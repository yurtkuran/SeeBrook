package cc.seebrook.app.cli

import cc.seebrook.document.ArchiveDocument
import cc.seebrook.document.CreateCategory
import cc.seebrook.document.DeleteCategory
import cc.seebrook.document.DetailChanges
import cc.seebrook.document.ListVersions
import cc.seebrook.document.ReassignDocuments
import cc.seebrook.document.RenameCategory
import cc.seebrook.document.ReplaceDocument
import cc.seebrook.document.RestoreDocument
import cc.seebrook.document.RestoreVersion
import cc.seebrook.document.UpdateDocumentDetails
import cc.seebrook.document.DocumentDetails
import cc.seebrook.document.ListCategories
import cc.seebrook.document.ListDocumentsForAdmin
import cc.seebrook.document.UploadDocument
import cc.seebrook.identity.BootstrapRoot
import cc.seebrook.identity.GrantRole
import cc.seebrook.identity.ImportOwners
import cc.seebrook.identity.ListInvitations
import cc.seebrook.identity.ResendInvitation
import cc.seebrook.identity.RevokeInvitation
import cc.seebrook.identity.SendInvitations
import cc.seebrook.identity.TransferOwnership
import cc.seebrook.identity.ResolveOperator
import cc.seebrook.identity.RevokeRole
import cc.seebrook.platform.auth.Role
import cc.seebrook.platform.auth.UserActor
import cc.seebrook.platform.db.Database
import cc.seebrook.platform.storage.ObjectStorage
import cc.seebrook.tenant.FindHoa
import cc.seebrook.tenant.HoaImageKind
import cc.seebrook.tenant.RemoveHoaImage
import cc.seebrook.tenant.SetHoaImage
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

/** Admin command failed for a reason the operator can fix; printed without a stack trace. */
class CommandException(message: String, val exitCode: Int = 1) : RuntimeException(message)

/** Parses `--name value` options, rejecting unknown, repeated or value-less ones. */
fun parseOptions(args: List<String>, allowed: Set<String>): Map<String, String> {
    val options = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        val name = args[i].removePrefix("--")
        if (!args[i].startsWith("--") || name !in allowed) throw CommandException("Unknown option '${args[i]}'", 2)
        val value = args.getOrNull(i + 1)?.takeIf { !it.startsWith("--") }
            ?: throw CommandException("Option --$name needs a value", 2)
        if (options.put(name, value) != null) throw CommandException("Option --$name given twice", 2)
        i += 2
    }
    return options
}

private fun Map<String, String>.required(name: String) =
    this[name]?.takeIf { it.isNotBlank() } ?: throw CommandException("Missing --$name", 2)

/**
 * Admin commands (ED-14, ED-31): thin adapters over the same actions the API uses, so operators
 * get the same authorization and audit. Every command except `bootstrap-root` names its operator.
 */
class AdminCommands(
    private val db: Database,
    private val storage: ObjectStorage? = null,
    private val maxDocumentBytes: Int = 25 * 1024 * 1024,
) {
    private fun operator(email: String): UserActor = when (val result = ResolveOperator(db)(email)) {
        is ResolveOperator.Result.Active -> result.actor
        ResolveOperator.Result.NotFound -> throw CommandException("Operator '$email' is not a user")
        ResolveOperator.Result.Disabled -> throw CommandException("Operator '$email' is disabled")
    }

    private fun hoaId(slug: String?): UUID? = slug?.let {
        FindHoa(db).bySlug(it)?.id ?: throw CommandException("No HOA with slug '$it'")
    }

    private fun role(code: String): Role = Role.fromCode(code)
        ?: throw CommandException("Unknown role '$code'; one of ${Role.entries.joinToString { it.code }}", 2)

    fun bootstrapRoot(args: List<String>): String {
        val email = parseOptions(args, setOf("email")).required("email")
        return when (BootstrapRoot(db)(email)) {
            is BootstrapRoot.Result.Created -> "Root granted to $email"
            BootstrapRoot.Result.RootAlreadyExists ->
                throw CommandException("A Root user already exists; an existing Root must use grant-role")
        }
    }

    fun grantRole(args: List<String>): String {
        val o = parseOptions(args, setOf("operator", "user", "role", "hoa", "reason"))
        val role = role(o.required("role"))
        return when (GrantRole(db)(operator(o.required("operator")), o.required("user"), role, hoaId(o["hoa"]), o["reason"])) {
            is GrantRole.Result.Granted -> "Granted ${role.code} to ${o["user"]}"
            GrantRole.Result.AlreadyGranted -> "${o["user"]} already has ${role.code}; nothing changed"
            GrantRole.Result.Forbidden -> throw CommandException("Operator may not grant ${role.code} here")
            GrantRole.Result.UserNotFound -> throw CommandException("No user '${o["user"]}' (users are created when they accept an invitation)")
            GrantRole.Result.InvalidScope -> throw CommandException("root takes no --hoa; every other role needs --hoa", 2)
            GrantRole.Result.ReasonRequired -> throw CommandException("--reason is required (up to 1000 characters)", 2)
        }
    }

    fun revokeRole(args: List<String>): String {
        val o = parseOptions(args, setOf("operator", "user", "role", "hoa", "reason"))
        val role = role(o.required("role"))
        return when (RevokeRole(db)(operator(o.required("operator")), o.required("user"), role, hoaId(o["hoa"]), o["reason"])) {
            RevokeRole.Result.Revoked -> "Revoked ${role.code} from ${o["user"]}"
            RevokeRole.Result.NotFound -> throw CommandException("${o["user"]} has no active ${role.code} there")
            RevokeRole.Result.Forbidden -> throw CommandException("Operator may not revoke ${role.code} here")
            RevokeRole.Result.LastRoot -> throw CommandException("Cannot revoke the last Root")
            RevokeRole.Result.LastHoaAdmin -> throw CommandException("Cannot remove the HOA's last HOA Admin")
            RevokeRole.Result.InvalidScope -> throw CommandException("root takes no --hoa; every other role needs --hoa", 2)
            RevokeRole.Result.ReasonRequired -> throw CommandException("--reason is required (up to 1000 characters)", 2)
        }
    }

    fun importOwners(args: List<String>): String {
        val o = parseOptions(args.filter { it != "--commit" }, setOf("operator", "hoa", "file", "reason"))
        val commit = "--commit" in args
        val path = Path.of(o.required("file"))
        if (!Files.isRegularFile(path)) throw CommandException("No file at $path")
        if (Files.size(path) > 2L * 1024 * 1024) throw CommandException("File is too large")
        val hoaId = requireNotNull(hoaId(o.required("hoa")))
        val result = ImportOwners(db)(
            operator(o.required("operator")), hoaId, path.fileName.toString(), Files.readAllBytes(path), commit, o["reason"],
        )
        return when (result) {
            is ImportOwners.Result.Planned -> describe(result.plan, "Dry run — nothing was changed.") +
                if (result.plan.isEmpty) "" else "\nRe-run with --commit --reason <text> to apply."
            is ImportOwners.Result.Imported -> describe(result.plan, "Imported (batch ${result.importId}).")
            is ImportOwners.Result.AlreadyImported ->
                "This file was already imported at ${result.importedAt} (batch ${result.importId}); nothing changed."
            is ImportOwners.Result.Invalid -> throw CommandException(
                "File rejected; nothing was changed:\n" + result.errors.joinToString("\n") { e ->
                    val where = if (e.line == 0L) "File" else "Line ${e.line}"
                    "  $where${e.column?.let { " [$it]" }.orEmpty()}: ${e.message}"
                },
            )
            is ImportOwners.Result.Conflicts -> throw CommandException(
                "File conflicts with existing data; nothing was changed:\n" +
                    result.conflicts.joinToString("\n") { "  ${it.unitLabel}: ${it.message}" },
            )
            ImportOwners.Result.Forbidden -> throw CommandException("Operator may not manage membership in this HOA")
            ImportOwners.Result.ReasonRequired -> throw CommandException("--commit requires --reason (up to 1000 characters)", 2)
        }
    }

    fun sendInvitations(args: List<String>): String {
        val o = parseOptions(args.filter { it != "--commit" }, setOf("operator", "hoa", "unit", "reason"))
        val commit = "--commit" in args
        val hoaId = requireNotNull(hoaId(o.required("hoa")))
        return when (val result = SendInvitations(db)(operator(o.required("operator")), hoaId, o["unit"], commit, o["reason"])) {
            is SendInvitations.Result.Planned -> buildString {
                appendLine("Dry run — nothing was sent. Invitations that would be emailed: ${result.targets.size}")
                result.targets.forEach { appendLine("    ${it.unitLabel}: ${it.email}") }
                if (result.targets.isNotEmpty()) append("Re-run with --commit to queue these emails.")
            }.trimEnd()
            is SendInvitations.Result.Queued -> buildString {
                appendLine("Queued ${result.targets.size} invitation email${if (result.targets.size == 1) "" else "s"}; the server sends them shortly (or run run-jobs).")
                result.targets.forEach { appendLine("    ${it.unitLabel}: ${it.email}") }
            }.trimEnd()
            SendInvitations.Result.UnitNotFound -> throw CommandException("No unit '${o["unit"]}' in this HOA")
            SendInvitations.Result.Forbidden -> throw CommandException("Operator may not manage membership in this HOA")
        }
    }

    fun resendInvitation(args: List<String>): String {
        val o = parseOptions(args, setOf("operator", "hoa", "unit", "email", "reason"))
        val hoaId = requireNotNull(hoaId(o.required("hoa")))
        val result = ResendInvitation(db)(operator(o.required("operator")), hoaId, o.required("unit"), o.required("email"), o["reason"])
        return when (result) {
            is ResendInvitation.Result.Queued ->
                "Queued a new invitation email to ${o["email"]}; its earlier link stops working once it is sent."
            ResendInvitation.Result.AlreadyQueued -> "An email to ${o["email"]} is already queued; nothing changed"
            ResendInvitation.Result.NotFound ->
                throw CommandException("No pending invitation for ${o["email"]} at ${o["unit"]} (accepted, revoked or never invited)")
            ResendInvitation.Result.Forbidden -> throw CommandException("Operator may not manage membership in this HOA")
        }
    }

    fun revokeInvitation(args: List<String>): String {
        val o = parseOptions(args, setOf("operator", "hoa", "unit", "email", "reason"))
        val hoaId = requireNotNull(hoaId(o.required("hoa")))
        val result = RevokeInvitation(db)(operator(o.required("operator")), hoaId, o.required("unit"), o.required("email"), o["reason"])
        return when (result) {
            is RevokeInvitation.Result.Revoked -> "Revoked the invitation for ${o["email"]} at ${o["unit"]}; its link no longer works"
            RevokeInvitation.Result.NotFound ->
                throw CommandException("No pending invitation for ${o["email"]} at ${o["unit"]}")
            RevokeInvitation.Result.Forbidden -> throw CommandException("Operator may not manage membership in this HOA")
            RevokeInvitation.Result.ReasonRequired -> throw CommandException("--reason is required (up to 1000 characters)", 2)
        }
    }

    fun listInvitations(args: List<String>): String {
        val o = parseOptions(args, setOf("operator", "hoa"))
        val hoaId = requireNotNull(hoaId(o.required("hoa")))
        return when (val result = ListInvitations(db)(operator(o.required("operator")), hoaId)) {
            is ListInvitations.Result.Listed -> if (result.rows.isEmpty()) {
                "No invitations"
            } else {
                result.rows.joinToString("\n") { r ->
                    val sent = r.sentAt?.let { " sent $it" }.orEmpty()
                    val expires = r.expiresAt?.takeIf { r.state == ListInvitations.State.SENT }?.let { ", expires $it" }.orEmpty()
                    "${r.unitLabel}  ${r.email}  ${r.state.name.lowercase()}$sent$expires"
                }
            }
            ListInvitations.Result.Forbidden -> throw CommandException("Operator may not manage membership in this HOA")
        }
    }

    fun transferOwnership(args: List<String>): String {
        val o = parseOptions(args.filter { it != "--commit" }, setOf("operator", "hoa", "unit", "to", "reason"))
        val commit = "--commit" in args
        val hoaId = requireNotNull(hoaId(o.required("hoa")))
        val unit = o.required("unit")
        val to = o.required("to").split(',').map { it.trim() }
        return when (val result = TransferOwnership(db)(operator(o.required("operator")), hoaId, unit, to, commit, o["reason"])) {
            is TransferOwnership.Result.Planned -> describe(result.plan, "Dry run — nothing was changed.") +
                "\nRe-run with --commit --reason <text> to apply."
            is TransferOwnership.Result.Transferred -> describe(result.plan, "Transferred.") +
                if (result.plan.inviting.isEmpty()) "" else "\nSend the new invitations with: send-invitations --unit \"$unit\" --commit"
            TransferOwnership.Result.NoChange -> "$unit already has exactly these owners or invitations; nothing changed"
            TransferOwnership.Result.UnitNotFound -> throw CommandException("No unit '$unit' in this HOA")
            is TransferOwnership.Result.InvalidEmail -> throw CommandException("'${result.email}' is not a valid email address", 2)
            TransferOwnership.Result.NoNewOwners -> throw CommandException("--to needs at least one email address", 2)
            TransferOwnership.Result.Forbidden -> throw CommandException("Operator may not manage membership in this HOA")
            TransferOwnership.Result.ReasonRequired -> throw CommandException("--commit requires --reason (up to 1000 characters)", 2)
        }
    }

    fun createCategory(args: List<String>): String {
        val o = parseOptions(args, setOf("operator", "hoa", "name"))
        val hoaId = requireNotNull(hoaId(o.required("hoa")))
        return when (val result = CreateCategory(db)(operator(o.required("operator")), hoaId, o.required("name"))) {
            is CreateCategory.Result.Created -> "Created category '${result.category.name}'"
            CreateCategory.Result.AlreadyExists -> throw CommandException("A category named '${o["name"]}' already exists")
            CreateCategory.Result.InvalidName -> throw CommandException("Category names are 1 to 100 characters", 2)
            CreateCategory.Result.Forbidden -> throw CommandException("Operator may not manage documents in this HOA")
        }
    }

    fun listCategories(args: List<String>): String {
        val o = parseOptions(args, setOf("operator", "hoa"))
        val hoaId = requireNotNull(hoaId(o.required("hoa")))
        return when (val result = ListCategories(db)(operator(o.required("operator")), hoaId)) {
            is ListCategories.Result.Listed -> result.categories.joinToString("\n") { it.name }.ifEmpty { "No categories" }
            ListCategories.Result.Forbidden -> throw CommandException("Operator may not manage documents in this HOA")
        }
    }

    fun uploadDocument(args: List<String>): String {
        val o = parseOptions(args, setOf("operator", "hoa", "category", "title", "description", "tags", "document-date", "file"))
        val storage = requireStorage()
        val path = pdfFile(o.required("file"))
        val date = o["document-date"]?.let(::date)
        val details = DocumentDetails(
            title = o.required("title"),
            description = o.required("description"),
            tags = o["tags"]?.split(',').orEmpty(),
            documentDate = date,
        )
        val hoaId = requireNotNull(hoaId(o.required("hoa")))
        val result = UploadDocument(db, storage, maxDocumentBytes)(
            operator(o.required("operator")), hoaId, o.required("category"), details, path.fileName.toString(), Files.readAllBytes(path),
        )
        return when (result) {
            is UploadDocument.Result.Uploaded ->
                "Uploaded (${result.pageCount} pages). It is quarantined until the malware scan passes, then published " +
                    "automatically; check with list-documents."
            is UploadDocument.Result.InvalidDetails ->
                throw CommandException("Not uploaded: " + result.problems.joinToString("; ") { "${it.first} ${it.second}" }, 2)
            is UploadDocument.Result.InvalidFile -> throw CommandException("Not uploaded: ${result.reason}")
            UploadDocument.Result.CategoryNotFound -> throw CommandException("No category '${o["category"]}' (see list-categories)")
            UploadDocument.Result.Forbidden -> throw CommandException("Operator may not manage documents in this HOA")
        }
    }

    fun listDocuments(args: List<String>): String {
        val o = parseOptions(args, setOf("operator", "hoa"))
        val hoaId = requireNotNull(hoaId(o.required("hoa")))
        return when (val result = ListDocumentsForAdmin(db)(operator(o.required("operator")), hoaId)) {
            is ListDocumentsForAdmin.Result.Listed -> result.rows.joinToString("\n") { r ->
                val rejected = r.rejectionReason?.let { " — rejected: $it" }.orEmpty()
                "${r.documentId}  ${r.category}  ${r.title}  ${r.status}$rejected"
            }.ifEmpty { "No documents" }
            ListDocumentsForAdmin.Result.Forbidden -> throw CommandException("Operator may not manage documents in this HOA")
        }
    }

    /** `set-hoa-logo` (ED-55) and `set-hoa-photo` (ED-57). */
    fun setHoaImage(kind: HoaImageKind, args: List<String>): String {
        val o = parseOptions(args, setOf("operator", "hoa", "file"))
        val storage = requireStorage()
        val path = Path.of(o.required("file"))
        if (!Files.isRegularFile(path)) throw CommandException("No file at $path")
        if (Files.size(path) > kind.maxUploadBytes) throw CommandException("File is larger than ${kind.maxUploadMegabytes} MB")
        val hoaId = requireNotNull(hoaId(o.required("hoa")))
        return when (val result = SetHoaImage(db, storage, kind)(operator(o.required("operator")), hoaId, Files.readAllBytes(path))) {
            is SetHoaImage.Result.Set -> "${kind.label} set (${result.width} × ${result.height} pixels, ${result.bytes / 1024} KB). " +
                "Owners see it the next time the app loads their properties."
            is SetHoaImage.Result.InvalidImage -> throw CommandException("Not set: ${result.reason}")
            SetHoaImage.Result.HoaNotFound -> throw CommandException("No HOA '${o["hoa"]}'")
            SetHoaImage.Result.Forbidden -> throw CommandException("Operator may not manage this HOA's profile")
        }
    }

    /** `remove-hoa-logo` and `remove-hoa-photo`. */
    fun removeHoaImage(kind: HoaImageKind, args: List<String>): String {
        val o = parseOptions(args, setOf("operator", "hoa"))
        val storage = requireStorage()
        val hoaId = requireNotNull(hoaId(o.required("hoa")))
        return when (RemoveHoaImage(db, storage, kind)(operator(o.required("operator")), hoaId)) {
            RemoveHoaImage.Result.Removed -> "${kind.label} removed; " +
                (if (kind == HoaImageKind.LOGO) "the app shows its placeholder." else "Home shows no photo.")
            RemoveHoaImage.Result.NoImage -> "The HOA has no ${kind.code}; nothing changed."
            RemoveHoaImage.Result.HoaNotFound -> throw CommandException("No HOA '${o["hoa"]}'")
            RemoveHoaImage.Result.Forbidden -> throw CommandException("Operator may not manage this HOA's profile")
        }
    }

    private fun requireStorage(): ObjectStorage =
        storage ?: throw CommandException("Document storage is not configured (SEEBROOK_STORAGE_MODE)")

    private fun pdfFile(name: String): Path {
        val path = Path.of(name)
        if (!Files.isRegularFile(path)) throw CommandException("No file at $path")
        if (Files.size(path) > maxDocumentBytes) throw CommandException("File is larger than ${maxDocumentBytes / (1024 * 1024)} MB")
        return path
    }

    private fun date(value: String): LocalDate = try {
        LocalDate.parse(value)
    } catch (_: DateTimeParseException) {
        throw CommandException("--document-date must be YYYY-MM-DD (or 'none' to clear it)", 2)
    }

    private fun uuid(name: String, value: String): UUID =
        runCatching { UUID.fromString(value) }.getOrNull() ?: throw CommandException("--$name must be an ID (see list-documents)", 2)

    private val noDocument = "No such document in this HOA (see list-documents)"
    private val forbiddenDocuments = "Operator may not manage documents in this HOA"
    private val reasonRequired = "--reason is required (up to 1000 characters)"

    fun replaceDocument(args: List<String>): String {
        val o = parseOptions(args, setOf("operator", "hoa", "document", "file"))
        val storage = requireStorage()
        val path = pdfFile(o.required("file"))
        val hoaId = requireNotNull(hoaId(o.required("hoa")))
        val result = ReplaceDocument(db, storage, maxDocumentBytes)(
            operator(o.required("operator")), hoaId, uuid("document", o.required("document")),
            path.fileName.toString(), Files.readAllBytes(path),
        )
        return when (result) {
            is ReplaceDocument.Result.Uploaded ->
                "Uploaded a new version (${result.pageCount} pages). Owners keep seeing the current version until the " +
                    "scan passes; then the new one replaces it. Check with list-versions."
            is ReplaceDocument.Result.InvalidFile -> throw CommandException("Not uploaded: ${result.reason}")
            ReplaceDocument.Result.Archived -> throw CommandException("The document is archived; restore it first")
            ReplaceDocument.Result.AlreadyProcessing -> throw CommandException("A new version of this document is still being scanned")
            ReplaceDocument.Result.NotFound -> throw CommandException(noDocument)
            ReplaceDocument.Result.Forbidden -> throw CommandException(forbiddenDocuments)
        }
    }

    fun archiveDocument(args: List<String>): String {
        val o = parseOptions(args, setOf("operator", "hoa", "document", "reason"))
        val hoaId = requireNotNull(hoaId(o.required("hoa")))
        return when (val result = ArchiveDocument(db)(operator(o.required("operator")), hoaId, uuid("document", o.required("document")), o["reason"])) {
            ArchiveDocument.Result.Archived -> "Archived; owners no longer see it. restore-document brings it back."
            is ArchiveDocument.Result.NotPublished -> throw CommandException("Only published documents can be archived (this one is ${result.status})")
            ArchiveDocument.Result.NotFound -> throw CommandException(noDocument)
            ArchiveDocument.Result.ReasonRequired -> throw CommandException(reasonRequired, 2)
            ArchiveDocument.Result.Forbidden -> throw CommandException(forbiddenDocuments)
        }
    }

    fun restoreDocument(args: List<String>): String {
        val o = parseOptions(args, setOf("operator", "hoa", "document", "reason"))
        val hoaId = requireNotNull(hoaId(o.required("hoa")))
        return when (val result = RestoreDocument(db)(operator(o.required("operator")), hoaId, uuid("document", o.required("document")), o["reason"])) {
            RestoreDocument.Result.Restored -> "Restored; owners see it again."
            is RestoreDocument.Result.NotArchived -> throw CommandException("Only archived documents can be restored (this one is ${result.status})")
            RestoreDocument.Result.NotFound -> throw CommandException(noDocument)
            RestoreDocument.Result.ReasonRequired -> throw CommandException(reasonRequired, 2)
            RestoreDocument.Result.Forbidden -> throw CommandException(forbiddenDocuments)
        }
    }

    fun listVersions(args: List<String>): String {
        val o = parseOptions(args, setOf("operator", "hoa", "document"))
        val hoaId = requireNotNull(hoaId(o.required("hoa")))
        return when (val result = ListVersions(db)(operator(o.required("operator")), hoaId, uuid("document", o.required("document")))) {
            is ListVersions.Result.Listed -> buildString {
                appendLine("${result.title} (${result.status})")
                result.versions.forEach { v ->
                    val marker = if (v.isCurrent) "* current" else ""
                    val rejected = v.rejectionReason?.let { " — rejected: $it" }.orEmpty()
                    appendLine("  ${v.versionId}  ${v.uploadedAt.toLocalDate()}  ${v.state}  ${v.pageCount} pages  ${v.fileName}  $marker$rejected".trimEnd())
                }
            }.trimEnd()
            ListVersions.Result.NotFound -> throw CommandException(noDocument)
            ListVersions.Result.Forbidden -> throw CommandException(forbiddenDocuments)
        }
    }

    fun restoreVersion(args: List<String>): String {
        val o = parseOptions(args, setOf("operator", "hoa", "document", "version", "reason"))
        val hoaId = requireNotNull(hoaId(o.required("hoa")))
        val result = RestoreVersion(db)(
            operator(o.required("operator")), hoaId, uuid("document", o.required("document")), uuid("version", o.required("version")), o["reason"],
        )
        return when (result) {
            RestoreVersion.Result.Restored -> "That version is now current. Newer versions are kept and can be restored the same way."
            RestoreVersion.Result.AlreadyCurrent -> "That version is already current; nothing changed"
            RestoreVersion.Result.VersionNotFound -> throw CommandException("No such version of this document (see list-versions)")
            is RestoreVersion.Result.VersionNotClean -> throw CommandException("Only clean versions can be restored (this one is ${result.state})")
            is RestoreVersion.Result.NotPublished -> throw CommandException("The document has no published version yet (it is ${result.status})")
            RestoreVersion.Result.AlreadyProcessing -> throw CommandException("A new version is still being scanned; try again once it finishes")
            RestoreVersion.Result.NotFound -> throw CommandException(noDocument)
            RestoreVersion.Result.ReasonRequired -> throw CommandException(reasonRequired, 2)
            RestoreVersion.Result.Forbidden -> throw CommandException(forbiddenDocuments)
        }
    }

    fun updateDocument(args: List<String>): String {
        val o = parseOptions(args, setOf("operator", "hoa", "document", "title", "description", "tags", "document-date", "category"))
        val hoaId = requireNotNull(hoaId(o.required("hoa")))
        val dateOption = o["document-date"]
        val changes = DetailChanges(
            title = o["title"],
            description = o["description"],
            tags = o["tags"]?.let { if (it == "none") emptyList() else it.split(',') },
            documentDate = dateOption?.takeIf { it != "none" }?.let(::date),
            clearDocumentDate = dateOption == "none",
            categoryName = o["category"],
        )
        if (changes.isEmpty) throw CommandException("Give at least one of --title, --description, --tags, --document-date, --category", 2)
        val result = UpdateDocumentDetails(db)(operator(o.required("operator")), hoaId, uuid("document", o.required("document")), changes)
        return when (result) {
            UpdateDocumentDetails.Result.Updated -> "Updated"
            UpdateDocumentDetails.Result.NoChange -> "Nothing changed"
            is UpdateDocumentDetails.Result.InvalidDetails ->
                throw CommandException("Not updated: " + result.problems.joinToString("; ") { "${it.first} ${it.second}" }, 2)
            UpdateDocumentDetails.Result.CategoryNotFound -> throw CommandException("No category '${o["category"]}' (see list-categories)")
            UpdateDocumentDetails.Result.NotFound -> throw CommandException(noDocument)
            UpdateDocumentDetails.Result.Forbidden -> throw CommandException(forbiddenDocuments)
        }
    }

    fun renameCategory(args: List<String>): String {
        val o = parseOptions(args, setOf("operator", "hoa", "name", "to"))
        val hoaId = requireNotNull(hoaId(o.required("hoa")))
        return when (RenameCategory(db)(operator(o.required("operator")), hoaId, o.required("name"), o.required("to"))) {
            RenameCategory.Result.Renamed -> "Renamed '${o["name"]}' to '${o["to"]?.trim()}'"
            RenameCategory.Result.NotFound -> throw CommandException("No category '${o["name"]}' (see list-categories)")
            RenameCategory.Result.AlreadyExists -> throw CommandException("A category named '${o["to"]}' already exists")
            RenameCategory.Result.InvalidName -> throw CommandException("Category names are 1 to 100 characters", 2)
            RenameCategory.Result.Forbidden -> throw CommandException(forbiddenDocuments)
        }
    }

    fun reassignDocuments(args: List<String>): String {
        val o = parseOptions(args, setOf("operator", "hoa", "from", "to"))
        val hoaId = requireNotNull(hoaId(o.required("hoa")))
        return when (val result = ReassignDocuments(db)(operator(o.required("operator")), hoaId, o.required("from"), o.required("to"))) {
            is ReassignDocuments.Result.Moved -> "Moved ${result.count} document(s) from '${o["from"]}' to '${o["to"]}'"
            ReassignDocuments.Result.SameCategory -> throw CommandException("--from and --to are the same category", 2)
            is ReassignDocuments.Result.CategoryNotFound -> throw CommandException("No category '${result.name}' (see list-categories)")
            ReassignDocuments.Result.Forbidden -> throw CommandException(forbiddenDocuments)
        }
    }

    fun deleteCategory(args: List<String>): String {
        val o = parseOptions(args, setOf("operator", "hoa", "name", "reason"))
        val hoaId = requireNotNull(hoaId(o.required("hoa")))
        return when (val result = DeleteCategory(db)(operator(o.required("operator")), hoaId, o.required("name"), o["reason"])) {
            DeleteCategory.Result.Deleted -> "Deleted category '${o["name"]}'"
            is DeleteCategory.Result.NotEmpty -> throw CommandException(
                "'${o["name"]}' still holds ${result.documents} document(s), archived ones included; move them first with reassign-documents",
            )
            DeleteCategory.Result.NotFound -> throw CommandException("No category '${o["name"]}' (see list-categories)")
            DeleteCategory.Result.ReasonRequired -> throw CommandException(reasonRequired, 2)
            DeleteCategory.Result.Forbidden -> throw CommandException(forbiddenDocuments)
        }
    }

    private fun describe(plan: TransferOwnership.Plan, heading: String): String = buildString {
        appendLine("$heading ${plan.unitLabel}:")
        appendLine("  Ownership ends for: ${plan.ending.ifEmpty { listOf("nobody") }.joinToString()}")
        appendLine("  Invitations revoked: ${plan.revoking.ifEmpty { listOf("none") }.joinToString()}")
        appendLine("  New owners invited (sent separately): ${plan.inviting.ifEmpty { listOf("none") }.joinToString()}")
        appendLine("  Unchanged: ${plan.continuing.ifEmpty { listOf("nobody") }.joinToString()}")
    }.trimEnd()

    private fun describe(plan: ImportOwners.Plan, heading: String): String = buildString {
        appendLine(heading)
        appendLine("  Rows: ${plan.rowCount}; units unchanged: ${plan.unchangedUnits}")
        appendLine("  New properties: ${plan.propertiesToCreate.size}")
        plan.propertiesToCreate.forEach { appendLine("    ${it.unitLabel}, ${it.addressLine1}") }
        appendLine("  New owner invitations (sent separately): ${plan.invitationsToCreate.size}")
        plan.invitationsToCreate.forEach { appendLine("    ${it.unitLabel}: ${it.email}") }
    }.trimEnd()
}
