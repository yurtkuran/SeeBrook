import SwiftUI
import SeebrookShared

/// Payments (ED-53): the selected property's balance and its history, following the Stitch
/// "Dues & Payments" design. Every figure comes from the server (ED-17); the app only formats
/// them. Paying joins this screen with checkout.
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

    private var property: PropertyResponse? { model.selectedProperty(in: account) }

    var body: some View {
        NavigationStack {
            List {
                if let property, let summary {
                    Section {
                        BalanceCard(property: property, summary: summary)
                    }
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
                }
                if summary != nil {
                    history
                }
                if loading && summary == nil {
                    ProgressView().frame(maxWidth: .infinity).listRowBackground(Color.clear)
                }
            }
            .overlay { emptyState }
            .refreshable { await reload() }
            .toolbar {
                ToolbarItem(placement: .principal) {
                    ScreenTitle(hoa: property?.hoaName, title: "Payments")
                }
            }
            .navigationBarTitleDisplayMode(.inline)
            .task(id: property?.id) { await reload() }
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
            Text("History")
        } footer: {
            if !transactions.isEmpty {
                Text("Newest first. Quote the reference when you ask your HOA about a transaction.")
            }
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
            summary = loaded
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

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if !compact {
                Label("\(property.unitLabel) · \(property.hoaName)", systemImage: "mappin.and.ellipse")
                    .font(.caption.weight(.medium))
                    .padding(.horizontal, 10)
                    .padding(.vertical, 5)
                    .background(.tint.opacity(0.1), in: Capsule())
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(summary.creditCents > 0 ? "CREDIT" : "CURRENT BALANCE")
                    .font(.caption.weight(.semibold))
                    .tracking(1)
                    .foregroundStyle(.secondary)
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Text(Money.text(summary.creditCents > 0 ? summary.creditCents : summary.amountDueCents, summary.currency))
                        .font(compact ? .title.weight(.bold) : .largeTitle.weight(.bold))
                    Text(summary.currency).font(.caption).foregroundStyle(.secondary)
                }
            }
            status
            if !compact {
                breakdown
            }
            if summary.pendingPaymentsCents > 0 {
                Label("\(Money.text(summary.pendingPaymentsCents, summary.currency)) in payments is processing. The balance changes once it settles.",
                      systemImage: "clock")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            if !compact, let updated = summary.updatedAt.flatMap({ try? Date($0, strategy: .iso8601) }) {
                Text("Updated by your HOA \(updated.formatted(date: .abbreviated, time: .omitted))")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.background, in: .rect(cornerRadius: 12))
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
                Text(transaction.description_).lineLimit(2)
                Text(detail)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 8)
            VStack(alignment: .trailing, spacing: 2) {
                Text(amount)
                    .font(.body.monospacedDigit())
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

/// Everything before the current owners' period, as one line (ED-42).
private struct CarriedForwardRow: View {
    let carriedForward: CarriedForwardResponse

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text("Balance carried forward")
                if let through = Day.date(carriedForward.throughDate) {
                    Text("Through \(through.formatted(date: .abbreviated, time: .omitted))")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            Spacer()
            Text(Money.text(carriedForward.amountCents, "USD")).font(.body.monospacedDigit())
        }
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
