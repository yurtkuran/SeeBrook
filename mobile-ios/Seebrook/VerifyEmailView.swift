import SwiftUI

/// A new email-and-password account must verify its email before it can accept an invitation (ED-29).
struct VerifyEmailView: View {
    @Environment(AppModel.self) private var model
    let email: String
    @State private var message: String?
    @State private var working = false

    var body: some View {
        NavigationStack {
            CardScreen {
                CardSection {
                    CardRow { Text("We sent a verification link to \(email). Open it, then come back and tap Continue.") }
                } footer: {
                    Text("Can't find it? Check your spam folder.")
                }
                Button("Continue") {
                    Task {
                        working = true
                        message = await model.checkEmailVerified()
                        working = false
                    }
                }
                .buttonStyle(PrimaryButtonStyle())
                Button("Send the email again") {
                    Task { message = await model.resendVerification() }
                }
                .frame(maxWidth: .infinity)
                if let message {
                    CardSection { CardRow { Text(message).foregroundStyle(.secondary) } }
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
