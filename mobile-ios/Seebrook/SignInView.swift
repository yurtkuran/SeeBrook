import SwiftUI

/// Sign-in with Firebase (ED-50): email and password, or Google. Debug builds also offer the
/// developer options: the server address and development-token sign-in (ED-48).
/// The layout follows the "Seebrook Mobile Login" design in Stitch.
struct SignInView: View {
    @Environment(AppModel.self) private var model
    let message: String?

    /// Seebrook is by invitation only: there is no open sign-up, only setting up an account
    /// with an invitation (ED-50).
    private enum Mode { case signIn, setUp }

    @State private var mode = Mode.signIn
    @State private var invitation = ""
    @State private var email = ""
    @State private var password = ""
    @State private var showPassword = false
    @State private var notice: String?
    @State private var error: String?
    @State private var working = false
    @State private var showingDeveloper = false

    var body: some View {
        GeometryReader { geometry in
        ScrollView {
            VStack(spacing: 0) {
                header
                    .padding(.top, 56)
                    .padding(.bottom, 40)
                card
                if mode == .signIn, FirebaseSignIn.isConfigured {
                    Button("Forgot password?") { sendReset() }
                        .font(.footnote.weight(.medium))
                        .foregroundStyle(Style.secondaryText)
                        .padding(.top, 16)
                }
                Spacer(minLength: 32)
                footer
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 12)
            .frame(maxWidth: 420)
            .frame(maxWidth: .infinity, minHeight: geometry.size.height, alignment: .top)
        }
        .scrollDismissesKeyboard(.interactively)
        .scrollBounceBehavior(.basedOnSize)
        }
        .background { Background() }
        .disabled(working)
        .environment(\.colorScheme, .light)
        #if DEBUG
        .overlay(alignment: .topTrailing) {
            Button("Developer") { showingDeveloper = true }
                .font(.footnote)
                .padding(.horizontal, 20)
        }
        .sheet(isPresented: $showingDeveloper) {
            DeveloperSignInView()
        }
        #endif
    }

    // MARK: Parts

    private var header: some View {
        VStack(spacing: 12) {
            Image("Wordmark")
                .resizable()
                .scaledToFit()
                .frame(height: 48)
                .accessibilityLabel("Seebrook")
            Text(mode == .signIn ? "See your community differently." : "Set up your account")
                .font(.subheadline.weight(.medium))
                .foregroundStyle(Style.secondaryText)
        }
    }

    private var card: some View {
        VStack(alignment: .leading, spacing: 16) {
            if let shown = error ?? message {
                Text(shown)
                    .font(.footnote)
                    .foregroundStyle(.red)
            }
            if let notice {
                Text(notice)
                    .font(.footnote)
                    .foregroundStyle(Style.secondaryText)
            }
            if !FirebaseSignIn.isConfigured {
                Text("Sign-in isn't set up in this build.")
                Text(verbatim: "Add Config/Firebase/<configuration>/GoogleService-Info.plist and rebuild.")
                    .font(.footnote)
                    .foregroundStyle(Style.secondaryText)
            } else if mode == .signIn {
                signInFields
            } else {
                setUpFields
            }
        }
        .padding(24)
        .background(.white.opacity(0.95), in: .rect(cornerRadius: Style.cardRadius))
        .overlay(RoundedRectangle(cornerRadius: Style.cardRadius).stroke(.white.opacity(0.8)))
        .shadow(color: Style.brand.opacity(0.08), radius: 20, y: 12)
    }

    @ViewBuilder private var signInFields: some View {
        emailField
        passwordField(label: "Password", newPassword: false)
        Button("Sign In") {
            run { await model.signIn(email: email, password: password) }
        }
        .buttonStyle(PrimaryButtonStyle())
        .disabled(email.isEmpty || password.isEmpty)
        .padding(.top, 8)
        if FirebaseSignIn.googleAvailable {
            Button {
                run { await model.signInWithGoogle() }
            } label: {
                Label("Sign in with Google", systemImage: "g.circle.fill")
            }
            .buttonStyle(SecondaryButtonStyle())
        }
    }

    @ViewBuilder private var setUpFields: some View {
        field("Invitation code or link") {
            TextField("Paste the code or link", text: $invitation, axis: .vertical)
                .lineLimit(1...3)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .font(.callout.monospaced())
        }
        Text("From the email your HOA sent you.")
            .font(.caption)
            .foregroundStyle(Style.secondaryText)
            .padding(.top, -10)
        emailField
        passwordField(label: "Choose a password", newPassword: true)
        Text("Use the email address the invitation was sent to. We'll email you a link to verify it.")
            .font(.caption)
            .foregroundStyle(Style.secondaryText)
            .padding(.top, -10)
        Button("Set Up Account") {
            run { await model.setUpAccount(invitation: invitation, email: email, password: password) }
        }
        .buttonStyle(PrimaryButtonStyle())
        .disabled(invitation.isEmpty || email.isEmpty || password.isEmpty)
        .padding(.top, 8)
        if FirebaseSignIn.googleAvailable {
            Button {
                run { await model.setUpWithGoogle(invitation: invitation) }
            } label: {
                Label("Use Google instead", systemImage: "g.circle.fill")
            }
            .buttonStyle(SecondaryButtonStyle())
            .disabled(invitation.isEmpty)
        }
    }

    @ViewBuilder private var footer: some View {
        if mode == .signIn {
            HStack(spacing: 4) {
                Text("Have an invitation?")
                    .foregroundStyle(Style.secondaryText)
                Button("Set up your account") { switchMode(.setUp) }
                    .fontWeight(.semibold)
                    .foregroundStyle(Style.brand)
            }
            .font(.footnote)
            .frame(maxWidth: .infinity)
        } else {
            HStack(spacing: 4) {
                Text("Already set up?")
                    .foregroundStyle(Style.secondaryText)
                Button("Sign in") { switchMode(.signIn) }
                    .fontWeight(.semibold)
                    .foregroundStyle(Style.brand)
            }
            .font(.footnote)
            .frame(maxWidth: .infinity)
        }
    }

    private var emailField: some View {
        field("Email") {
            TextField(text: $email, prompt: Text(verbatim: "you@example.com")) { Text("Email") }
                .keyboardType(.emailAddress)
                .textContentType(.username)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
        }
    }

    private func passwordField(label: String, newPassword: Bool) -> some View {
        field(label) {
            HStack {
                Group {
                    if showPassword {
                        TextField("", text: $password)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                    } else {
                        SecureField("", text: $password)
                    }
                }
                .textContentType(newPassword ? .newPassword : .password)
                Button {
                    showPassword.toggle()
                } label: {
                    Image(systemName: showPassword ? "eye.slash" : "eye")
                        .foregroundStyle(Style.secondaryText)
                }
                .accessibilityLabel(showPassword ? "Hide password" : "Show password")
            }
        }
    }

    private func field(_ label: String, @ViewBuilder input: () -> some View) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label)
                .font(.caption.weight(.semibold))
                .foregroundStyle(Style.labelText)
            input()
                .font(.callout)
                .padding(.horizontal, 14)
                .frame(minHeight: 48)
                .background(.white, in: .rect(cornerRadius: Style.controlRadius))
                .overlay(RoundedRectangle(cornerRadius: Style.controlRadius).stroke(Style.border))
        }
    }

    // MARK: Actions

    private func sendReset() {
        Task {
            error = nil
            notice = await model.sendPasswordReset(email: email)
        }
    }

    private func switchMode(_ new: Mode) {
        mode = new
        error = nil
        notice = nil
        password = ""
        showPassword = false
    }

    private func run(_ action: @escaping () async -> String?) {
        Task {
            working = true
            error = nil
            notice = nil
            error = await action()
            working = false
        }
    }
}

// MARK: Style

private enum Style {
    static let brand = Color("Brand")
    static let secondaryText = Color(red: 0.39, green: 0.45, blue: 0.55)  // slate-500
    static let labelText = Color(red: 0.20, green: 0.25, blue: 0.33)      // slate-700
    static let border = Color(red: 0.89, green: 0.91, blue: 0.94)         // slate-200
    static let cardRadius: CGFloat = 16
    static let controlRadius: CGFloat = 12
}

/// The faded harbor photo, with a light wash so the form stays readable.
private struct Background: View {
    var body: some View {
        Image("LoginBackground")
            .resizable()
            .scaledToFill()
            .overlay(Color(red: 0.97, green: 0.98, blue: 0.99).opacity(0.5))
            .ignoresSafeArea()
            .accessibilityHidden(true)
    }
}

private struct PrimaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.callout.weight(.semibold))
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity, minHeight: 48)
            .background(Style.brand.opacity(isEnabled ? (configuration.isPressed ? 0.85 : 1) : 0.45),
                        in: .rect(cornerRadius: Style.controlRadius))
            .shadow(color: Style.brand.opacity(isEnabled ? 0.2 : 0), radius: 6, y: 3)
    }
}

private struct SecondaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.callout.weight(.medium))
            .foregroundStyle(Style.labelText.opacity(isEnabled ? 1 : 0.45))
            .frame(maxWidth: .infinity, minHeight: 48)
            .background(configuration.isPressed ? Color(white: 0.96) : .white,
                        in: .rect(cornerRadius: Style.controlRadius))
            .overlay(RoundedRectangle(cornerRadius: Style.controlRadius).stroke(Style.border))
    }
}

#if DEBUG
/// Developer options (debug builds only, ED-48): the server to use, and development-token
/// sign-in against a server in development auth mode. A production server refuses these tokens.
private struct DeveloperSignInView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var server = ""
    @State private var token = ""

    var body: some View {
        NavigationStack {
            Form {
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
                    Button("Sign in with token") {
                        model.useServer(server)
                        dismiss()
                        Task { await model.signIn(token: token) }
                    }
                    .disabled(token.isEmpty)
                } header: {
                    Text("Development token")
                } footer: {
                    Text(verbatim: "Only when the server runs in development auth mode. On your Mac: seebrook dev-token <email> --days 7")
                }
            }
            .navigationTitle("Developer")
            .toolbar {
                Button("Done") {
                    model.useServer(server)
                    dismiss()
                }
            }
            .onAppear { if server.isEmpty { server = model.serverURL } }
        }
    }
}
#endif
