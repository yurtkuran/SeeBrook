import QuickLook
import SwiftUI
import SeebrookShared

/// The HOA's document library (ED-39, ED-52), following the Stitch "Document Library" design:
/// search, category chips, and a page-by-page list. Opening a document fetches a fresh link,
/// downloads the PDF to a temporary file and shows it in Quick Look, which can also share it.
struct DocumentsView: View {
    @Environment(AppModel.self) private var model
    let account: AppModel.Account

    @State private var search = ""
    @State private var categoryId: String?
    @State private var categories: [DocumentCategoryResponse] = []
    @State private var documents: [DocumentResponse] = []
    @State private var total = 0
    @State private var nextPage = 1
    @State private var loading = false
    @State private var error: String?
    @State private var opening: String?
    @State private var preview: URL?

    private var property: PropertyResponse? { model.selectedProperty(in: account) }

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 12) {
                    if !categories.isEmpty {
                        categoryChips.padding(.horizontal, -Theme.padding)
                    }
                    if !documents.isEmpty {
                        HStack {
                            SectionHeader("Community documents")
                            Spacer()
                            SectionHeader(total == 1 ? "1 file" : "\(total) files")
                        }
                        .padding(.horizontal, Theme.padding)
                        .padding(.bottom, -6)
                        CardSection {
                            ForEach(Array(documents.enumerated()), id: \.element.id) { index, document in
                                if index > 0 { CardDivider() }
                                Button { open(document) } label: {
                                    CardRow { DocumentRow(document: document, opening: opening == document.id) }
                                }
                                .buttonStyle(.plain)
                                .disabled(opening != nil)
                                .onAppear { if document.id == documents.last?.id { loadMore() } }
                            }
                        }
                    }
                    if loading { ProgressView().frame(maxWidth: .infinity) }
                }
                .padding(Theme.padding)
            }
            .background(Theme.screen)
            .overlay { emptyState }
            .searchable(text: $search, prompt: "Search bylaws, minutes, budgets…")
            .refreshable { await reload() }
            .toolbar {
                ToolbarItem(placement: .principal) {
                    ScreenTitle(hoa: property?.hoaName, title: "Documents")
                }
            }
            .navigationBarTitleDisplayMode(.inline)
            // Reload when the HOA, category or (after a short pause in typing) the search changes.
            .task(id: Filter(hoaId: property?.hoaId, categoryId: categoryId, search: search)) {
                if !documents.isEmpty || !search.isEmpty {
                    try? await Task.sleep(for: .milliseconds(300))
                    if Task.isCancelled { return }
                }
                await reload()
            }
            .quickLookPreview($preview)
            .onChange(of: preview) { old, _ in
                // The PDF is only kept while it is shown.
                if let old { try? FileManager.default.removeItem(at: old.deletingLastPathComponent()) }
            }
        }
    }

    private struct Filter: Equatable {
        let hoaId: String?
        let categoryId: String?
        let search: String
    }

    private var categoryChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                chip("All", selected: categoryId == nil) { categoryId = nil }
                ForEach(categories, id: \.id) { category in
                    chip(category.name, selected: categoryId == category.id) { categoryId = category.id }
                }
            }
            .padding(.horizontal, Theme.padding)
            .padding(.vertical, 4)
        }
    }

    private func chip(_ title: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.subheadline.weight(.medium))
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .foregroundStyle(selected ? AnyShapeStyle(.white) : AnyShapeStyle(.primary))
                .background(selected ? AnyShapeStyle(.tint) : AnyShapeStyle(.tint.opacity(0.1)), in: Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    @ViewBuilder private var emptyState: some View {
        if let error, documents.isEmpty {
            ContentUnavailableView {
                Label("Documents unavailable", systemImage: "exclamationmark.triangle")
            } description: {
                Text(error)
            } actions: {
                Button("Try again") { Task { await reload() } }
            }
        } else if property == nil {
            ContentUnavailableView("No property", systemImage: "house",
                                   description: Text("Add a property to see your HOA's documents."))
        } else if documents.isEmpty && !loading {
            if search.isEmpty && categoryId == nil {
                ContentUnavailableView("No documents yet", systemImage: "doc",
                                       description: Text("Your HOA hasn't published any documents."))
            } else {
                ContentUnavailableView.search(text: search)
            }
        }
    }

    // MARK: Loading

    private func reload() async {
        guard let hoaId = property?.hoaId else {
            documents = []
            categories = []
            return
        }
        error = nil
        loading = true
        defer { loading = false }
        do {
            async let cats = model.documentCategories(hoaId: hoaId)
            let page = try await model.documents(hoaId: hoaId, search: search, categoryId: categoryId, page: 1)
            categories = try await cats
            if let selected = categoryId, !categories.contains(where: { $0.id == selected }) { categoryId = nil }
            documents = page.documents
            total = Int(page.total)
            nextPage = 2
        } catch is CancellationError {
        } catch {
            if (error as? URLError)?.code == .cancelled { return }
            self.error = AppModel.message(for: error)
            documents = []
        }
    }

    private func loadMore() {
        guard !loading, documents.count < total, let hoaId = property?.hoaId else { return }
        Task {
            loading = true
            defer { loading = false }
            do {
                let page = try await model.documents(hoaId: hoaId, search: search, categoryId: categoryId, page: nextPage)
                let known = Set(documents.map(\.id))
                documents += page.documents.filter { !known.contains($0.id) }
                total = Int(page.total)
                nextPage += 1
            } catch {
                self.error = AppModel.message(for: error)
            }
        }
    }

    private func open(_ document: DocumentResponse) {
        guard let hoaId = property?.hoaId else { return }
        Task {
            opening = document.id
            defer { opening = nil }
            do {
                preview = try await model.downloadDocument(hoaId: hoaId, documentId: document.id)
            } catch {
                self.error = AppModel.message(for: error)
            }
        }
    }
}

/// One document: PDF badge, title, category, size and date.
private struct DocumentRow: View {
    let document: DocumentResponse
    let opening: Bool

    var body: some View {
        HStack(spacing: 14) {
            VStack(spacing: 2) {
                Image(systemName: "doc.richtext")
                    .font(.title3)
                Text("PDF").font(.caption2.weight(.bold))
            }
            .foregroundStyle(.red)
            .frame(width: 48, height: 48)
            .background(.red.opacity(0.1), in: .rect(cornerRadius: Theme.radius))
            .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 4) {
                Text(document.title)
                    .font(.body.weight(.medium))
                    .lineLimit(2)
                HStack(spacing: 6) {
                    Text(document.categoryName)
                        .font(.caption.weight(.medium))
                        .padding(.horizontal, 8)
                        .padding(.vertical, 2)
                        .background(.tint.opacity(0.1), in: Capsule())
                    Text(ByteCountFormatter.string(fromByteCount: document.sizeBytes, countStyle: .file))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Text(dateLine)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
            if opening {
                ProgressView()
            } else {
                Image(systemName: "arrow.down.circle")
                    .font(.title3)
                    .foregroundStyle(.tint)
                    .accessibilityHidden(true)
            }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
        .accessibilityHint("Opens the PDF")
    }

    /// The document's own date when it has one, otherwise when it was added.
    private var dateLine: String {
        if let date = document.documentDate.flatMap(Self.day) {
            return "Dated \(date.formatted(date: .abbreviated, time: .omitted))"
        }
        if let added = try? Date(document.uploadedAt, strategy: .iso8601) {
            return "Added \(added.formatted(date: .abbreviated, time: .omitted))"
        }
        return ""
    }

    /// A calendar date (`2026-11-24`) at noon UTC, so it shows the same day in every time zone.
    private static func day(_ text: String) -> Date? {
        try? Date("\(text)T12:00:00Z", strategy: .iso8601)
    }
}
