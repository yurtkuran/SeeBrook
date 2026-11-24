import GoogleSignIn
import SwiftUI

@main
struct SeebrookApp: App {
    @State private var model: AppModel

    init() {
        FirebaseSignIn.configure()
        _model = State(initialValue: AppModel())
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .task { await model.start() }
                .onOpenURL { GIDSignIn.sharedInstance.handle($0) }
        }
    }
}
