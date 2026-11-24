import SwiftUI

struct RootView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        switch model.phase {
        case .starting, .loading:
            ProgressView()
        case .signedOut(let message):
            SignInView(message: message)
        case .verifyEmail(let email):
            VerifyEmailView(email: email)
        case .needsInvitation:
            InvitationView(firstProperty: true)
        case .signedIn(let account):
            MainView(account: account)
        }
    }
}
