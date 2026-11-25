import Foundation
import UIKit
import Observation
import SeebrookShared

/// The app's session state. It only calls the API and presents what comes back; every rule and
/// figure is the backend's (ED-17).
@MainActor
@Observable
final class AppModel {
    enum Phase {
        case starting
        case signedOut(message: String?)
        case loading
        /// Signed in with a password whose email is not verified yet.
        case verifyEmail(String)
        /// Signed in, but not linked to any property yet: the owner enters their invitation.
        case needsInvitation
        case signedIn(Account)
    }

    struct Account {
        let me: MeResponse
        let properties: [PropertyResponse]
    }

    private(set) var phase: Phase = .starting
    private(set) var serverStatus: String?
    private(set) var serverURL = ServerSettings.url
    private var api = SeebrookApi(baseUrl: ServerSettings.url)

    /// Where the ID token comes from: Firebase, or a development token (debug builds, ED-48).
    private enum Credential {
        case firebase
        case development(String)
    }

    private var credential: Credential?

    /// The property the app is showing, remembered between launches. Only a preference: the
    /// server decides on every request which properties this account may see.
    private(set) var selectedPropertyID = UserDefaults.standard.string(forKey: AppModel.selectedPropertyKey)
    private static let selectedPropertyKey = "selectedPropertyID"

    /// The invitation entered while setting up an account; accepted once signed in (and verified).
    private var pendingInvitation: String?
    /// What the invitation screen starts with when that automatic acceptance did not work.
    private(set) var invitationDraft: (input: String, message: String)?

    /// Resumes the previous session: Firebase keeps its signed-in user, development tokens are in the Keychain.
    func start() async {
        guard case .starting = phase else { return }
        #if DEBUG
        // Scripted runs (`simctl launch` with SIMCTL_CHILD_SEEBROOK_*): sign in without typing.
        let environment = ProcessInfo.processInfo.environment
        if let url = environment["SEEBROOK_SERVER_URL"] { useServer(url) }
        if let token = environment["SEEBROOK_DEV_TOKEN"] {
            await signIn(token: token)
            return
        }
        #endif
        if FirebaseSignIn.currentUser != nil {
            credential = .firebase
            await enter()
            return
        }
        #if DEBUG
        if let token = TokenStore.load() {
            await signIn(token: token)
            return
        }
        #endif
        phase = .signedOut(message: nil)
    }

    // MARK: Signing in. Each returns a message to show, or nil when it worked.

    func signIn(email: String, password: String) async -> String? {
        await firebase { try await FirebaseSignIn.signIn(email: email.trimmingCharacters(in: .whitespaces), password: password) }
    }

    func signInWithGoogle() async -> String? {
        await firebase { try await FirebaseSignIn.signInWithGoogle() }
    }

    /// Setting up an account is for invited owners (ED-50): the invitation comes first, then a
    /// password account (with a verification email) or Google, then the invitation is accepted.
    func setUpAccount(invitation: String, email: String, password: String) async -> String? {
        await withInvitation(invitation) {
            try await FirebaseSignIn.createAccount(email: email.trimmingCharacters(in: .whitespaces), password: password)
        }
    }

    func setUpWithGoogle(invitation: String) async -> String? {
        await withInvitation(invitation) { try await FirebaseSignIn.signInWithGoogle() }
    }

    private func withInvitation(_ invitation: String, _ signIn: () async throws -> Any) async -> String? {
        guard InvitationCode.shared.fromInput(input: invitation) != nil else { return Self.badInvitationCode }
        pendingInvitation = invitation
        let message = await firebase(signIn)
        if message != nil { pendingInvitation = nil }
        return message
    }

    func sendPasswordReset(email: String) async -> String {
        let email = email.trimmingCharacters(in: .whitespaces)
        guard !email.isEmpty else { return "Enter your email address first." }
        do {
            try await FirebaseSignIn.sendPasswordReset(email: email)
        } catch {
            // Same answer either way, so the screen never reveals whether an account exists.
        }
        return "If an account exists for \(email), an email with a reset link is on its way."
    }

    private func firebase(_ signIn: () async throws -> Any) async -> String? {
        do {
            _ = try await signIn()
        } catch {
            return FirebaseSignIn.isCancellation(error) ? nil : FirebaseSignIn.message(for: error)
        }
        credential = .firebase
        await enter()
        return nil
    }

    /// Debug builds: a token from `seebrook dev-token` (ED-48).
    func signIn(token: String) async {
        let token = token.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !token.isEmpty else { return }
        credential = .development(token)
        await enter()
    }

    // MARK: Email verification (needed to accept an invitation, ED-29)

    /// Returns a message when the email still isn't verified.
    func checkEmailVerified() async -> String? {
        do {
            guard try await FirebaseSignIn.refreshEmailVerified() else {
                return "Your email isn't verified yet. Open the link in the email we sent, then try again."
            }
        } catch {
            return FirebaseSignIn.message(for: error)
        }
        await enter()
        return nil
    }

    func resendVerification() async -> String {
        do {
            try await FirebaseSignIn.resendVerification()
            return "We sent another verification email."
        } catch {
            return FirebaseSignIn.message(for: error)
        }
    }

    /// After signing in: verify the email if needed, then load the account or ask for the invitation.
    private func enter() async {
        if case .firebase = credential, let user = FirebaseSignIn.currentUser, !user.isEmailVerified {
            phase = .verifyEmail(user.email ?? "")
            return
        }
        phase = .loading
        do {
            let account = try await load()
            rememberDevelopmentToken()
            phase = .signedIn(account)
        } catch where Self.apiError(error)?.code == "not_registered" {
            rememberDevelopmentToken()
            if let invitation = pendingInvitation {
                pendingInvitation = nil
                if let message = await acceptInvitation(invitation) {
                    invitationDraft = (invitation, message)
                    phase = .needsInvitation
                }
            } else {
                phase = .needsInvitation
            }
        } catch {
            signOut(message: Self.message(for: error))
        }
    }

    private func rememberDevelopmentToken() {
        if case .development(let token) = credential { TokenStore.save(token) }
    }

    /// Accepts the invitation in [input] (the code or the whole link from the email). Returns a
    /// message to show when it can't, or nil once the property is linked.
    func acceptInvitation(_ input: String) async -> String? {
        guard let code = InvitationCode.shared.fromInput(input: input) else { return Self.badInvitationCode }
        do {
            try await authorize()
            let accepted = try await api.acceptInvitation(code: code)
            select(propertyID: accepted.propertyId)
            phase = .signedIn(try await load())
            return nil
        } catch where Self.apiError(error)?.kind == .unauthorized {
            signOut(message: Self.message(for: error))
            return nil
        } catch {
            return Self.message(for: error)
        }
    }

    func refresh() async {
        guard case .signedIn = phase else { return }
        do {
            phase = .signedIn(try await load())
        } catch let error as NSError where Self.apiError(error)?.kind == .unauthorized {
            signOut(message: Self.message(for: error))
        } catch {
            // Keep showing what was loaded; the next refresh tries again.
        }
    }

    // MARK: Balance and history (ED-42, ED-53)

    func financeSummary(propertyId: String) async throws -> AccountSummaryResponse {
        try await signedIn { try await $0.financeSummary(propertyId: propertyId) }
    }

    func transactions(propertyId: String, page: Int) async throws -> AccountTransactionsPageResponse {
        try await signedIn {
            try await $0.accountTransactions(propertyId: propertyId, page: Int32(page), pageSize: Int32(Self.transactionPageSize))
        }
    }

    static let transactionPageSize = 25

    // MARK: Paying (ED-43, ED-54)

    func paymentQuote(propertyId: String, amountCents: Int64) async throws -> PaymentQuoteResponse {
        try await signedIn { try await $0.paymentQuote(propertyId: propertyId, amountCents: amountCents) }
    }

    /// Starts a payment for exactly the approved option. The caller keeps [idempotencyKey] for that
    /// approval and sends it again on a retry, so a repeated tap never pays twice.
    func startPayment(propertyId: String, option: PaymentOptionResponse, amountCents: Int64, idempotencyKey: String) async throws -> PaymentResponse {
        let request = StartPaymentRequest(method: option.method, amountCents: amountCents, feeCents: option.feeCents,
                                          totalCents: option.totalCents, idempotencyKey: idempotencyKey)
        return try await signedIn { try await $0.startPayment(propertyId: propertyId, request: request) }
    }

    func payments(propertyId: String) async throws -> [PaymentResponse] {
        try await signedIn { try await $0.payments(propertyId: propertyId).payments }
    }

    // MARK: Documents (ED-39, ED-52)

    func documentCategories(hoaId: String) async throws -> [DocumentCategoryResponse] {
        try await signedIn { try await $0.documentCategories(hoaId: hoaId).categories }
    }

    func documents(hoaId: String, search: String, categoryId: String?, page: Int) async throws -> DocumentsPageResponse {
        try await signedIn {
            try await $0.documents(hoaId: hoaId, search: search, categoryId: categoryId,
                                   page: Int32(page), pageSize: Int32(Self.documentPageSize))
        }
    }

    static let documentPageSize = 20

    /// Fetches the PDF through a fresh short-lived link into a temporary folder and returns the
    /// file. The link is used once and not kept; the caller deletes the file when it is closed.
    func downloadDocument(hoaId: String, documentId: String) async throws -> URL {
        let link = try await signedIn { try await $0.documentDownloadLink(hoaId: hoaId, documentId: documentId) }
        guard let url = URL(string: link.url) else { throw DownloadFailed() }
        let (downloaded, response) = try await URLSession.shared.download(from: url)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw DownloadFailed() }
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let file = folder.appendingPathComponent(link.fileName)
        try FileManager.default.moveItem(at: downloaded, to: file)
        return file
    }

    struct DownloadFailed: Error {}

    /// The logo of the property's HOA (ED-55), or nil when it has none or it can't be loaded now.
    /// Kept on the device by version, so it is downloaded again only after the HOA changes it.
    func hoaLogo(for property: PropertyResponse) async -> UIImage? {
        guard let version = property.hoaLogoVersion else { return nil }
        let folder = URL.cachesDirectory.appendingPathComponent("hoa-logos", isDirectory: true)
        let file = folder.appendingPathComponent("\(property.hoaId)-\(version).png")
        if let cached = UIImage(contentsOfFile: file.path) { return cached }
        do {
            let link = try await signedIn { try await $0.hoaLogo(propertyId: property.id) }
            guard let url = URL(string: link.url) else { return nil }
            let (data, response) = try await URLSession.shared.data(from: url)
            guard (response as? HTTPURLResponse)?.statusCode == 200, let image = UIImage(data: data) else { return nil }
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            // Earlier versions of this HOA's logo are no longer needed.
            for old in (try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil)) ?? []
            where old.lastPathComponent.hasPrefix("\(property.hoaId)-") {
                try? FileManager.default.removeItem(at: old)
            }
            try data.write(to: file, options: .atomic)
            return image
        } catch {
            return nil
        }
    }

    /// Runs a signed-in API call. An expired sign-in signs out; other errors go to the caller.
    private func signedIn<T>(_ call: (SeebrookApi) async throws -> T) async throws -> T {
        do {
            try await authorize()
            return try await call(api)
        } catch where Self.apiError(error)?.kind == .unauthorized {
            signOut(message: Self.message(for: error))
            throw error
        }
    }

    /// The selected property, or the first one when none is selected or it is no longer linked.
    func selectedProperty(in account: Account) -> PropertyResponse? {
        account.properties.first { $0.id == selectedPropertyID } ?? account.properties.first
    }

    func select(propertyID: String?) {
        selectedPropertyID = propertyID
        UserDefaults.standard.set(propertyID, forKey: Self.selectedPropertyKey)
    }

    /// The invitation screen takes what the automatic acceptance left behind, once.
    func takeInvitationDraft() -> (input: String, message: String)? {
        defer { invitationDraft = nil }
        return invitationDraft
    }

    func signOut(message: String? = nil) {
        pendingInvitation = nil
        invitationDraft = nil
        FirebaseSignIn.signOut()
        TokenStore.clear()
        credential = nil
        api.idToken = nil
        select(propertyID: nil)
        phase = .signedOut(message: message)
    }

    #if DEBUG
    /// Development builds can point at any server, such as the Mac running `scripts/dev.sh`.
    func useServer(_ url: String) {
        let url = url.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !url.isEmpty, url != serverURL else { return }
        ServerSettings.url = url
        serverURL = url
        api = SeebrookApi(baseUrl: url)
        serverStatus = nil
    }
    #endif

    func checkServer() async {
        serverStatus = "Checking…"
        do {
            let health = try await api.health()
            serverStatus = health.status == HealthStatus.up ? "The server is up." : "The server is running but not ready."
        } catch {
            serverStatus = Self.message(for: error)
        }
    }

    /// Puts a current ID token on the API client; Firebase refreshes it when it is about to expire.
    private func authorize() async throws {
        switch credential {
        case .firebase:
            api.idToken = try await FirebaseSignIn.idToken()
        case .development(let token):
            api.idToken = token
        case nil:
            api.idToken = nil
        }
    }

    private func load() async throws -> Account {
        try await authorize()
        let me = try await api.me()
        let properties = try await api.myProperties()
        return Account(me: me, properties: properties.properties)
    }

    /// The API's error code (`quote_changed`, …), for screens that handle one specially.
    static func apiCode(_ error: Error) -> String? { apiError(error)?.code }

    private static func apiError(_ error: Error) -> ApiException? {
        (error as NSError).kotlinException as? ApiException
    }

    private static let badInvitationCode = "That doesn't look like an invitation code. Copy the code or the link from your invitation email."

    static func message(for error: Error) -> String {
        guard let e = apiError(error) else {
            if error is FirebaseSignIn.NotAvailable { return "Your sign-in has expired. Please sign in again." }
            if error is DownloadFailed || error is URLError { return "The document couldn't be downloaded. Please try again." }
            return "Something went wrong. Please try again."
        }
        switch (e.kind, e.code) {
        case (.network, _):
            return "Can't reach the Seebrook server. Check your connection and try again."
        case (.unauthorized, _):
            return "Your sign-in has expired. Please sign in again."
        case (.forbidden, "not_registered"):
            return "This account isn't linked to a property yet. Accept your invitation first."
        case (_, "invitation_not_found"):
            return "We couldn't find that invitation. Check the code, or ask your HOA to send a new invitation."
        case (_, "invitation_expired"):
            return "This invitation has expired. Ask your HOA to send a new one."
        case (_, "invitation_no_longer_valid"):
            return "This invitation was already used or replaced by a newer one. Use the latest invitation email, or ask your HOA for a new one."
        case (_, "invitation_email_mismatch"):
            return "This invitation was sent to a different email address. Sign in with the address the invitation was sent to."
        case (_, "email_not_verified"):
            return "Please verify your email address first, then try again."
        case (_, "invalid_amount"):
            return "That amount can't be paid online. Check the amount and try again."
        case (_, "payments_unavailable"):
            return "Online payments are paused right now. Nothing was charged."
        case (_, "provider_unavailable"):
            return "The payment service isn't answering. Nothing was charged. Please try again in a few minutes."
        case (_, "provider_rejected"):
            return "The payment service couldn't start this payment. Nothing was charged."
        case (_, "method_not_accepted"):
            return "Your HOA doesn't accept that payment method any more. Choose another."
        case (_, "idempotency_conflict"):
            return "This payment was already started with different details. Please start again."
        case (_, "finance_unavailable"):
            return "Your HOA hasn't set up the balance for this property yet."
        case (.notFound, _):
            return "This is no longer available. Pull down to refresh."
        case (_, "documents_unavailable"):
            return "Documents can't be opened right now. Please try again later."
        case (.forbidden, "account_disabled"):
            return "This account has been disabled. Please contact your HOA."
        default:
            return "Something went wrong (\(e.code ?? "HTTP \(e.status)")). Please try again."
        }
    }
}

private extension NSError {
    /// The Kotlin exception behind an error thrown by the shared module.
    var kotlinException: Any? { userInfo["KotlinException"] }
}
