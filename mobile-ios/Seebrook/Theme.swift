import SwiftUI

/// The app's shared look (owner request 2026-11-26): one corner radius for every card, section,
/// field and button, so they all match. Change it here and every screen follows.
///
/// SwiftUI can't change the rounding of system `Form` and `List` sections, so screens draw their
/// sections with [CardSection] instead.
enum Theme {
    /// Cards, sections, fields, buttons and icon tiles.
    static let radius: CGFloat = 10
    /// A card's background on the grouped screen background (light and dark).
    static let card = Color(.secondarySystemGroupedBackground)
    /// The screen behind the cards.
    static let screen = Color(.systemGroupedBackground)
    /// Space around the cards and between sections.
    static let padding: CGFloat = 16
    static let sectionSpacing: CGFloat = 22
}

extension View {
    /// A rounded card background in the app's radius.
    func card() -> some View {
        background(Theme.card, in: .rect(cornerRadius: Theme.radius))
    }
}

/// A scrolling screen of card sections on the grouped background.
struct CardScreen<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Theme.sectionSpacing) {
                content
            }
            .padding(Theme.padding)
        }
        .background(Theme.screen)
        .scrollDismissesKeyboard(.interactively)
    }
}

/// A section like a form's: an optional small header, its rows on one card, an optional footer.
/// Put [CardDivider] between rows.
struct CardSection<Content: View, Footer: View>: View {
    var header: String?
    @ViewBuilder var content: Content
    @ViewBuilder var footer: Footer

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let header {
                SectionHeader(header).padding(.horizontal, Theme.padding)
            }
            VStack(alignment: .leading, spacing: 0) {
                content
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .card()
            footer
                .font(.footnote)
                .foregroundStyle(.secondary)
                .padding(.horizontal, Theme.padding)
        }
    }
}

extension CardSection where Footer == EmptyView {
    init(header: String? = nil, @ViewBuilder content: () -> Content) {
        self.init(header: header, content: content, footer: { EmptyView() })
    }
}

/// One row in a [CardSection]: the usual row padding, full width.
struct CardRow<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        content
            .padding(.horizontal, Theme.padding)
            .padding(.vertical, 12)
            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
            .contentShape(.rect)
    }
}

/// The line between rows of a [CardSection].
struct CardDivider: View {
    var body: some View {
        Divider().padding(.leading, Theme.padding)
    }
}

/// A full-width primary button in the app's radius, for the bottom of a screen.
struct PrimaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity, minHeight: 50)
            .background(Color.accentColor.opacity(isEnabled ? (configuration.isPressed ? 0.8 : 1) : 0.35),
                        in: .rect(cornerRadius: Theme.radius))
    }
}
