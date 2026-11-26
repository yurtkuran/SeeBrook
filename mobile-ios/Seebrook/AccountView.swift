import SwiftUI
import SeebrookShared

/// Account: who is signed in, the linked properties, adding a property, autopay for the selected
/// property (ED-60), and signing out.
struct AccountView: View {
    @Environment(AppModel.self) private var model
    let account: AppModel.Account
    @State private var addingProperty = false
    /// The selected property's autopay, when the HOA offers it or the owner has a plan.
    @State private var autopay: RecurringResponse?
    @State private var showingAutopay = false

    private var property: PropertyResponse? { model.selectedProperty(in: account) }

    var body: some View {
        NavigationStack {
            CardScreen {
                CardSection(header: "Signed in as") {
                    CardRow {
                        VStack(alignment: .leading) {
                            if let name = account.me.displayName { Text(name) }
                            Text(account.me.email).foregroundStyle(.secondary)
                        }
                    }
                }
                CardSection(header: "Your properties") {
                    ForEach(account.properties, id: \.id) { property in
                        Button {
                            model.select(propertyID: property.id)
                        } label: {
                            CardRow {
                                HStack {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(property.unitLabel).font(.headline)
                                        Text(property.hoaName).font(.subheadline).foregroundStyle(.secondary)
                                    }
                                    Spacer()
                                    if property.id == model.selectedProperty(in: account)?.id {
                                        Image(systemName: "checkmark").foregroundStyle(.tint)
                                            .accessibilityLabel("Selected")
                                    }
                                }
                            }
                        }
                        .buttonStyle(.plain)
                        CardDivider()
                    }
                    Button { addingProperty = true } label: {
                        CardRow { Label("Add a property", systemImage: "plus").foregroundStyle(.tint) }
                    }
                    .buttonStyle(.plain)
                } footer: {
                    if account.properties.count > 1 {
                        Text("Tap a property to show it on Home.")
                    }
                }
                if let property, let autopay, autopay.plan != nil || !autopay.options.isEmpty {
                    CardSection(header: "Payments") {
                        Button { showingAutopay = true } label: { CardRow { AutopayRow(autopay: autopay) } }
                            .buttonStyle(.plain)
                    } footer: {
                        Text("Autopay for \(property.unitLabel), \(property.hoaName).")
                    }
                }
                CardSection {
                    Button(role: .destructive) { model.signOut() } label: {
                        CardRow { Text("Sign out").foregroundStyle(.red) }
                    }
                    .buttonStyle(.plain)
                }
            }
            .navigationTitle("Account")
            .refreshable {
                await model.refresh()
                await loadAutopay()
            }
            .task(id: property?.id) { await loadAutopay() }
            .sheet(isPresented: $showingAutopay) {
                if let property {
                    AutopayView(property: property) { Task { await loadAutopay() } }
                }
            }
            .sheet(isPresented: $addingProperty) {
                InvitationView(firstProperty: false)
            }
        }
    }

    /// Optional: the rest of Account shows even when autopay can't be loaded.
    private func loadAutopay() async {
        guard let propertyId = property?.id else {
            autopay = nil
            return
        }
        autopay = try? await model.recurring(propertyId: propertyId)
    }
}
