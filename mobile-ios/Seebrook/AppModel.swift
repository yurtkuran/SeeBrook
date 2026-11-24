import Foundation
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

    /// Signs back in with the stored token, if there is one.
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
        if let token = TokenStore.load() {
            await signIn(token: token)
        } else {
            phase = .signedOut(message: nil)
        }
    }

    func signIn(token: String) async {
        let token = token.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !token.isEmpty else { return }
        api.idToken = token
        phase = .loading
        do {
            let account = try await load()
            TokenStore.save(token)
            phase = .signedIn(account)
        } catch where Self.apiError(error)?.code == "not_registered" {
            TokenStore.save(token)
            phase = .needsInvitation
        } catch {
            signOut(message: Self.message(for: error))
        }
    }

    /// Accepts the invitation in [input] (the code or the whole link from the email). Returns a
    /// message to show when it can't, or nil once the property is linked.
    func acceptInvitation(_ input: String) async -> String? {
        guard let code = InvitationCode.shared.fromInput(input: input) else {
            return "That doesn't look like an invitation code. Copy the code or the link from your invitation email."
        }
        do {
            _ = try await api.acceptInvitation(code: code)
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

    func signOut(message: String? = nil) {
        TokenStore.clear()
        api.idToken = nil
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

    private func load() async throws -> Account {
        let me = try await api.me()
        let properties = try await api.myProperties()
        return Account(me: me, properties: properties.properties)
    }

    private static func apiError(_ error: Error) -> ApiException? {
        (error as NSError).kotlinException as? ApiException
    }

    static func message(for error: Error) -> String {
        guard let e = apiError(error) else { return "Something went wrong. Please try again." }
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
