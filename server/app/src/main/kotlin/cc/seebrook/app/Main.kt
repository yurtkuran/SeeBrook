package cc.seebrook.app

import cc.seebrook.app.cli.AdminCommands
import cc.seebrook.app.cli.CommandException
import cc.seebrook.app.cli.FinanceCommands
import cc.seebrook.app.devtools.DevAuth
import cc.seebrook.app.devtools.DevSeed
import cc.seebrook.app.http.seebrookModule
import cc.seebrook.db.Migrations
import cc.seebrook.platform.auth.IdTokenVerifier
import cc.seebrook.platform.config.AppConfig
import cc.seebrook.platform.config.AuthConfig
import cc.seebrook.platform.config.ConfigException
import cc.seebrook.platform.db.Database
import cc.seebrook.platform.jobs.JobWorker
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger("cc.seebrook.app")

private const val USAGE = """Usage: seebrook <command>
  serve                      Start the HTTP API (default). Migrates first when SEEBROOK_MIGRATE_ON_START is true.
  migrate                    Apply pending database migrations and exit.
  seed-dev                   Apply migrations and load synthetic Channel Harbor data (development/test only).
  dev-invite <unit> <email>  Issue an owner invitation for a synthetic unit and print its token (development/test only).
  dev-token <email> [--unverified]
                             Print a development ID token for <email> (development auth mode only).

Admin commands (audited; ED-31):
  bootstrap-root --email <email>
                             Create the first Root user. Refused once any Root exists.
  grant-role  --operator <email> --user <email> --role <role> [--hoa <slug>] --reason <text>
  revoke-role --operator <email> --user <email> --role <role> [--hoa <slug>] --reason <text>
                             Roles: root (no --hoa), hoa_admin, membership_admin, document_admin, financial_admin.
  import-owners --operator <email> --hoa <slug> --file <csv> [--commit --reason <text>]
                             Validate an owner/property CSV and show the changes (dry run by default);
                             --commit applies them all-or-nothing. Columns: unit_label, address_line1,
                             address_line2, city, state_code, postal_code, owner_email, owner_name.
  send-invitations --operator <email> --hoa <slug> [--unit <label>] [--commit] [--reason <text>]
                             Email invitations that were never sent (dry run by default; --commit queues them).
  resend-invitation --operator <email> --hoa <slug> --unit <label> --email <email> [--reason <text>]
                             Email a pending invitation again with a new link; the earlier link stops working.
  revoke-invitation --operator <email> --hoa <slug> --unit <label> --email <email> --reason <text>
                             Revoke a pending invitation and cancel any queued email.
  list-invitations --operator <email> --hoa <slug>
                             Show every invitation and its state (unsent, queued, sent, expired, accepted, revoked).
  transfer-ownership --operator <email> --hoa <slug> --unit <label> --to <email>[,<email>...] [--commit --reason <text>]
                             Make the listed people the unit's owners: others lose access at once, their pending
                             invitations are revoked, new people are invited (dry run by default).
  create-category --operator <email> --hoa <slug> --name <name>
  list-categories --operator <email> --hoa <slug>
  upload-document --operator <email> --hoa <slug> --category <name> --title <text> --description <text>
                  --file <pdf> [--tags <a,b>] [--document-date YYYY-MM-DD]
                             Check and upload a PDF; it is published after a clean malware scan.
  list-documents --operator <email> --hoa <slug>
                             Show every document with its ID and state (processing, published, archived, rejected).
  replace-document --operator <email> --hoa <slug> --document <id> --file <pdf>
                             Upload a new version; it replaces the current one after a clean scan.
  update-document --operator <email> --hoa <slug> --document <id> [--title <text>] [--description <text>]
                  [--tags <a,b>|none] [--document-date YYYY-MM-DD|none] [--category <name>]
                             Edit details or move to another category; the PDF is unchanged.
  archive-document --operator <email> --hoa <slug> --document <id> --reason <text>
  restore-document --operator <email> --hoa <slug> --document <id> --reason <text>
                             Hide a document from owners, or show an archived one again.
  list-versions --operator <email> --hoa <slug> --document <id>
  restore-version --operator <email> --hoa <slug> --document <id> --version <id> --reason <text>
                             Make an earlier clean version current again; newer versions are kept.
  rename-category --operator <email> --hoa <slug> --name <name> --to <new name>
  reassign-documents --operator <email> --hoa <slug> --from <category> --to <category>
                             Move every document of a category, archived ones included, to another.
  delete-category --operator <email> --hoa <slug> --name <name> --reason <text>
                             Only when it holds no documents, archived ones included.

Finance (positive balances are owed, negative are credits):
  import-accounts --operator <email> --hoa <slug> --file <csv> [--commit --reason <text>]
                             Map units to their spreadsheet account IDs (columns unit_label,account; dry run by default).
  import-transactions --operator <email> --hoa <slug> --file <csv> [--commit --reason <text>]
                             Import assessments, fees, adjustments, waivers and offline payments; changed rows become
                             audited corrections. Dry run by default; the same file twice changes nothing.
  list-balances --operator <email> --hoa <slug>
  account-statement --operator <email> --hoa <slug> --unit <label>
  reconcile-balances --operator <email> --hoa <slug> --file <csv>
                             Compare ledger balances with the spreadsheet's (columns account,balance) and check the
                             ledger; exits 1 unless everything matches.

Background jobs:
  run-jobs                   Run queued jobs now (invitation emails, document scans) and exit. `serve` runs them continuously."""

private val ADMIN_COMMANDS = setOf(
    "bootstrap-root", "grant-role", "revoke-role", "import-owners",
    "send-invitations", "resend-invitation", "revoke-invitation", "list-invitations", "transfer-ownership",
    "create-category", "list-categories", "upload-document", "list-documents",
    "replace-document", "update-document", "archive-document", "restore-document", "list-versions", "restore-version",
    "rename-category", "reassign-documents", "delete-category",
)

private val FINANCE_COMMANDS = setOf(
    "import-accounts", "import-transactions", "list-balances", "account-statement", "reconcile-balances",
)

private fun usage(): Nothing {
    System.err.println(USAGE)
    exitProcess(2)
}

fun main(args: Array<String>) {
    val command = args.firstOrNull() ?: "serve"
    val operands = args.drop(1)
    if (command in setOf("help", "--help", "-h")) {
        println(USAGE)
        return
    }
    when (command) {
        "serve", "migrate", "seed-dev", "run-jobs" -> if (operands.isNotEmpty()) usage()
        "dev-invite" -> if (operands.size != 2) usage()
        "dev-token" -> if (operands.size !in 1..2 || (operands.size == 2 && operands[1] != "--unverified")) usage()
        in ADMIN_COMMANDS, in FINANCE_COMMANDS -> {}
        else -> usage()
    }

    val config = try {
        AppConfig.fromEnvironment()
    } catch (e: ConfigException) {
        System.err.println("Configuration error: ${e.message}")
        exitProcess(2)
    }

    // Needs no database.
    if (command == "dev-token") {
        println(DevAuth.token(config.environment, config.auth, operands[0], emailVerified = operands.size == 1))
        return
    }

    log.info("Starting '{}' in {} environment", command, config.environment.name.lowercase())
    Database(config.database).use { db ->
        when (command) {
            "migrate" -> migrate(db)
            "seed-dev" -> {
                migrate(db)
                val result = DevSeed.run(db, config.environment)
                log.info(
                    "Seeded HOA '{}': {} properties created, {} total",
                    DevSeed.HOA_SLUG, result.propertiesCreated, result.totalProperties,
                )
            }
            "dev-invite" -> {
                val issued = DevAuth.invite(db, config.environment, operands[0], operands[1])
                println("Invitation for ${operands[1]} to ${operands[0]} (expires ${issued.expiresAt}):")
                println(issued.token)
            }
            in FINANCE_COMMANDS -> {
                val finance = FinanceCommands(db)
                val output = try {
                    when (command) {
                        "import-accounts" -> finance.importAccounts(operands)
                        "import-transactions" -> finance.importTransactions(operands)
                        "list-balances" -> finance.listBalances(operands)
                        "account-statement" -> finance.accountStatement(operands)
                        else -> finance.reconcileBalances(operands)
                    }
                } catch (e: CommandException) {
                    System.err.println("Error: ${e.message}")
                    if (e.exitCode == 2) System.err.println(USAGE)
                    exitProcess(e.exitCode)
                }
                println(output)
            }
            in ADMIN_COMMANDS -> {
                val admin = AdminCommands(db, BackgroundJobs.storage(config), config.documentMaxBytes)
                val output = try {
                    when (command) {
                        "bootstrap-root" -> admin.bootstrapRoot(operands)
                        "grant-role" -> admin.grantRole(operands)
                        "import-owners" -> admin.importOwners(operands)
                        "send-invitations" -> admin.sendInvitations(operands)
                        "resend-invitation" -> admin.resendInvitation(operands)
                        "revoke-invitation" -> admin.revokeInvitation(operands)
                        "list-invitations" -> admin.listInvitations(operands)
                        "transfer-ownership" -> admin.transferOwnership(operands)
                        "create-category" -> admin.createCategory(operands)
                        "list-categories" -> admin.listCategories(operands)
                        "upload-document" -> admin.uploadDocument(operands)
                        "list-documents" -> admin.listDocuments(operands)
                        "replace-document" -> admin.replaceDocument(operands)
                        "update-document" -> admin.updateDocument(operands)
                        "archive-document" -> admin.archiveDocument(operands)
                        "restore-document" -> admin.restoreDocument(operands)
                        "list-versions" -> admin.listVersions(operands)
                        "restore-version" -> admin.restoreVersion(operands)
                        "rename-category" -> admin.renameCategory(operands)
                        "reassign-documents" -> admin.reassignDocuments(operands)
                        "delete-category" -> admin.deleteCategory(operands)
                        else -> admin.revokeRole(operands)
                    }
                } catch (e: CommandException) {
                    System.err.println("Error: ${e.message}")
                    if (e.exitCode == 2) System.err.println(USAGE)
                    exitProcess(e.exitCode)
                }
                println(output)
            }
            "run-jobs" -> {
                val runner = BackgroundJobs.runner(db, config)
                if (runner.kinds.isEmpty()) {
                    println("No job handlers are enabled (email is disabled); nothing was run.")
                } else {
                    println("Ran ${runner.drain(max = 1000)} jobs.")
                }
            }
            "serve" -> {
                if (config.migrateOnStart) migrate(db)
                val runner = BackgroundJobs.runner(db, config)
                val worker = JobWorker(runner).takeIf { runner.kinds.isNotEmpty() }?.also { it.start() }
                log.info("Background jobs: {}", runner.kinds.sorted().ifEmpty { listOf("none (email disabled)") }.joinToString())
                val verifier = when (val auth = config.auth) {
                    is AuthConfig.Firebase -> IdTokenVerifier.firebase(auth.projectId)
                    is AuthConfig.Development -> IdTokenVerifier.development(auth.projectId, auth.secret.toByteArray())
                }
                log.info("Auth: {} (project {})", if (config.auth is AuthConfig.Firebase) "Firebase" else "development tokens", config.auth.projectId)
                try {
                    embeddedServer(Netty, port = config.httpPort) { seebrookModule(db, verifier, BackgroundJobs.storage(config)) }
                        .start(wait = true)
                } finally {
                    worker?.close()
                }
            }
        }
    }
}

private fun migrate(db: Database) {
    val result = Migrations.migrate(db.dataSource)
    log.info("Migrations applied: {}; schema version: {}", result.applied, result.currentVersion ?: "unchanged")
}
