import SwiftUI

/// A new email-and-password account must verify its email before it can accept an invitation (ED-29).
struct VerifyEmailView: View {
    @Environment(AppModel.self) private var model
    let email: String
    @State private var message: String?
    @State private var working = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("We sent a verification link to \(email). Open it, then come back and tap Continue.")
                    Button("Continue") {
                        Task {
                            working = true
                            message = await model.checkEmailVerified()
                            working = false
                        }
                    }
                    Button("Send the email again") {
                        Task { message = await model.resendVerification() }
                    }
                } footer: {
                    Text("Can't find it? Check your spam folder.")
                }
                if let message {
                    Section { Text(message).foregroundStyle(.secondary) }
                }
            }
            .disabled(working)
            .navigationTitle("Verify your email")
            .toolbar {
                Button("Sign out") { model.signOut() }
            }
        }
    }
}
