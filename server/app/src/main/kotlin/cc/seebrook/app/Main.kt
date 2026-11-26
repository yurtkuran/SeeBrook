package cc.seebrook.app

import cc.seebrook.app.cli.AdminCommands
import cc.seebrook.app.cli.CommandException
import cc.seebrook.app.cli.FinanceCommands
import cc.seebrook.app.cli.PaymentCommands
import cc.seebrook.app.cli.RegisterCommands
import cc.seebrook.app.devtools.DevAuth
import cc.seebrook.app.devtools.DevPayments
import cc.seebrook.app.devtools.DevSeed
import cc.seebrook.app.http.seebrookModule
import cc.seebrook.db.Migrations
import cc.seebrook.finance.MonitorPayments
import cc.seebrook.finance.RunRecurringCharges
import cc.seebrook.platform.auth.IdTokenVerifier
import cc.seebrook.platform.config.AppConfig
import cc.seebrook.platform.config.AuthConfig
import cc.seebrook.platform.config.ConfigException
import cc.seebrook.platform.config.PaymentsConfig
import cc.seebrook.platform.db.Database
import cc.seebrook.platform.jobs.JobWorker
import cc.seebrook.platform.jobs.PeriodicTask
import cc.seebrook.app.cli.AlertCommands
import cc.seebrook.tenant.HoaImageKind
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.slf4j.LoggerFactory
import java.time.Duration
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger("cc.seebrook.app")

private const val USAGE = """Usage: seebrook <command>
  serve                      Start the HTTP API (default). Migrates first when SEEBROOK_MIGRATE_ON_START is true.
  migrate                    Apply pending database migrations and exit.
  seed-dev                   Apply migrations and load synthetic Channel Harbor data (development/test only).
  dev-invite <unit> <email>  Issue an owner invitation for a synthetic unit and print its token (development/test only).
  dev-token <email> [--unverified] [--days <1-30>]
                             Print a development ID token for <email> (development auth mode only).
                             Valid for 1 hour, or --days for testing on a phone.

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
  set-hoa-logo --operator <email> --hoa <slug> --file <logo.png|.jpg>
                             The logo owners see in the app (ED-55): PNG or JPEG up to 2 MB, at least 32 pixels a
                             side. Stored as a PNG at most 512 pixels a side; replaces the current logo.
  remove-hoa-logo --operator <email> --hoa <slug>
  set-hoa-photo --operator <email> --hoa <slug> --file <photo.jpg|.png>
                             The community photo on the app's Home screen (ED-57): PNG or JPEG up to 10 MB, at
                             least 320 pixels a side; landscape works best. Stored as a JPEG at most 1600 pixels
                             a side; replaces the current photo.
  remove-hoa-photo --operator <email> --hoa <slug>

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
  convert-register --file <xlsx> --year <yyyy> --out <dir>
                             Convert the HOA register workbook into transactions.csv, expected-balances.csv and
                             accounts.csv in <dir>; checks every unit against the sheet's balance first. Needs no
                             database. Keep <dir> outside the repository: the files hold real financial data.

Payments (ED-43):
  configure-payments --operator <email> --hoa <slug> --provider <stripe|simulated> --merchant <id>
                     --card-fee <fee> --ach-fee <fee> [--suspended true|false] [--commit --reason <text>]
                             Set the HOA's provider, merchant account (Stripe: the HOA's connected account acct_…)
                             and the fee the owner pays per method: off (not accepted), 0 (HOA absorbs the fee),
                             3%, 1.50 or 2.9%+0.30. Dry run by default.
  payment-settings --operator <email> --hoa <slug>
  list-payments --operator <email> --hoa <slug> [--unit <label>]
  export-payments --operator <email> --hoa <slug> --from <date> --to <date> --out <file.csv>
                  [--first-id 2026-00391] [--category-id 101]
                             Received online payments as rows for the register workbook (its columns plus Seebrook
                             Ref), to paste with Skip blanks. Keep the file outside the repository.
  payout-report --operator <email> --hoa <slug> --from <date> --to <date> [--out <file.csv>]
                             What each bank deposit from the provider is made of: payments, refunds, the provider's
                             actual fees and the net, matched to Seebrook's records; anything that does not match is
                             listed. --out writes every line as CSV (keep it outside the repository).
  reconcile-payments         Ask the provider about payments that have not settled and apply what changed (lost or
                             late webhooks); resume HOAs paused by a provider outage once it answers. `serve` does
                             this every 5 minutes. System action: no operator.
  dev-payment-event --hoa <slug> --payment <PAY-…> --status <processing|succeeded|failed|canceled> [--failure <code>]
                             Development only: send the simulated provider's webhook to the local server.

Recurring payments (ED-59):
  recurring-schedule --operator <email> --hoa <slug> --day <1-28> --frequencies <monthly,quarterly,annual>
                     [--anchor-month <1-12>] --reason <text>
                             When owners' recurring plans charge: on --day of the month; quarterly plans from
                             --anchor-month (default January) every third month, annual plans in that month. A
                             weekend or bank holiday moves to the next business day. Audited.
  list-recurring --operator <email> --hoa <slug> [--all]
                             The HOA's recurring plans (--all includes canceled ones).
  run-recurring-charges      Charge the plans due now and submit any left unsent. `serve` does this every hour.
                             System action: no operator.

Background jobs:
  run-jobs                   Run queued jobs now (invitation emails, document scans) and exit. `serve` runs them continuously.

Operator alerts (ED-61), Root only:
  list-alerts --operator <email> [--all]
                             Open alerts (payments paused, mismatched payment events, failed jobs, stuck payments
                             or jobs) with what to do; --all includes resolved ones. New alerts are emailed to
                             the Root operators and logged with ALERT:.
  resolve-alert --operator <email> --alert <id> --reason <text>
                             Mark an alert handled (audited). Outages and backlogs resolve by themselves.
  check-alerts               Run the stuck payment and job checks now. `serve` does this every 15 minutes.
                             System action: no operator."""

private val ADMIN_COMMANDS = setOf(
    "bootstrap-root", "grant-role", "revoke-role", "import-owners",
    "send-invitations", "resend-invitation", "revoke-invitation", "list-invitations", "transfer-ownership",
    "create-category", "list-categories", "upload-document", "list-documents",
    "replace-document", "update-document", "archive-document", "restore-document", "list-versions", "restore-version",
    "rename-category", "reassign-documents", "delete-category", "set-hoa-logo", "remove-hoa-logo", "set-hoa-photo", "remove-hoa-photo",
)

private val FINANCE_COMMANDS = setOf(
    "import-accounts", "import-transactions", "list-balances", "account-statement", "reconcile-balances",
)

private val PAYMENT_COMMANDS = setOf(
    "configure-payments", "payment-settings", "list-payments", "export-payments", "payout-report", "reconcile-payments", "dev-payment-event",
    "recurring-schedule", "list-recurring", "run-recurring-charges",
)

private val ALERT_COMMANDS = setOf("list-alerts", "resolve-alert", "check-alerts")

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
        "dev-token" -> if (devTokenOptions(operands) == null) usage()
        in ADMIN_COMMANDS, in FINANCE_COMMANDS, in PAYMENT_COMMANDS, in ALERT_COMMANDS -> {}
        "convert-register" -> {
            // Needs no configuration or database: it only reads the workbook and writes CSV files.
            try {
                println(RegisterCommands.convert(operands))
            } catch (e: CommandException) {
                System.err.println("Error: ${e.message}")
                if (e.exitCode == 2) System.err.println(USAGE)
                exitProcess(e.exitCode)
            }
            return
        }
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
        val (verified, validFor) = requireNotNull(devTokenOptions(operands))
        println(DevAuth.token(config.environment, config.auth, operands[0], verified, validFor))
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
            in ALERT_COMMANDS -> {
                val alerts = AlertCommands(db)
                val output = try {
                    when (command) {
                        "list-alerts" -> alerts.list(operands)
                        "resolve-alert" -> alerts.resolve(operands)
                        else -> {
                            if (operands.isNotEmpty()) throw CommandException("check-alerts takes no options", 2)
                            val stuck = AlertChecks(db).run()
                            "Checked: ${stuck.payments} stuck payment(s), ${stuck.jobs} overdue background job(s). See list-alerts."
                        }
                    }
                } catch (e: CommandException) {
                    System.err.println("Error: ${e.message}")
                    if (e.exitCode == 2) System.err.println(USAGE)
                    exitProcess(e.exitCode)
                }
                println(output)
            }
            in PAYMENT_COMMANDS -> {
                val payments = PaymentCommands(db, PaymentSetup.providers(config))
                val output = try {
                    when (command) {
                        "configure-payments" -> payments.configure(operands)
                        "payment-settings" -> payments.settings(operands)
                        "list-payments" -> payments.list(operands)
                        "export-payments" -> payments.export(operands)
                        "reconcile-payments" -> payments.reconcile(operands)
                        "payout-report" -> payments.payoutReport(operands)
                        "recurring-schedule" -> payments.recurringSchedule(operands)
                        "list-recurring" -> payments.listRecurring(operands)
                        "run-recurring-charges" -> payments.runRecurring(operands)
                        else -> DevPayments.devPaymentEvent(
                            db, config.environment,
                            (config.payments as? PaymentsConfig.Simulated)?.webhookSecret
                                ?: throw CommandException("dev-payment-event needs SEEBROOK_PAYMENTS_MODE=simulated"),
                            operands,
                        )
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
                        "set-hoa-logo" -> admin.setHoaImage(HoaImageKind.LOGO, operands)
                        "remove-hoa-logo" -> admin.removeHoaImage(HoaImageKind.LOGO, operands)
                        "set-hoa-photo" -> admin.setHoaImage(HoaImageKind.PHOTO, operands)
                        "remove-hoa-photo" -> admin.removeHoaImage(HoaImageKind.PHOTO, operands)
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
                val payments = PaymentSetup.providers(config)
                log.info("Payments: {}", payments.names.sorted().ifEmpty { listOf("disabled") }.joinToString())
                // Lost webhooks and provider outages (ED-46).
                val monitor = if (payments.names.isEmpty()) {
                    null
                } else {
                    val pass = MonitorPayments(db, payments)
                    PeriodicTask("payments", Duration.ofMinutes(5)) { pass() }.start()
                }
                // Recurring charges (ED-59): hourly, so a due date is charged early on its day.
                val recurring = if (payments.names.isEmpty()) {
                    null
                } else {
                    val run = RunRecurringCharges(db, payments)
                    PeriodicTask("recurring", Duration.ofHours(1)) {
                        val summary = run()
                        if (summary != RunRecurringCharges.Summary(0, 0, 0, 0, 0)) log.info("Recurring charges: {}", summary)
                    }.start()
                }
                // Stuck payments and background jobs (ED-61).
                val checks = AlertChecks(db).let { c -> PeriodicTask("alerts", Duration.ofMinutes(15)) { c.run() }.start() }
                try {
                    embeddedServer(Netty, port = config.httpPort) { seebrookModule(db, verifier, BackgroundJobs.storage(config), payments = payments) }
                        .start(wait = true)
                } finally {
                    checks.close()
                    recurring?.close()
                    monitor?.close()
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

/** `dev-token <email> [--unverified] [--days <1-30>]`: (email verified, validity), or null when malformed. */
internal fun devTokenOptions(operands: List<String>): Pair<Boolean, Duration>? {
    if (operands.isEmpty() || operands[0].startsWith("--")) return null
    var verified = true
    var validFor = Duration.ofHours(1)
    val rest = operands.drop(1).iterator()
    while (rest.hasNext()) {
        when (rest.next()) {
            "--unverified" -> verified = false
            "--days" -> validFor = Duration.ofDays(rest.takeIf { it.hasNext() }?.next()?.toLongOrNull()?.takeIf { it in 1..30 } ?: return null)
            else -> return null
        }
    }
    return verified to validFor
}
