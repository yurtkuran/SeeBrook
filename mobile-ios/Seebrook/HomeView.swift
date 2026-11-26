import SwiftUI
import SeebrookShared

/// The signed-in app: tabs as in the Stitch "Home Dashboard" design (ED-51).
struct MainView: View {
    let account: AppModel.Account
    @State private var tab = MainView.startTab

    var body: some View {
        TabView(selection: $tab) {
            HomeView(account: account, showPayments: { tab = "payments" })
                .tabItem { Label("Home", systemImage: "house") }
                .tag("home")
            PaymentsView(account: account)
                .tabItem { Label("Payments", systemImage: "creditcard") }
                .tag("payments")
            DocumentsView(account: account)
                .tabItem { Label("Documents", systemImage: "folder") }
                .tag("documents")
            AccountView(account: account)
                .tabItem { Label("Account", systemImage: "person.crop.circle") }
                .tag("account")
        }
    }

    /// Home, unless a scripted run asked for another tab (debug builds, SIMCTL_CHILD_SEEBROOK_TAB).
    private static var startTab: String { ScriptedLaunch.take("SEEBROOK_TAB") ?? "home" }
}

/// Debug builds: scripted simulator runs (`SIMCTL_CHILD_SEEBROOK_*`, ED-48) can pick the first
/// tab or open a sheet. Each setting is used once, for the first session after launch, so signing
/// out and in again starts on Home like a normal launch. Release builds ignore them.
@MainActor
enum ScriptedLaunch {
    #if DEBUG
    private static var used: Set<String> = []
    #endif

    static func take(_ name: String) -> String? {
        #if DEBUG
        guard used.insert(name).inserted else { return nil }
        return ProcessInfo.processInfo.environment[name]
        #else
        return nil
        #endif
    }
}

/// Home, following the owner's "Home - Mobile" design (2026-11-25): the selected property (with
/// a menu to switch property or add one), its balance and the HOA's community photo. Tapping the
/// balance opens Payments.
struct HomeView: View {
    @Environment(AppModel.self) private var model
    let account: AppModel.Account
    let showPayments: () -> Void
    @State private var addingProperty = false
    @State private var summary: AccountSummaryResponse?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 20) {
                    if let property = model.selectedProperty(in: account) {
                        PropertyCard(property: property, menu: propertyMenu)
                        if let summary {
                            Button(action: showPayments) {
                                BalanceCard(property: property, summary: summary, compact: true)
                                    .contentShape(.rect(cornerRadius: Theme.radius))
                            }
                            .buttonStyle(.plain)
                            .accessibilityHint("Opens Payments")
                        }
                        HoaPhoto(property: property)
                    } else {
                        noProperties
                    }
                }
                .padding(Theme.padding)
                // Same distance from the top as the Payments card.
                .padding(.top, 15)
            }
            .background(Theme.screen)
            .refreshable {
                await model.refresh()
                await loadSummary()
            }
            .task(id: model.selectedProperty(in: account)?.id) { await loadSummary() }
            // The property card names the unit and HOA, so the screen has no title bar (design).
            .toolbar(.hidden, for: .navigationBar)
            .sheet(isPresented: $addingProperty) {
                InvitationView(firstProperty: false)
            }
        }
    }

    /// The balance is a summary here; when it can't be loaded, Home simply leaves it out and
    /// Payments shows why.
    private func loadSummary() async {
        guard let propertyId = model.selectedProperty(in: account)?.id else {
            summary = nil
            return
        }
        summary = try? await model.financeSummary(propertyId: propertyId)
    }

    private var noProperties: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("No properties are linked to this account.")
            Text("If you own a home in an HOA that uses Seebrook, enter the invitation your HOA sent you.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
            Button("Add a property") { addingProperty = true }
                .buttonStyle(.borderedProminent)
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .card()
    }

    @ViewBuilder private var propertyMenu: some View {
        if account.properties.count > 1 {
            Picker("Property", selection: Binding(
                get: { model.selectedProperty(in: account)?.id },
                set: { model.select(propertyID: $0) }
            )) {
                ForEach(account.properties, id: \.id) { property in
                    Text("\(property.unitLabel) · \(property.hoaName)").tag(Optional(property.id))
                }
            }
            .pickerStyle(.inline)
        }
        Button("Add a property", systemImage: "plus") { addingProperty = true }
    }
}

/// The HOA's community photo (ED-57), under the balance as in the design. Nothing shows when the
/// HOA has no photo or it can't be loaded; a plain tile holds its place while it loads.
private struct HoaPhoto: View {
    @Environment(AppModel.self) private var model
    let property: PropertyResponse
    @State private var image: UIImage?
    /// The photo that couldn't be loaded; another property or a new version tries again.
    @State private var failedKey: String?

    private var key: String { "\(property.id) \(property.hoaPhotoVersion ?? "")" }

    var body: some View {
        if property.hoaPhotoVersion != nil && failedKey != key {
            Color(.secondarySystemFill)
                .frame(height: 220)
                .overlay {
                    if let image {
                        Image(uiImage: image).resizable().scaledToFill()
                    }
                }
                .clipShape(.rect(cornerRadius: Theme.radius))
                .accessibilityElement()
                .accessibilityLabel("\(property.hoaName) community photo")
                .accessibilityAddTraits(.isImage)
                .task(id: key) {
                    image = nil
                    let loaded = await model.hoaPhoto(for: property)
                    if Task.isCancelled { return }
                    image = loaded
                    if loaded == nil { failedKey = key }
                }
        }
    }
}

/// The selected property: the HOA logo with the unit and HOA, then the address and the property menu.
private struct PropertyCard<MenuItems: View>: View {
    let property: PropertyResponse
    let menu: MenuItems

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            PropertyHeader(property: property)
            HStack(alignment: .top) {
                Text(PropertyAddress.text(property))
                    .font(.subheadline)
                Spacer(minLength: 0)
                Menu {
                    menu
                } label: {
                    Image(systemName: "ellipsis")
                        .rotationEffect(.degrees(90))
                        .font(.body.weight(.semibold))
                        .foregroundStyle(.secondary)
                        .frame(width: 32, height: 32)
                        .contentShape(.rect)
                }
                .tint(.secondary)
                .accessibilityLabel("Property options")
            }
        }
        .padding(20)
        .card()
    }
}

enum PropertyAddress {
    static func text(_ p: PropertyResponse) -> String {
        [p.addressLine1, p.addressLine2, "\(p.city), \(p.stateCode) \(p.postalCode)"]
            .compactMap { $0 }
            .joined(separator: "\n")
    }
}
