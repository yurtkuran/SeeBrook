import SwiftUI

struct RootView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        switch model.phase {
        case .starting, .loading:
            ProgressView()
        case .signedOut(let message):
            SignInView(message: message)
        case .signedIn(let account):
            HomeView(account: account)
        }
    }
}
