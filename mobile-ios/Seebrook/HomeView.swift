import SwiftUI
import SeebrookShared

/// Placeholder home screen for the setup milestone: who is signed in and their properties.
struct HomeView: View {
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
                Section("Your properties") {
                    if account.properties.isEmpty {
                        Text("No properties are linked to this account.").foregroundStyle(.secondary)
                    }
                    ForEach(account.properties, id: \.id) { property in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(property.unitLabel).font(.headline)
                            Text(address(property)).font(.subheadline).foregroundStyle(.secondary)
                        }
                    }
                }
            }
            .navigationTitle("Seebrook")
            .refreshable { await model.refresh() }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Sign out") { model.signOut() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Add property", systemImage: "plus") { addingProperty = true }
                }
            }
            .sheet(isPresented: $addingProperty) {
                InvitationView(firstProperty: false)
            }
        }
    }

    private func address(_ p: PropertyResponse) -> String {
        [p.addressLine1, p.addressLine2, "\(p.city), \(p.stateCode) \(p.postalCode)"]
            .compactMap { $0 }
            .joined(separator: "\n")
    }
}
