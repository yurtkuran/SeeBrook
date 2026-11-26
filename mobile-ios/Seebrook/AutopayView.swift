import SwiftUI
import SeebrookShared
import StripePaymentSheet

/// Autopay (ED-59, ED-60): the owner's recurring payment for the property. The owner saves a card or
/// bank account through Stripe's sheet, picks one of the HOA's schedules and a fixed amount, and
/// approves the exact fee and total the server quotes. The server decides when to charge; the app
/// only shows its dates and sends what the owner approved (ED-17).
struct AutopayView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let property: PropertyResponse
    /// Called after anything changed, so the Payments tab reloads its autopay row.
    let onChanged: () -> Void

    @State private var overview: RecurringResponse?
    @State private var loading = false
    @State private var working = false
    @State private var message: String?
    @State private var notice: String?
    /// Showing the plan form: setting up, changing, or approving again.
    @State private var editing = false

    // The form
    @State private var frequency: String?
    @State private var methodId: String?
    @State private var amountText = ""
    @State private var quote: PaymentOptionResponse?
    @State private var quoting = false
    @FocusState private var amountFocused: Bool

    // Confirming before a change that can lead to charges (the server's 403 `reauthentication_required`)
    @State private var reauthenticating = false
    @State private var afterReauthentication: (() async -> Void)?

    // Saving a method with Stripe's sheet
    @State private var stripeSheet: PaymentSheet?
    @State private var showingStripeSheet = false
    @State private var stripeSetupId: String?

    @State private var confirmingCancel = false
    @State private var removing: SavedMethodResponse?

    var body: some View {
        NavigationStack {
            Form {
                if let overview {
                    content(overview)
                } else if loading {
                    ProgressView().frame(maxWidth: .infinity)
                } else if let message {
                    Section { Text(message).foregroundStyle(.red) }
                    Button("Try again") { Task { await load() } }
                }
            }
            .disabled(working)
            .navigationTitle(editing && overview?.plan != nil ? "Change autopay" : "Autopay")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    if editing && overview?.plan != nil {
                        Button("Back") { editing = false; message = nil }
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .safeAreaInset(edge: .bottom) {
                if editing, let overview, overview.available { approveButton }
            }
            .refreshable { await load() }
        }
        .task { await load() }
        .interactiveDismissDisabled(working || showingStripeSheet)
        .sheet(isPresented: $reauthenticating) {
            ReauthenticationView { confirmed in
                reauthenticating = false
                let next = afterReauthentication
                afterReauthentication = nil
                if confirmed, let next {
                    Task {
                        // Let this sheet finish closing first: the retried action may open Stripe's sheet.
                        try? await Task.sleep(for: .milliseconds(600))
                        await next()
                    }
                }
            }
        }
        .confirmationDialog("Cancel autopay?", isPresented: $confirmingCancel, titleVisibility: .visible) {
            Button("Cancel autopay", role: .destructive) { Task { await change("cancel") } }
            Button("Keep autopay", role: .cancel) {}
        } message: {
            Text("No further automatic payments will be made. Payments already submitted still complete.")
        }
        .confirmationDialog("Remove this payment method?", isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }),
                            titleVisibility: .visible, presenting: removing) { method in
            Button("Remove \(method.label)", role: .destructive) { Task { await remove(method) } }
        }
        .background {
            if let stripeSheet {
                Color.clear.paymentSheet(isPresented: $showingStripeSheet, paymentSheet: stripeSheet, onCompletion: stripeFinished)
            }
        }
    }

    // MARK: Content

    @ViewBuilder private func content(_ overview: RecurringResponse) -> some View {
        Section {
            PropertyHeader(property: property)
        }
        .listRowBackground(Color.clear)
        if let notice {
            Section { Label(notice, systemImage: "checkmark.circle.fill").foregroundStyle(.green) }
        }
        if let message {
            Section { Text(message).foregroundStyle(.red) }
        }
        if !overview.available {
            Section {
                Label("Online payments are paused right now, so autopay can't be set up or changed. Your existing autopay is kept.",
                      systemImage: "pause.circle")
                    .foregroundStyle(.secondary)
            }
        }
        if let plan = overview.plan, !editing {
            PlanSummary(plan: plan)
            planActions(plan, available: overview.available)
        } else if overview.options.isEmpty {
            Section {
                Text("\(property.hoaName) doesn't offer autopay yet. You can still pay each time on the Payments tab.")
                    .foregroundStyle(.secondary)
            }
        } else {
            form(overview)
        }
    }

    @ViewBuilder private func planActions(_ plan: RecurringPlanResponse, available: Bool) -> some View {
        Section {
            if plan.status == "needs_approval" {
                Button("Review and approve") { startEditing(plan) }.disabled(!available)
            } else {
                Button("Change amount, schedule or method") { startEditing(plan) }.disabled(!available)
            }
            if plan.status == "active" {
                Button("Pause autopay") { Task { await change("pause") } }
            } else if plan.status == "paused" {
                Button("Resume autopay") { Task { await change("resume") } }.disabled(!available)
            }
            Button("Cancel autopay", role: .destructive) { confirmingCancel = true }
        } footer: {
            Text("Each payment gets its own email receipt and shows on the Payments tab.")
        }
    }

    // MARK: The plan form

    @ViewBuilder private func form(_ overview: RecurringResponse) -> some View {
        Section {
            ForEach(overview.options, id: \.frequency) { option in
                choice(selected: frequency == option.frequency) {
                    frequency = option.frequency
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(Self.frequencyName(option.frequency))
                        Text("First payment \(Self.day(option.firstChargeDate))")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
        } header: {
            Text("How often")
        } footer: {
            Text("Payments are made on \(property.hoaName)'s schedule. A date on a weekend or bank holiday moves to the next business day.")
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
            Text("Dues each time")
        } footer: {
            Text("The same amount is paid each time. Enter the dues for the whole period: for quarterly, three months of dues.")
        }
        Section {
            ForEach(usableMethods(overview), id: \.id) { method in
                choice(selected: methodId == method.id) {
                    methodId = method.id
                    Task { await requote() }
                } label: {
                    Label(method.label, systemImage: method.method == "ach" ? "building.columns" : "creditcard")
                }
                .swipeActions {
                    if overview.plan?.methodId != method.id {
                        Button("Remove", role: .destructive) { removing = method }
                    }
                }
            }
            ForEach(overview.acceptedMethods, id: \.self) { kind in
                Button {
                    Task { await addMethod(kind) }
                } label: {
                    Label(kind == "ach" ? "Add a bank account" : "Add a card", systemImage: "plus.circle")
                }
                .disabled(!overview.available)
            }
        } header: {
            Text("Pay with")
        } footer: {
            Text("Card and bank details are entered with Stripe, our payment processor, and kept by Stripe. Seebrook only sees the last four digits.")
        }
        if let quote, let cents = CheckoutView.cents(amountText) {
            Section {
                row("Dues", cents)
                row("\(CheckoutView.methodName(quote.method)) fee", quote.feeCents)
                row("Each payment", quote.totalCents).fontWeight(.semibold)
            } footer: {
                if let frequency, let first = overview.options.first(where: { $0.frequency == frequency }),
                   let method = overview.methods.first(where: { $0.id == methodId }) {
                    Text("By turning on autopay, you authorize \(property.hoaName) to charge \(method.label) \(Money.text(quote.totalCents, "USD")) \(Self.every(frequency)), starting \(Self.day(first.firstChargeDate)), until you cancel. You can pause or cancel any time in the app. If the fee changes, we ask you first.")
                }
            }
        } else if quoting {
            Section { ProgressView().frame(maxWidth: .infinity) }
        }
    }

    private var approveButton: some View {
        Button {
            Task { await approve() }
        } label: {
            Group {
                if working {
                    ProgressView().tint(.white)
                } else if let quote {
                    Label(overview?.plan == nil ? "Turn on autopay · \(Money.text(quote.totalCents, "USD"))"
                                                : "Approve · \(Money.text(quote.totalCents, "USD"))",
                          systemImage: "lock.fill")
                } else {
                    Text(overview?.plan == nil ? "Turn on autopay" : "Approve")
                }
            }
            .font(.headline)
            .frame(maxWidth: .infinity, minHeight: 50)
        }
        .buttonStyle(.borderedProminent)
        .disabled(working || amountFocused || quote == nil || frequency == nil || methodId == nil)
        .padding()
        .background(.bar)
    }

    private func choice<Content: View>(selected: Bool, action: @escaping () -> Void, @ViewBuilder label: () -> Content) -> some View {
        Button(action: action) {
            HStack {
                Image(systemName: selected ? "largecircle.fill.circle" : "circle").foregroundStyle(.tint)
                label()
                Spacer()
            }
        }
        .foregroundStyle(.primary)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private func row(_ label: String, _ cents: Int64) -> some View {
        HStack {
            Text(label)
            Spacer()
            Text(Money.text(cents, "USD")).monospacedDigit()
        }
    }

    /// Saved methods the HOA still accepts.
    private func usableMethods(_ overview: RecurringResponse) -> [SavedMethodResponse] {
        overview.methods.filter { overview.acceptedMethods.contains($0.method) }
    }

    private func startEditing(_ plan: RecurringPlanResponse?) {
        guard let overview else { return }
        message = nil
        notice = nil
        frequency = plan?.frequency ?? (overview.options.count == 1 ? overview.options.first?.frequency : nil)
        let methods = usableMethods(overview)
        methodId = methods.contains { $0.id == plan?.methodId } ? plan?.methodId : methods.first?.id
        amountText = plan.map { CheckoutView.editable($0.duesCents) } ?? ""
        quote = nil
        editing = true
        Task { await requote() }
    }

    // MARK: Actions

    private func load() async {
        loading = true
        defer { loading = false }
        await finishPendingSetup()
        do {
            let loaded = try await model.recurring(propertyId: property.id)
            overview = loaded
            if loaded.plan == nil && !editing { startEditing(nil) }
        } catch is CancellationError {
        } catch {
            if overview == nil { message = AppModel.message(for: error) }
        }
    }

    /// The fee and total for the entered amount and chosen method, from the server's payment quote:
    /// autopay charges the same fee as paying now.
    private func requote() async {
        quote = nil
        guard let cents = CheckoutView.cents(amountText),
              let method = overview?.methods.first(where: { $0.id == methodId }) else {
            if !amountText.isEmpty && CheckoutView.cents(amountText) == nil { message = "Enter an amount such as 125.00." }
            return
        }
        quoting = true
        defer { quoting = false }
        do {
            let fresh = try await model.paymentQuote(propertyId: property.id, amountCents: cents)
            quote = fresh.options.first { $0.method == method.method }
            message = quote == nil ? "\(property.hoaName) doesn't accept this payment method any more. Choose another." : nil
        } catch {
            message = AppModel.message(for: error)
        }
    }

    private func approve() async {
        guard let quote, let frequency, let methodId, let cents = CheckoutView.cents(amountText) else { return }
        await confirmed {
            let plan = try await model.setUpRecurringPlan(propertyId: property.id, methodId: methodId, frequency: frequency,
                                                          duesCents: cents, feeCents: quote.feeCents, totalCents: quote.totalCents)
            editing = false
            notice = plan.nextChargeDate.map { "Autopay is on. The first payment is on \(Self.day($0))." } ?? "Autopay is on."
            await reload()
        } quoteChanged: {
            await requote()
            message = "The fee or total changed. Please review the new total."
        }
    }

    private func change(_ action: String) async {
        await confirmed {
            _ = try await model.changeRecurringPlan(propertyId: property.id, action: action)
            notice = switch action {
            case "pause": "Autopay is paused. Nothing is charged until you resume it."
            case "resume": "Autopay is on again."
            default: "Autopay is canceled."
            }
            await reload()
        }
    }

    private func remove(_ method: SavedMethodResponse) async {
        await confirmed {
            try await model.removeSavedMethod(propertyId: property.id, methodId: method.id)
            if methodId == method.id { methodId = nil; quote = nil }
            await reload()
        }
    }

    /// Starts saving a card or bank account: Stripe's sheet collects the details, then the server
    /// checks with Stripe that it was saved.
    private func addMethod(_ kind: String) async {
        await confirmed {
            let setup = try await model.startMethodSetup(propertyId: property.id, method: kind)
            switch setup.provider {
            case "simulated":
                await complete(setup.setupId)
            case "stripe":
                guard let sheet = Self.stripeSheet(for: setup, hoaName: property.hoaName) else {
                    message = "This payment method can't be saved in the app."
                    return
                }
                stripeSetupId = setup.setupId
                stripeSheet = sheet
                showingStripeSheet = true
            default:
                message = "Saving a payment method isn't available in this version of the app."
            }
        }
    }

    private func stripeFinished(_ result: PaymentSheetResult) {
        stripeSheet = nil
        let setupId = stripeSetupId
        stripeSetupId = nil
        switch result {
        case .completed:
            if let setupId { Task { await complete(setupId) } }
        case .canceled:
            message = "The payment method wasn't saved."
        case .failed(let error):
            message = error.localizedDescription
        }
    }

    private func complete(_ setupId: String) async {
        working = true
        defer { working = false }
        do {
            let saved = try await model.completeMethodSetup(propertyId: property.id, setupId: setupId)
            PendingSetups.remove(propertyId: property.id)
            methodId = saved.id
            notice = "\(saved.label) is saved."
            await reload()
            await requote()
        } catch where AppModel.apiCode(error) == "setup_pending" {
            // Bank accounts can take a while to verify: finish next time Autopay opens.
            PendingSetups.save(setupId, propertyId: property.id)
            message = AppModel.message(for: error)
        } catch {
            message = AppModel.message(for: error)
        }
    }

    /// A bank account still verifying when Autopay was last open.
    private func finishPendingSetup() async {
        guard let setupId = PendingSetups.load(propertyId: property.id) else { return }
        do {
            let saved = try await model.completeMethodSetup(propertyId: property.id, setupId: setupId)
            PendingSetups.remove(propertyId: property.id)
            methodId = saved.id
            notice = "\(saved.label) is saved."
        } catch where AppModel.apiCode(error) == "setup_pending" || (error as? URLError) != nil {
            // Still verifying, or offline: try again next time.
        } catch {
            PendingSetups.remove(propertyId: property.id)
        }
    }

    private func reload() async {
        if let loaded = try? await model.recurring(propertyId: property.id) { overview = loaded }
        onChanged()
    }

    /// Runs a change; when the server asks for a recent sign-in, asks the owner to sign in again and
    /// then runs it once more. A changed quote calls [quoteChanged] instead of showing an error.
    private func confirmed(_ action: @escaping () async throws -> Void, quoteChanged: (() async -> Void)? = nil) async {
        message = nil
        notice = nil
        working = true
        defer { working = false }
        do {
            try await action()
        } catch where AppModel.apiCode(error) == "reauthentication_required" {
            afterReauthentication = { await confirmed(action, quoteChanged: quoteChanged) }
            reauthenticating = true
        } catch where AppModel.apiCode(error) == "quote_changed" && quoteChanged != nil {
            working = false
            await quoteChanged?()
        } catch {
            message = AppModel.message(for: error)
        }
    }

    // MARK: Stripe (ED-58, ED-60)

    /// Stripe's sheet for saving a method against the server's SetupIntent on the HOA's own Stripe
    /// account. Stripe keeps the details; the server stores only a reference and a label.
    private static func stripeSheet(for setup: MethodSetupResponse, hoaName: String) -> PaymentSheet? {
        guard let clientSecret = setup.clientParams["setupClientSecret"],
              let publishableKey = setup.clientParams["publishableKey"],
              let account = setup.clientParams["stripeAccount"] else { return nil }
        let client = STPAPIClient(publishableKey: publishableKey)
        client.stripeAccount = account
        var configuration = PaymentSheet.Configuration()
        configuration.apiClient = client
        configuration.merchantDisplayName = hoaName
        configuration.allowsDelayedPaymentMethods = true
        return PaymentSheet(setupIntentClientSecret: clientSecret, configuration: configuration)
    }

    // MARK: Formatting

    static func frequencyName(_ code: String) -> String {
        switch code {
        case "monthly": "Monthly"
        case "quarterly": "Quarterly"
        case "annual": "Yearly"
        default: code
        }
    }

    static func every(_ code: String) -> String {
        switch code {
        case "monthly": "every month"
        case "quarterly": "every three months"
        case "annual": "every year"
        default: code
        }
    }

    static func day(_ text: String) -> String {
        Day.date(text)?.formatted(date: .abbreviated, time: .omitted) ?? text
    }
}

/// The owner's plan: status, what each payment is and when the next one is.
private struct PlanSummary: View {
    let plan: RecurringPlanResponse

    var body: some View {
        Section {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("\(Money.text(plan.totalCents, plan.currency)) \(AutopayView.every(plan.frequency))")
                        .font(.headline)
                    Text(plan.methodLabel).font(.subheadline).foregroundStyle(.secondary)
                }
                Spacer()
                AutopayStatus(status: plan.status)
            }
            if plan.status == "active", let next = plan.nextChargeDate {
                LabeledContent("Next payment", value: AutopayView.day(next))
            }
            LabeledContent("Dues", value: Money.text(plan.duesCents, plan.currency))
            LabeledContent("Fee", value: Money.text(plan.feeCents, plan.currency))
        } footer: {
            switch plan.status {
            case "paused":
                Text("Nothing is charged while autopay is paused. Payment dates that pass while it is paused are not charged later.")
            case "needs_approval":
                Text(plan.statusReason == "fee_changed"
                     ? "Your HOA changed the fee for this payment method, so the last payment wasn't made. Approve the new total to turn autopay back on."
                     : "This payment method can no longer be used, so the last payment wasn't made. Choose another method to turn autopay back on.")
                    .foregroundStyle(.orange)
            default:
                EmptyView()
            }
        }
    }
}

/// The plan's status as a small colored label.
struct AutopayStatus: View {
    let status: String

    var body: some View {
        let (text, color): (String, Color) = switch status {
        case "active": ("On", .green)
        case "paused": ("Paused", .secondary)
        case "needs_approval": ("Needs approval", .orange)
        default: ("Off", .secondary)
        }
        Text(text)
            .font(.caption.weight(.semibold))
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(color.opacity(0.15), in: Capsule())
            .foregroundStyle(color)
    }
}

/// Signing in again before a change that can lead to charges (ED-59): the server accepts it only
/// within 10 minutes of a sign-in.
private struct ReauthenticationView: View {
    @Environment(AppModel.self) private var model
    let finished: (Bool) -> Void

    @State private var password = ""
    @State private var token = ""
    @State private var working = false
    @State private var message: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("For your security, confirm it's you before changing how you pay.")
                }
                switch model.reauthentication {
                case let .firebase(usesPassword, usesGoogle, email):
                    if usesPassword {
                        Section {
                            if let email { LabeledContent("Email", value: email) }
                            SecureField("Password", text: $password)
                                .textContentType(.password)
                                .onSubmit { Task { await confirmPassword() } }
                            Button("Confirm") { Task { await confirmPassword() } }
                                .disabled(password.isEmpty)
                        }
                    }
                    if usesGoogle {
                        Section {
                            Button("Continue with Google") { Task { await confirmGoogle() } }
                        }
                    }
                    if !usesPassword && !usesGoogle {
                        Section {
                            Button("Sign out") { model.signOut(message: "Sign in again to continue.") }
                        } footer: {
                            Text("Sign out, then sign in again to continue.")
                        }
                    }
                case .developmentToken:
                    #if DEBUG
                    Section {
                        TextField("Development token", text: $token, axis: .vertical)
                            .font(.caption.monospaced())
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                        Button("Use this token") {
                            model.replaceDevelopmentToken(token)
                            finished(true)
                        }
                        .disabled(token.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    } footer: {
                        Text(verbatim: "Development sign-in: paste a new token from `seebrook dev-token`.")
                    }
                    #else
                    EmptyView()
                    #endif
                }
                if let message, !message.isEmpty {
                    Section { Text(message).foregroundStyle(.red) }
                }
            }
            .disabled(working)
            .navigationTitle("Sign in again")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { finished(false) } }
            }
        }
    }

    private func confirmPassword() async {
        guard !password.isEmpty else { return }
        working = true
        defer { working = false }
        message = await model.reauthenticate(password: password)
        if message == nil { finished(true) }
    }

    private func confirmGoogle() async {
        working = true
        defer { working = false }
        message = await model.reauthenticateWithGoogle()
        if message == nil { finished(true) }
    }
}

/// A bank account setup still being verified, by property, so it can be finished later. Only
/// Stripe's setup ID is kept: no account details.
enum PendingSetups {
    private static func key(_ propertyId: String) -> String { "pendingSetup.\(propertyId)" }

    static func load(propertyId: String) -> String? { UserDefaults.standard.string(forKey: key(propertyId)) }
    static func save(_ setupId: String, propertyId: String) { UserDefaults.standard.set(setupId, forKey: key(propertyId)) }
    static func remove(propertyId: String) { UserDefaults.standard.removeObject(forKey: key(propertyId)) }
}
