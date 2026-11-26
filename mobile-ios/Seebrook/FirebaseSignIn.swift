import FirebaseAuth
import FirebaseCore
import GoogleSignIn
import UIKit

/// Firebase Authentication (ED-28, ED-50): email and password, and Google. Firebase keeps the
/// signed-in user in the Keychain and refreshes its ID token; the backend verifies every token.
@MainActor
enum FirebaseSignIn {
    struct NotAvailable: Error {}

    /// Configures Firebase when this build has a `GoogleService-Info.plist` (ED-50).
    static func configure() {
        guard FirebaseApp.app() == nil,
              Bundle.main.path(forResource: "GoogleService-Info", ofType: "plist") != nil else { return }
        FirebaseApp.configure()
    }

    static var isConfigured: Bool { FirebaseApp.app() != nil }

    /// Google sign-in needs the OAuth client in the Firebase file (Google enabled before downloading it).
    static var googleAvailable: Bool { FirebaseApp.app()?.options.clientID != nil }

    static var currentUser: User? { isConfigured ? Auth.auth().currentUser : nil }

    static func signIn(email: String, password: String) async throws -> User {
        try await Auth.auth().signIn(withEmail: email, password: password).user
    }

    /// Creates the account and sends the verification email (needed to accept an invitation, ED-29).
    static func createAccount(email: String, password: String) async throws -> User {
        let user = try await Auth.auth().createUser(withEmail: email, password: password).user
        try await user.sendEmailVerification()
        return user
    }

    static func signInWithGoogle() async throws -> User {
        guard let clientID = FirebaseApp.app()?.options.clientID, let presenter = topViewController() else { throw NotAvailable() }
        GIDSignIn.sharedInstance.configuration = GIDConfiguration(clientID: clientID)
        let result = try await GIDSignIn.sharedInstance.signIn(withPresenting: presenter)
        guard let idToken = result.user.idToken?.tokenString else { throw NotAvailable() }
        let credential = GoogleAuthProvider.credential(withIDToken: idToken, accessToken: result.user.accessToken.tokenString)
        return try await Auth.auth().signIn(with: credential).user
    }

    /// How the current user signs in, for asking them to sign in again: password, Google, or both.
    static var signInMethods: (password: Bool, google: Bool) {
        let providers = Set(currentUser?.providerData.map(\.providerID) ?? [])
        return (providers.contains(EmailAuthProviderID), providers.contains(GoogleAuthProviderID))
    }

    /// Confirms the signed-in owner again before a change that can lead to charges (ED-59): a fresh
    /// sign-in renews the token's `auth_time`, which the server checks.
    static func reauthenticate(password: String) async throws {
        guard let user = currentUser, let email = user.email else { throw NotAvailable() }
        try await user.reauthenticate(with: EmailAuthProvider.credential(withEmail: email, password: password))
        _ = try await user.getIDToken(forcingRefresh: true)
    }

    static func reauthenticateWithGoogle() async throws {
        guard let user = currentUser, let clientID = FirebaseApp.app()?.options.clientID,
              let presenter = topViewController() else { throw NotAvailable() }
        GIDSignIn.sharedInstance.configuration = GIDConfiguration(clientID: clientID)
        let result = try await GIDSignIn.sharedInstance.signIn(withPresenting: presenter, hint: user.email)
        guard let idToken = result.user.idToken?.tokenString else { throw NotAvailable() }
        let credential = GoogleAuthProvider.credential(withIDToken: idToken, accessToken: result.user.accessToken.tokenString)
        try await user.reauthenticate(with: credential)
        _ = try await user.getIDToken(forcingRefresh: true)
    }

    static func sendPasswordReset(email: String) async throws {
        try await Auth.auth().sendPasswordReset(withEmail: email)
    }

    /// The current ID token; Firebase refreshes it when it is about to expire.
    static func idToken(forceRefresh: Bool = false) async throws -> String {
        guard let user = currentUser else { throw NotAvailable() }
        return try await user.getIDToken(forcingRefresh: forceRefresh)
    }

    /// Whether the email is verified now, asking Firebase rather than trusting the cached user.
    static func refreshEmailVerified() async throws -> Bool {
        guard let user = currentUser else { return false }
        try await user.reload()
        if user.isEmailVerified { _ = try await user.getIDToken(forcingRefresh: true) }
        return user.isEmailVerified
    }

    static func resendVerification() async throws {
        try await currentUser?.sendEmailVerification()
    }

    static func signOut() {
        guard isConfigured else { return }
        try? Auth.auth().signOut()
        GIDSignIn.sharedInstance.signOut()
    }

    /// Whether [error] is the owner closing the Google sheet, which needs no message.
    static func isCancellation(_ error: Error) -> Bool {
        (error as NSError).domain == kGIDSignInErrorDomain && (error as NSError).code == GIDSignInError.canceled.rawValue
    }

    static func message(for error: Error) -> String {
        guard let code = AuthErrorCode(rawValue: (error as NSError).code), (error as NSError).domain == AuthErrorDomain else {
            return "Sign-in didn't work. Please try again."
        }
        switch code {
        case .invalidCredential, .wrongPassword, .userNotFound, .invalidEmail:
            return "The email or password is incorrect."
        case .emailAlreadyInUse:
            return "An account with this email already exists. Sign in instead, or reset your password."
        case .weakPassword:
            return "Choose a longer password (at least 8 characters)."
        case .networkError:
            return "No connection. Check your internet and try again."
        case .tooManyRequests:
            return "Too many attempts. Please wait a few minutes and try again."
        case .userDisabled:
            return "This sign-in has been disabled. Please contact your HOA."
        case .userMismatch:
            return "That's a different account. Sign in with the account you use for Seebrook."
        case .accountExistsWithDifferentCredential:
            return "This email already signs in another way. Use the method you used before."
        default:
            return "Sign-in didn't work. Please try again."
        }
    }

    private static func topViewController() -> UIViewController? {
        let root = UIApplication.shared.connectedScenes
            .compactMap { ($0 as? UIWindowScene)?.keyWindow?.rootViewController }
            .first
        var top = root
        while let presented = top?.presentedViewController { top = presented }
        return top
    }
}
