import SwiftUI
import SeebrookShared

/// Payments (ED-53): the selected property's balance and its history, following the Stitch
/// "Dues & Payments" design. Every figure comes from the server (ED-17); the app only formats
/// them. **Make a Payment** opens checkout (ED-54).
struct PaymentsView: View {
    @Environment(AppModel.self) private var model
    let account: AppModel.Account

    @State private var summary: AccountSummaryResponse?
    @State private var transactions: [AccountTransactionResponse] = []
    @State private var carriedForward: CarriedForwardResponse?
    @State private var total = 0
    @State private var nextPage = 1
    @State private var loading = false
    @State private var error: String?
    @State private var onlinePayments: [PaymentResponse] = []
    @State private var paying = false
    #if DEBUG
    /// Scripted runs can open checkout once the balance loads (SIMCTL_CHILD_SEEBROOK_CHECKOUT=1).
    @State private var openCheckout = ProcessInfo.processInfo.environment["SEEBROOK_CHECKOUT"] != nil
    #endif

    private var property: PropertyResponse? { model.selectedProperty(in: account) }

    var body: some View {
        NavigationStack {
            List {
                if let property, let summary {
                    Section {
                        BalanceCard(property: property, summary: summary,
                                    onPay: summary.onlinePayments == "available" ? { paying = true } : nil)
                    }
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
                }
                if summary != nil {
                    if !onlinePayments.isEmpty { online }
                    history
                }
                if loading && summary == nil {
                    ProgressView().frame(maxWidth: .infinity).listRowBackground(Color.clear)
                }
            }
            .overlay { emptyState }
            .refreshable { await reload() }
            // The balance card names the unit and HOA, so the screen has no title bar (design).
            .toolbar(.hidden, for: .navigationBar)
            .task(id: property?.id) { await reload() }
            .sheet(isPresented: $paying) {
                if let property, let summary {
                    CheckoutView(property: property, summary: summary) { Task { await reload() } }
                }
            }
        }
    }

    @ViewBuilder private var history: some View {
        Section {
            ForEach(transactions, id: \.id) { transaction in
                TransactionRow(transaction: transaction)
                    .onAppear { if transaction.id == transactions.last?.id { loadMore() } }
            }
            if loading { ProgressView().frame(maxWidth: .infinity) }
            if let carriedForward, transactions.count >= total {
                CarriedForwardRow(carriedForward: carriedForward)
            }
            if transactions.isEmpty && carriedForward == nil && !loading {
                Text("No transactions yet.").foregroundStyle(.secondary)
            }
        } header: {
            SectionHeader("History")
        } footer: {
            if !transactions.isEmpty {
                Text("Newest first. Quote the reference when asking about a transaction.")
            }
        }
    }

    /// Recent online payments with their status: processing and failed ones appear only here.
    private var online: some View {
        Section {
            ForEach(onlinePayments.prefix(5), id: \.id) { payment in
                OnlinePaymentRow(payment: payment)
            }
        } header: {
            SectionHeader("Online payments")
        } footer: {
            Text("A payment joins the history once it settles.")
        }
    }

    @ViewBuilder private var emptyState: some View {
        if let error, summary == nil {
            ContentUnavailableView {
                Label("Balance unavailable", systemImage: "exclamationmark.triangle")
            } description: {
                Text(error)
            } actions: {
                Button("Try again") { Task { await reload() } }
            }
        } else if property == nil {
            ContentUnavailableView("No property", systemImage: "house",
                                   description: Text("Add a property to see its balance."))
        }
    }

    private func reload() async {
        guard let propertyId = property?.id else {
            summary = nil
            transactions = []
            return
        }
        error = nil
        loading = true
        defer { loading = false }
        do {
            async let firstPage = model.transactions(propertyId: propertyId, page: 1)
            let loaded = try await model.financeSummary(propertyId: propertyId)
            let page = try await firstPage
            // Optional: the balance and history still show if the payment list can't load.
            onlinePayments = (try? await model.payments(propertyId: propertyId)) ?? []
            summary = loaded
            #if DEBUG
            if openCheckout, loaded.onlinePayments == "available" {
                openCheckout = false
                paying = true
            }
            #endif
            transactions = page.transactions
            carriedForward = page.carriedForward
            total = Int(page.total)
            nextPage = 2
        } catch is CancellationError {
        } catch {
            if (error as? URLError)?.code == .cancelled { return }
            self.error = AppModel.message(for: error)
            summary = nil
            transactions = []
        }
    }

    private func loadMore() {
        guard !loading, transactions.count < total, let propertyId = property?.id else { return }
        Task {
            loading = true
            defer { loading = false }
            do {
                let page = try await model.transactions(propertyId: propertyId, page: nextPage)
                let known = Set(transactions.map(\.id))
                transactions += page.transactions.filter { !known.contains($0.id) }
                carriedForward = page.carriedForward
                total = Int(page.total)
                nextPage += 1
            } catch {
                self.error = AppModel.message(for: error)
            }
        }
    }
}

/// The balance: what is owed (or the credit), whether it is overdue or when it is due, and
/// payments still settling. Used on Payments and, compact, on Home.
struct BalanceCard: View {
    let property: PropertyResponse
    let summary: AccountSummaryResponse
    var compact = false
    /// Shown as **Make a Payment** when the HOA takes online payments now.
    var onPay: (() -> Void)?

    var body: some View {
        if compact {
            VStack(alignment: .leading, spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    caption
                    HStack(alignment: .firstTextBaseline, spacing: 6) {
                        amount.font(.title.weight(.bold))
                        Text(summary.currency).font(.caption).foregroundStyle(.secondary)
                    }
                }
                status
                pending
            }
            .padding(20)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(.background, in: .rect(cornerRadius: 12))
        } else {
            full
        }
    }

    /// The Payments card, following the owner's "Payments - Mobile" design (2026-11-25): the HOA
    /// logo and unit on top, then the balance and its status centered, then the Pay button.
    private var full: some View {
        VStack(spacing: 14) {
            ZStack {
                HStack(spacing: 4) {
                    Image(systemName: "mappin.and.ellipse").font(.caption.weight(.semibold))
                    Text("\(property.unitLabel) · \(property.hoaName)")
                }
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color(red: 0.23, green: 0.51, blue: 0.96))
                    .padding(.horizontal, 44)
                HoaLogo(property: property).frame(maxWidth: .infinity, alignment: .leading)
            }
            VStack(spacing: 6) {
                caption
                amount.font(.system(size: 38, weight: .bold)).monospacedDigit()
            }
            status
            breakdown
            pending.frame(maxWidth: .infinity, alignment: .leading)
            payment
            if let updated = summary.updatedAt.flatMap({ try? Date($0, strategy: .iso8601) }) {
                Text("Updated \(updated.formatted(date: .abbreviated, time: .omitted))")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity)
        .background(.background, in: .rect(cornerRadius: 14))
    }

    private var caption: some View {
        Text(summary.creditCents > 0 ? "CREDIT" : "CURRENT BALANCE")
            .font(.caption.weight(.semibold))
            .tracking(1)
            .foregroundStyle(.secondary)
    }

    private var amount: Text {
        Text(Money.text(summary.creditCents > 0 ? summary.creditCents : summary.amountDueCents, summary.currency))
    }

    @ViewBuilder private var pending: some View {
        if summary.pendingPaymentsCents > 0 {
            Label("\(Money.text(summary.pendingPaymentsCents, summary.currency)) in payments is processing. The balance changes once it settles.",
                  systemImage: "clock")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
    }

    @ViewBuilder private var payment: some View {
        if let onPay {
            Button(action: onPay) {
                HStack(spacing: 8) {
                    Image(systemName: "creditcard")
                    Text("Make a Payment")
                }
                    .font(.headline)
                    .frame(maxWidth: .infinity, minHeight: 48)
            }
            .buttonStyle(.borderedProminent)
            .buttonBorderShape(.roundedRectangle(radius: 12))
        } else if summary.onlinePayments == "unavailable" {
            Label("Online payments are paused for now. Your balance and history are still up to date.", systemImage: "pause.circle")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
    }

    /// Overdue first, then the next due date, else paid up.
    @ViewBuilder private var status: some View {
        if summary.overdueCents > 0 {
            chip("\(Money.text(summary.overdueCents, summary.currency)) overdue", color: .red)
        } else if summary.notYetDueCents > 0, let due = summary.nextDueDate.flatMap(Day.date) {
            chip("Due \(due.formatted(date: .abbreviated, time: .omitted))", color: .orange)
        } else if summary.creditCents > 0 {
            chip("Applied to your next charges", color: .green)
        } else {
            chip("Paid up", color: .green)
        }
    }

    @ViewBuilder private var breakdown: some View {
        if summary.overdueCents > 0 && summary.notYetDueCents > 0 {
            VStack(spacing: 6) {
                row("Overdue", summary.overdueCents)
                row("Not yet due", summary.notYetDueCents)
            }
            .font(.subheadline)
        }
    }

    private func row(_ label: String, _ cents: Int64) -> some View {
        HStack {
            Text(label).foregroundStyle(.secondary)
            Spacer()
            Text(Money.text(cents, summary.currency))
        }
    }

    private func chip(_ text: String, color: Color) -> some View {
        HStack(spacing: 6) {
            Circle().fill(color).frame(width: 7, height: 7)
            Text(text)
        }
        .font(.subheadline.weight(.medium))
        .padding(.horizontal, 10)
        .padding(.vertical, 5)
        .background(color.opacity(0.12), in: Capsule())
    }
}

/// One transaction: what it was, when, its reference, the amount and the balance after it.
private struct TransactionRow: View {
    let transaction: AccountTransactionResponse

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .foregroundStyle(.tint)
                .frame(width: 40, height: 40)
                .background(.tint.opacity(0.1), in: .rect(cornerRadius: 8))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                // Kotlin's `description` is `description_` in Swift (NSObject has its own).
                Text(transaction.description_)
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)
                // Two lines, so the reference owners are asked to quote is never cut off.
                Text(detail)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }
            Spacer(minLength: 8)
            VStack(alignment: .trailing, spacing: 2) {
                Text(amount)
                    .font(.subheadline.weight(.semibold).monospacedDigit())
                    .foregroundStyle(transaction.amountCents < 0 ? .green : .primary)
                Text("Balance \(Money.text(transaction.balanceAfterCents, "USD"))")
                    .font(.caption2.monospacedDigit())
                    .foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .combine)
    }

    private var icon: String {
        switch transaction.type {
        case "payment": "checkmark.circle"
        case "late_fee": "exclamationmark.circle"
        case "waiver": "arrow.uturn.backward.circle"
        case "adjustment": "plusminus.circle"
        default: "building.2"
        }
    }

    /// Charges add to the balance; payments and credits (negative) reduce it.
    private var amount: String {
        transaction.amountCents < 0
            ? "−\(Money.text(-transaction.amountCents, "USD"))"
            : Money.text(transaction.amountCents, "USD")
    }

    private var detail: String {
        var parts = [Day.date(transaction.date)?.formatted(date: .abbreviated, time: .omitted) ?? transaction.date]
        if let due = transaction.dueDate.flatMap(Day.date), transaction.amountCents > 0 {
            parts.append("due \(due.formatted(date: .abbreviated, time: .omitted))")
        }
        parts.append(transaction.reference)
        return parts.joined(separator: " · ")
    }
}

/// One online payment: method, reference, total and status.
private struct OnlinePaymentRow: View {
    let payment: PaymentResponse

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text("\(CheckoutView.methodName(payment.method)) payment")
                    .font(.subheadline.weight(.semibold))
                Text(detail).font(.caption).foregroundStyle(.secondary)
                if let reason = payment.failureReason, payment.status == "failed" {
                    Text(reason).font(.caption).foregroundStyle(.red)
                }
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 4) {
                Text(Money.text(payment.totalCents, payment.currency))
                    .font(.subheadline.weight(.semibold).monospacedDigit())
                Text(status.0)
                    .font(.caption2.weight(.semibold))
                    .padding(.horizontal, 8)
                    .padding(.vertical, 2)
                    .background(status.1.opacity(0.15), in: Capsule())
                    .foregroundStyle(status.1)
            }
        }
        .accessibilityElement(children: .combine)
    }

    private var detail: String {
        let date = (try? Date(payment.createdAt, strategy: .iso8601))?.formatted(date: .abbreviated, time: .omitted)
        return [date, payment.reference].compactMap { $0 }.joined(separator: " · ")
    }

    private var status: (String, Color) {
        switch payment.status {
        case "succeeded": ("Paid", .green)
        case "processing": ("Processing", .orange)
        case "failed": ("Failed", .red)
        case "canceled": ("Canceled", .secondary)
        default: ("Started", .blue)
        }
    }
}

/// The HOA's logo on the balance card (ED-55); a neutral tile while it loads or when the HOA has none.
private struct HoaLogo: View {
    @Environment(AppModel.self) private var model
    let property: PropertyResponse
    @State private var image: UIImage?

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 36, height: 36)
                    .clipShape(.rect(cornerRadius: 8))
                    .shadow(color: .black.opacity(0.06), radius: 1, y: 1)
            } else {
                Image(systemName: "building.2.fill")
                    .font(.subheadline)
                    .foregroundStyle(.tint)
                    .frame(width: 36, height: 36)
                    .background(.tint.opacity(0.1), in: .rect(cornerRadius: 8))
            }
        }
        .accessibilityHidden(true)
        .task(id: "\(property.id) \(property.hoaLogoVersion ?? "")") {
            image = await model.hoaLogo(for: property)
        }
    }
}

/// Everything before the current owners' period, as one line (ED-42).
private struct CarriedForwardRow: View {
    let carriedForward: CarriedForwardResponse

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "arrow.turn.left.up")
                .foregroundStyle(.tint)
                .frame(width: 40, height: 40)
                .background(.tint.opacity(0.1), in: .rect(cornerRadius: 8))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text("Balance carried forward").font(.subheadline.weight(.semibold))
                if let through = Day.date(carriedForward.throughDate) {
                    Text("Through \(through.formatted(date: .abbreviated, time: .omitted))")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            Spacer()
            Text(Money.text(carriedForward.amountCents, "USD"))
                .font(.subheadline.weight(.semibold).monospacedDigit())
        }
    }
}

/// A list section header in the designs' style: small, uppercase, gray.
struct SectionHeader: View {
    let title: String
    init(_ title: String) { self.title = title }

    var body: some View {
        Text(title)
            .font(.footnote.weight(.semibold))
            .textCase(.uppercase)
            .foregroundStyle(.secondary)
    }
}

/// The screen header from the designs: the HOA name over the screen's title.
struct ScreenTitle: View {
    let hoa: String?
    let title: String

    var body: some View {
        VStack(spacing: 0) {
            Text((hoa ?? "Seebrook").uppercased())
                .font(.caption.weight(.semibold))
                .tracking(1.2)
                .foregroundStyle(.tint)
            Text(title).font(.headline)
        }
    }
}

/// Formats the server's integer cents; never computes with them.
enum Money {
    static func text(_ cents: Int64, _ currency: String) -> String {
        (Decimal(cents) / 100).formatted(.currency(code: currency))
    }
}

/// The server's calendar dates (`2026-11-24`), shown as the same day in every time zone.
enum Day {
    static func date(_ text: String) -> Date? {
        try? Date("\(text)T12:00:00Z", strategy: .iso8601)
    }
}
