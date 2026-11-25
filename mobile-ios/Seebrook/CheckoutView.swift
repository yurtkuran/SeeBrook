import SwiftUI
import SeebrookShared
import StripePaymentSheet

/// Paying dues (ED-43, ED-54), following the Stitch "Review & Pay Dues" design: the owner picks an
/// amount, the server quotes the fee and total for each method the HOA accepts, the owner approves
/// one, and the app starts exactly that payment. Card and bank details are never entered here:
/// Stripe's payment sheet collects them (X-02, ED-58).
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
    /// Stripe's sheet for the started payment, shown when it is set.
    @State private var stripeSheet: PaymentSheet?
    @State private var showingStripeSheet = false
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
            .navigationTitle(started == nil ? "Pay dues" : "Payment submitted")
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
        .interactiveDismissDisabled(working || showingStripeSheet)
        .background {
            if let stripeSheet {
                Color.clear.paymentSheet(isPresented: $showingStripeSheet, paymentSheet: stripeSheet, onCompletion: stripeFinished)
            }
        }
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
            switch payment.provider {
            case "simulated":
                started = payment
            case "stripe":
                // The same approval returns the same payment, so after the owner closes the sheet
                // without paying, Pay opens the sheet for that payment again (ED-58).
                if payment.clientParams == nil {
                    // Already submitted (for example, a retry after the sheet finished): nothing left to enter.
                    started = payment
                    return
                }
                guard let sheet = Self.stripeSheet(for: payment, hoaName: property.hoaName) else {
                    message = "This payment can't be completed in the app. Nothing was charged."
                    return
                }
                pending = payment
                stripeSheet = sheet
                showingStripeSheet = true
            default:
                message = "Paying this way isn't available in this version of the app. Nothing was charged."
            }
        } catch where AppModel.apiCode(error) == "quote_changed" {
            // Nothing was charged: show the new figures and let the owner confirm again.
            working = false
            await requote(notice: "The fee or total changed. Please review the new total.")
        } catch {
            // Keep the approval and its key: trying again cannot pay twice.
            message = AppModel.message(for: error)
        }
    }

    // MARK: Stripe (ED-58)

    /// The payment the Stripe sheet is completing.
    @State private var pending: PaymentResponse?

    /// Stripe's payment sheet for a started payment, or nil when the client parameters are incomplete.
    /// The payment is a direct charge on the HOA's own Stripe account, so the sheet talks to Stripe
    /// as that account. No Stripe customer is given, so nothing is saved for later payments.
    private static func stripeSheet(for payment: PaymentResponse, hoaName: String) -> PaymentSheet? {
        guard let params = payment.clientParams,
              let clientSecret = params["clientSecret"],
              let publishableKey = params["publishableKey"],
              let account = params["stripeAccount"] else { return nil }
        let client = STPAPIClient(publishableKey: publishableKey)
        client.stripeAccount = account
        var configuration = PaymentSheet.Configuration()
        configuration.apiClient = client
        configuration.merchantDisplayName = hoaName
        // Bank payments settle days later; the payment shows as processing until then.
        configuration.allowsDelayedPaymentMethods = true
        return PaymentSheet(paymentIntentClientSecret: clientSecret, configuration: configuration)
    }

    private func stripeFinished(_ result: PaymentSheetResult) {
        stripeSheet = nil
        switch result {
        case .completed:
            if let pending { started = pending }
            pending = nil
            onStarted()
        case .canceled:
            // Keep the approval: Pay reopens the sheet for the same payment, never a second one.
            message = "Payment not completed. Nothing was charged."
        case .failed(let error):
            message = error.localizedDescription
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

/// After a payment was submitted: what happens next.
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
            if payment.provider == "simulated" {
                Text("Test payment: no money moves. It stays pending until it is completed on the development server.")
                #if DEBUG
                Text(verbatim: "$APP dev-payment-event --hoa <slug> --payment \(payment.reference) --status succeeded")
                    .font(.caption.monospaced())
                    .textSelection(.enabled)
                #endif
            } else if payment.method == "ach" {
                Text("Bank payments take a few business days. It shows as processing on the Payments tab until it settles, and your balance updates then.")
            } else {
                Text("Your card payment is being confirmed. Your balance updates within a minute or two.")
            }
        } header: {
            Text("What happens next")
        } footer: {
            Text("The payment shows on the Payments tab. You'll get an email receipt once it settles.")
        }
    }
}
