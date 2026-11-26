import SwiftUI

/// Accepting an invitation: the first one links the account to its property; later ones add
/// another property. The server decides whether the invitation is valid (ED-17).
struct InvitationView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    /// True right after the first sign-in, when the account has no property yet.
    let firstProperty: Bool
    @State private var input = ""
    @State private var message: String?
    @State private var working = false

    var body: some View {
        NavigationStack {
            CardScreen {
                CardSection(header: firstProperty ? "Link your property" : "Add a property") {
                    CardRow {
                        TextField("Invitation code or link", text: $input, axis: .vertical)
                            .lineLimit(2...4)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .font(.body.monospaced())
                    }
                } footer: {
                    Text("Your HOA emailed you an invitation. Copy the invitation code, or the whole link, from that email and paste it here. Use the same email address you signed in with.")
                }
                Button {
                    Task { await accept() }
                } label: {
                    if working { ProgressView().tint(.white) } else { Text("Accept invitation") }
                }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || working)
                if let message {
                    CardSection { CardRow { Text(message).foregroundStyle(.red) } }
                }
            }
            .navigationTitle("Invitation")
            .onAppear {
                if let draft = model.takeInvitationDraft() {
                    input = draft.input
                    message = draft.message
                }
            }
            .toolbar {
                if firstProperty {
                    Button("Sign out") { model.signOut() }
                } else {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }

    private func accept() async {
        working = true
        message = await model.acceptInvitation(input)
        working = false
        if message == nil && !firstProperty { dismiss() }
    }
}
