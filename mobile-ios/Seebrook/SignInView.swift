import SwiftUI

struct SignInView: View {
    let message: String?

    var body: some View {
        #if DEBUG
        DevSignInView(message: message)
        #else
        // Firebase sign-in (email, Google, Apple) arrives with the sign-in screens.
        ContentUnavailableView("Sign-in is coming soon", systemImage: "person.crop.circle", description: Text(message ?? ""))
        #endif
    }
}

#if DEBUG
/// Development builds only (ED-48): sign in with a token from `seebrook dev-token`, against a
/// server on your Mac. A server in production refuses these tokens.
private struct DevSignInView: View {
    @Environment(AppModel.self) private var model
    let message: String?
    @State private var server = ""
    @State private var token = ""

    var body: some View {
        NavigationStack {
            Form {
                if let message {
                    Section { Text(message).foregroundStyle(.red) }
                }
                Section {
                    TextField("http://your-mac.local:8088", text: $server)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .onSubmit { model.useServer(server) }
                    Button("Check server") {
                        model.useServer(server)
                        Task { await model.checkServer() }
                    }
                    if let status = model.serverStatus {
                        Text(status).font(.footnote).foregroundStyle(.secondary)
                    }
                } header: {
                    Text("Server")
                } footer: {
                    Text(verbatim: "On your iPhone, use your Mac's address on the same Wi-Fi, for example http://your-mac.local:8088. The simulator can use http://localhost:8088.")
                }
                Section {
                    TextField("Paste the token", text: $token, axis: .vertical)
                        .lineLimit(3...6)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .font(.footnote.monospaced())
                    Button("Sign in") {
                        model.useServer(server)
                        Task { await model.signIn(token: token) }
                    }
                    .disabled(token.isEmpty)
                } header: {
                    Text("Development token")
                } footer: {
                    Text(verbatim: "On your Mac: seebrook dev-token <email> --days 7")
                }
            }
            .navigationTitle("Seebrook Dev")
            .onAppear { if server.isEmpty { server = model.serverURL } }
        }
    }
}
#endif
