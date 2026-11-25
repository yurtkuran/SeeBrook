import SwiftUI
import SeebrookShared

/// Paying dues (ED-43, ED-54), following the Stitch "Review & Pay Dues" design: the owner picks an
/// amount, the server quotes the fee and total for each method the HOA accepts, the owner approves
/// one, and the app starts exactly that payment. Card and bank details are never entered here:
/// the provider's own SDK collects them (X-02).
struct CheckoutView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let property: PropertyResponse
    let summary: AccountSummaryResponse
    /// Called after a payment starts, so the balance and payments reload.
    let onStarted: () -> Void

    @State private var amountText = ""
    @State private var quote: PaymentQuoteResponse?
    @State private var method: String?
    @State private var working = false
    @State private var message: String?
    @State private var started: PaymentResponse?
    /// The approval being paid and its idempotency key: retrying the same approval reuses the key,
    /// so a repeated tap or a retry after a dropped connection never pays twice.
    @State private var approval: (Approval, String)?
    @FocusState private var amountFocused: Bool

    init(property: PropertyResponse, summary: AccountSummaryResponse, onStarted: @escaping () -> Void) {
        self.property = property
        self.summary = summary
        self.onStarted = onStarted
        _amountText = State(initialValue: summary.amountDueCents > 0 ? Self.editable(summary.amountDueCents) : "")
    }

    private struct Approval: Equatable {
        let method: String
        let amountCents: Int64
        let feeCents: Int64
        let totalCents: Int64
    }

    var body: some View {
        NavigationStack {
            Form {
                if let started {
                    StartedView(payment: started)
                } else {
                    form
                }
            }
            .disabled(working)
            .navigationTitle(started == nil ? "Pay dues" : "Payment started")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    if started == nil { Button("Cancel") { dismiss() } }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if started != nil { Button("Done") { dismiss() } }
                }
            }
            .safeAreaInset(edge: .bottom) {
                if started == nil, let option = selectedOption {
                    payButton(option)
                }
            }
        }
        .task { await requote() }
        .interactiveDismissDisabled(working)
    }

    // MARK: Form

    @ViewBuilder private var form: some View {
        Section {
            VStack(alignment: .leading, spacing: 2) {
                Text(property.unitLabel).font(.headline)
                Text(property.hoaName).font(.subheadline).foregroundStyle(.secondary)
            }
        }
        Section {
            HStack {
                Text("Amount")
                Spacer()
                TextField("0.00", text: $amountText)
                    .keyboardType(.decimalPad)
                    .multilineTextAlignment(.trailing)
                    .font(.title3.monospacedDigit())
                    .focused($amountFocused)
                    .onSubmit { Task { await requote() } }
            }
            if amountFocused {
                Button("Update total") {
                    amountFocused = false
                    Task { await requote() }
                }
            }
        } header: {
            Text("Dues")
        } footer: {
            if summary.amountDueCents > 0 {
                Text("You owe \(Money.text(summary.amountDueCents, summary.currency)). You can pay a different amount.")
            }
        }
        if let message {
            Section { Text(message).foregroundStyle(.red) }
        }
        if let quote {
            Section("Pay by") {
                ForEach(quote.options, id: \.method) { option in
                    Button { method = option.method } label: {
                        HStack {
                            Image(systemName: method == option.method ? "largecircle.fill.circle" : "circle")
                                .foregroundStyle(.tint)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(Self.methodName(option.method))
                                Text(option.feeCents == 0 ? "No fee" : "Fee \(Money.text(option.feeCents, quote.currency))")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            Text(Money.text(option.totalCents, quote.currency)).monospacedDigit()
                        }
                    }
                    .foregroundStyle(.primary)
                    .accessibilityAddTraits(method == option.method ? .isSelected : [])
                }
            }
            if let option = selectedOption {
                Section {
                    row("Dues", quote.amountCents, quote.currency)
                    row("\(Self.methodName(option.method)) fee", option.feeCents, quote.currency)
                    row("Total", option.totalCents, quote.currency).fontWeight(.semibold)
                } footer: {
                    Text("Your HOA's balance updates once the payment settles: usually right away for cards, a few business days for bank transfers.")
                }
            }
        } else if working {
            Section { ProgressView().frame(maxWidth: .infinity) }
        }
    }

    private func row(_ label: String, _ cents: Int64, _ currency: String) -> some View {
        HStack {
            Text(label)
            Spacer()
            Text(Money.text(cents, currency)).monospacedDigit()
        }
    }

    private func payButton(_ option: PaymentOptionResponse) -> some View {
        Button {
            Task { await pay(option) }
        } label: {
            Group {
                if working {
                    ProgressView().tint(.white)
                } else {
                    Label("Pay \(Money.text(option.totalCents, quote?.currency ?? "USD"))", systemImage: "lock.fill")
                }
            }
            .font(.headline)
            .frame(maxWidth: .infinity, minHeight: 50)
        }
        .buttonStyle(.borderedProminent)
        .disabled(working || amountFocused)
        .padding()
        .background(.bar)
    }

    private var selectedOption: PaymentOptionResponse? {
        quote?.options.first { $0.method == method }
    }

    // MARK: Actions

    /// Asks the server for the fee and total of the entered amount. Any change to the quote ends
    /// the previous approval, so the next payment gets a new key.
    private func requote(notice: String? = nil) async {
        guard let cents = Self.cents(amountText) else {
            quote = nil
            message = amountText.isEmpty ? nil : "Enter an amount such as 125.00."
            return
        }
        working = true
        defer { working = false }
        do {
            let fresh = try await model.paymentQuote(propertyId: property.id, amountCents: cents)
            quote = fresh
            approval = nil
            if !fresh.options.contains(where: { $0.method == method }) { method = fresh.options.first?.method }
            message = notice
        } catch {
            quote = nil
            message = AppModel.message(for: error)
        }
    }

    private func pay(_ option: PaymentOptionResponse) async {
        guard let quote else { return }
        let current = Approval(method: option.method, amountCents: quote.amountCents,
                               feeCents: option.feeCents, totalCents: option.totalCents)
        let key = approval?.0 == current ? approval!.1 : UUID().uuidString
        approval = (current, key)
        working = true
        defer { working = false }
        do {
            let payment = try await model.startPayment(propertyId: property.id, option: option,
                                                       amountCents: quote.amountCents, idempotencyKey: key)
            onStarted()
            guard payment.provider == "simulated" else {
                // Stripe's payment sheet, which collects the card or bank details, arrives with
                // the Stripe account (ED-54). The started payment is never charged without it.
                message = "Entering card or bank details isn't available in this version of the app yet. Nothing was charged."
                return
            }
            started = payment
        } catch where AppModel.apiCode(error) == "quote_changed" {
            // Nothing was charged: show the new figures and let the owner confirm again.
            working = false
            await requote(notice: "The fee or total changed. Please review the new total.")
        } catch {
            // Keep the approval and its key: trying again cannot pay twice.
            message = AppModel.message(for: error)
        }
    }

    // MARK: Formatting the owner's input

    static func methodName(_ method: String) -> String {
        switch method {
        case "card": "Card"
        case "ach": "Bank transfer"
        default: method
        }
    }

    /// "950.00" for 95000 cents, for the amount field.
    private static func editable(_ cents: Int64) -> String {
        (Decimal(cents) / 100).formatted(.number.precision(.fractionLength(2)).grouping(.never))
    }

    /// The entered amount in cents: digits with at most two decimals, else nil. The server decides
    /// whether the amount can be paid.
    static func cents(_ text: String) -> Int64? {
        let cleaned = text.replacingOccurrences(of: "$", with: "")
            .replacingOccurrences(of: ",", with: "")
            .trimmingCharacters(in: .whitespaces)
        guard !cleaned.isEmpty, cleaned.wholeMatch(of: /\d{1,7}(\.\d{0,2})?/) != nil,
              let value = Decimal(string: cleaned, locale: Locale(identifier: "en_US_POSIX")) else { return nil }
        return NSDecimalNumber(decimal: value * 100).int64Value
    }
}

/// After a simulated payment started (development only): what happens next.
private struct StartedView: View {
    let payment: PaymentResponse

    var body: some View {
        Section {
            VStack(alignment: .leading, spacing: 8) {
                Label("\(Money.text(payment.totalCents, payment.currency)) by \(CheckoutView.methodName(payment.method).lowercased())",
                      systemImage: "checkmark.seal.fill")
                    .font(.headline)
                    .foregroundStyle(.tint)
                Text("Reference \(payment.reference)").font(.subheadline).foregroundStyle(.secondary)
            }
        }
        Section {
            Text("Test payment: no money moves. It stays pending until it is completed on the development server.")
            #if DEBUG
            Text(verbatim: "$APP dev-payment-event --hoa <slug> --payment \(payment.reference) --status succeeded")
                .font(.caption.monospaced())
                .textSelection(.enabled)
            #endif
        } header: {
            Text("What happens next")
        } footer: {
            Text("The payment shows on the Payments tab. You'll get an email receipt once it settles.")
        }
    }
}
