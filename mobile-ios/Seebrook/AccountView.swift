import SwiftUI
import SeebrookShared

/// Account: who is signed in, the linked properties, adding a property, and signing out.
struct AccountView: View {
    @Environment(AppModel.self) private var model
    let account: AppModel.Account
    @State private var addingProperty = false

    var body: some View {
        NavigationStack {
            List {
                Section("Signed in as") {
                    VStack(alignment: .leading) {
                        if let name = account.me.displayName { Text(name) }
                        Text(account.me.email).foregroundStyle(.secondary)
                    }
                }
                Section {
                    ForEach(account.properties, id: \.id) { property in
                        Button {
                            model.select(propertyID: property.id)
                        } label: {
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
                        .foregroundStyle(.primary)
                    }
                    Button("Add a property", systemImage: "plus") { addingProperty = true }
                } header: {
                    Text("Your properties")
                } footer: {
                    if account.properties.count > 1 {
                        Text("Tap a property to show it on Home.")
                    }
                }
                Section {
                    Button("Sign out", role: .destructive) { model.signOut() }
                }
            }
            .navigationTitle("Account")
            .refreshable { await model.refresh() }
            .sheet(isPresented: $addingProperty) {
                InvitationView(firstProperty: false)
            }
        }
    }
}
