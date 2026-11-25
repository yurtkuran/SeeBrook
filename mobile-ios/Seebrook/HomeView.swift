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

    /// Debug builds: scripted runs can open a tab with SIMCTL_CHILD_SEEBROOK_TAB (ED-48).
    private static var startTab: String {
        #if DEBUG
        ProcessInfo.processInfo.environment["SEEBROOK_TAB"] ?? "home"
        #else
        "home"
        #endif
    }
}

/// Home: a greeting, the selected property (with a menu to switch property or add one) and its
/// balance, which opens Payments.
struct HomeView: View {
    @Environment(AppModel.self) private var model
    let account: AppModel.Account
    let showPayments: () -> Void
    @State private var addingProperty = false
    @State private var summary: AccountSummaryResponse?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    if let property = model.selectedProperty(in: account) {
                        greeting(unit: property.unitLabel)
                        PropertyCard(property: property, menu: propertyMenu)
                        if let summary {
                            Button(action: showPayments) {
                                VStack(alignment: .leading, spacing: 0) {
                                    BalanceCard(property: property, summary: summary, compact: true)
                                    Label("View payments", systemImage: "chevron.right")
                                        .labelStyle(TrailingIconLabelStyle())
                                        .font(.subheadline.weight(.medium))
                                        .foregroundStyle(.tint)
                                        .padding(.horizontal, 20)
                                        .padding(.bottom, 16)
                                }
                                .background(.background, in: .rect(cornerRadius: 12))
                            }
                            .buttonStyle(.plain)
                        }
                    } else {
                        greeting(unit: nil)
                        noProperties
                    }
                }
                .padding(20)
            }
            .background(Color(.systemGroupedBackground))
            .refreshable {
                await model.refresh()
                await loadSummary()
            }
            .task(id: model.selectedProperty(in: account)?.id) { await loadSummary() }
            .toolbar {
                ToolbarItem(placement: .principal) {
                    ScreenTitle(hoa: model.selectedProperty(in: account)?.hoaName, title: "Home")
                }
            }
            .navigationBarTitleDisplayMode(.inline)
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

    private func greeting(unit: String?) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 8) {
                Text(Self.salutation()).foregroundStyle(.tint)
                if let unit {
                    Text(unit)
                        .font(.subheadline.weight(.medium))
                        .padding(.horizontal, 10)
                        .padding(.vertical, 3)
                        .background(.tint.opacity(0.12), in: Capsule())
                }
            }
            Text(account.me.displayName ?? account.me.email)
                .font(.title3.weight(.semibold))
        }
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
        .background(.background, in: .rect(cornerRadius: 12))
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

    private static func salutation(at date: Date = .now) -> String {
        switch Calendar.current.component(.hour, from: date) {
        case 5..<12: "Good morning"
        case 12..<17: "Good afternoon"
        default: "Good evening"
        }
    }
}

/// The selected property: unit, address and HOA, with the property menu.
private struct PropertyCard<MenuItems: View>: View {
    let property: PropertyResponse
    let menu: MenuItems

    var body: some View {
        HStack(alignment: .top, spacing: 16) {
            Image(systemName: "house.fill")
                .font(.title2)
                .foregroundStyle(.tint)
                .frame(width: 56, height: 56)
                .background(.tint.opacity(0.12), in: .rect(cornerRadius: 8))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(property.unitLabel.uppercased())
                    .font(.caption.weight(.semibold))
                    .tracking(1)
                    .foregroundStyle(.tint)
                Text(PropertyAddress.text(property))
                Text(property.hoaName)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
            Menu {
                menu
            } label: {
                Image(systemName: "ellipsis")
                    .rotationEffect(.degrees(90))
                    .frame(width: 36, height: 36)
                    .background(.tint.opacity(0.08), in: .rect(cornerRadius: 8))
            }
            .accessibilityLabel("Property options")
        }
        .padding(16)
        .background(.background, in: .rect(cornerRadius: 12))
    }
}

enum PropertyAddress {
    static func text(_ p: PropertyResponse) -> String {
        [p.addressLine1, p.addressLine2, "\(p.city), \(p.stateCode) \(p.postalCode)"]
            .compactMap { $0 }
            .joined(separator: "\n")
    }
}

/// Title first, icon after: "View payments ›".
private struct TrailingIconLabelStyle: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 4) {
            configuration.title
            configuration.icon.font(.caption.weight(.semibold))
        }
    }
}
