import Foundation

/// Where the API lives. Release builds use the production server; development builds remember
/// the address entered on the sign-in screen.
enum ServerSettings {
    #if DEBUG
    private static let key = "serverURL"
    static var url: String {
        get { UserDefaults.standard.string(forKey: key) ?? "http://localhost:8088" }
        set { UserDefaults.standard.set(newValue, forKey: key) }
    }
    #else
    // Placeholder until hosting is chosen (docs/OPEN_ITEMS.md).
    static let url = "https://api.seebrook.cc"
    #endif
}
