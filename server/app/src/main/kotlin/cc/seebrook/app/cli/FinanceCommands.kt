package cc.seebrook.app.cli

import cc.seebrook.finance.AccountStatement
import cc.seebrook.finance.ImportAccounts
import cc.seebrook.finance.ImportTransactions
import cc.seebrook.finance.ListBalances
import cc.seebrook.finance.Money
import cc.seebrook.finance.ReconcileBalances
import cc.seebrook.finance.RowError
import cc.seebrook.identity.ResolveOperator
import cc.seebrook.platform.auth.UserActor
import cc.seebrook.platform.db.Database
import cc.seebrook.tenant.FindHoa
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Finance admin commands (ED-41): thin adapters over FinanceService actions, like [AdminCommands]. */
class FinanceCommands(private val db: Database) {
    private val forbidden = "Operator may not manage finances in this HOA"

    private fun operator(email: String): UserActor = when (val result = ResolveOperator(db)(email)) {
        is ResolveOperator.Result.Active -> result.actor
        ResolveOperator.Result.NotFound -> throw CommandException("Operator '$email' is not a user")
        ResolveOperator.Result.Disabled -> throw CommandException("Operator '$email' is disabled")
    }

    private fun hoaId(slug: String): UUID = FindHoa(db).bySlug(slug)?.id ?: throw CommandException("No HOA with slug '$slug'")

    private fun Map<String, String>.required(name: String) =
        this[name]?.takeIf { it.isNotBlank() } ?: throw CommandException("Missing --$name", 2)

    private fun file(name: String): Path {
        val path = Path.of(name)
        if (!Files.isRegularFile(path)) throw CommandException("No file at $path")
        if (Files.size(path) > 2L * 1024 * 1024) throw CommandException("File is larger than 2 MiB")
        return path
    }

    private fun rejected(errors: List<RowError>): Nothing = throw CommandException(
        "File rejected; nothing was changed:\n" + errors.joinToString("\n") { e ->
            val where = if (e.line == 0L) "File" else "Line ${e.line}"
            "  $where${e.column?.let { " [$it]" }.orEmpty()}: ${e.message}"
        },
    )

    private fun conflicts(conflicts: List<String>): Nothing =
        throw CommandException("File conflicts with existing data; nothing was changed:\n" + conflicts.joinToString("\n") { "  $it" })

    fun importAccounts(args: List<String>): String {
        val o = parseOptions(args.filter { it != "--commit" }, setOf("operator", "hoa", "file", "reason"))
        val commit = "--commit" in args
        val path = file(o.required("file"))
        val result = ImportAccounts(db)(operator(o.required("operator")), hoaId(o.required("hoa")), Files.readAllBytes(path), commit, o["reason"])
        fun describe(plan: ImportAccounts.Plan, heading: String) = buildString {
            appendLine(heading)
            appendLine("  New accounts: ${plan.toCreate.size}")
            plan.toCreate.forEach { appendLine("    ${it.unitLabel} = ${it.externalId}") }
            appendLine("  Corrected identifiers: ${plan.toRemap.size}")
            plan.toRemap.forEach { appendLine("    ${it.unitLabel}: ${it.from} -> ${it.to}") }
            append("  Unchanged: ${plan.unchanged}")
        }
        return when (result) {
            is ImportAccounts.Result.Planned -> describe(result.plan, "Dry run — nothing was changed.") +
                if (result.plan.isEmpty) "" else "\nRe-run with --commit --reason <text> to apply."
            is ImportAccounts.Result.Imported -> describe(result.plan, "Accounts mapped.")
            is ImportAccounts.Result.Invalid -> rejected(result.errors)
            is ImportAccounts.Result.Conflicts -> conflicts(result.conflicts)
            ImportAccounts.Result.Forbidden -> throw CommandException(forbidden)
            ImportAccounts.Result.ReasonRequired -> throw CommandException("--commit requires --reason (up to 1000 characters)", 2)
        }
    }

    fun importTransactions(args: List<String>): String {
        val o = parseOptions(args.filter { it != "--commit" }, setOf("operator", "hoa", "file", "reason"))
        val commit = "--commit" in args
        val path = file(o.required("file"))
        val result = ImportTransactions(db)(
            operator(o.required("operator")), hoaId(o.required("hoa")), path.fileName.toString(), Files.readAllBytes(path), commit, o["reason"],
        )
        fun describe(plan: ImportTransactions.Plan, heading: String) = buildString {
            appendLine(heading)
            appendLine("  Rows: ${plan.rowCount}")
            appendLine("  New transactions: ${plan.toCreate.size}")
            appendLine("  Corrections: ${plan.toCorrect.size}")
            plan.toCorrect.forEach { c ->
                val amount = if ("amount" in c.changed) " (${Money.format(c.before.amountCents)} -> ${Money.format(c.row.values.amountCents)})" else ""
                appendLine("    ${c.row.externalId}: ${c.changed.joinToString()}$amount")
            }
            appendLine("  Unchanged: ${plan.unchanged}")
            append("  Net change to balances: ${Money.format(plan.netChangeCents)} across ${plan.accountsAffected} account(s)")
        }
        return when (result) {
            is ImportTransactions.Result.Planned -> describe(result.plan, "Dry run — nothing was changed.") +
                if (result.plan.isEmpty) "" else "\nRe-run with --commit --reason <text> to apply."
            is ImportTransactions.Result.Imported -> describe(result.plan, "Imported (batch ${result.batchId}).")
            is ImportTransactions.Result.AlreadyImported ->
                "This file was already imported at ${result.importedAt} (batch ${result.batchId}); nothing changed."
            ImportTransactions.Result.NothingToImport -> "Every transaction in the file is already imported as is; nothing changed."
            is ImportTransactions.Result.Invalid -> rejected(result.errors)
            is ImportTransactions.Result.Conflicts -> conflicts(result.conflicts)
            ImportTransactions.Result.Forbidden -> throw CommandException(forbidden)
            ImportTransactions.Result.ReasonRequired -> throw CommandException("--commit requires --reason (up to 1000 characters)", 2)
        }
    }

    fun listBalances(args: List<String>): String {
        val o = parseOptions(args, setOf("operator", "hoa"))
        return when (val result = ListBalances(db)(operator(o.required("operator")), hoaId(o.required("hoa")))) {
            is ListBalances.Result.Listed -> buildString {
                result.rows.forEach { r ->
                    val balance = r.balanceCents?.let { "${Money.format(it).padStart(12)}  ${r.transactions} transactions" } ?: "no account mapped"
                    appendLine("${r.unitLabel.padEnd(10)}  ${(r.account ?: "-").padEnd(10)}  $balance")
                }
                append("Total owed across accounts: ${Money.format(result.totalCents)}")
            }
            ListBalances.Result.Forbidden -> throw CommandException(forbidden)
        }
    }

    fun accountStatement(args: List<String>): String {
        val o = parseOptions(args, setOf("operator", "hoa", "unit"))
        return when (val result = AccountStatement(db)(operator(o.required("operator")), hoaId(o.required("hoa")), o.required("unit"))) {
            is AccountStatement.Result.Statement -> buildString {
                appendLine("${result.unitLabel} — account ${result.account}")
                result.lines.forEach { l ->
                    val due = l.dueDate?.let { " due $it" }.orEmpty()
                    val corrected = if (l.corrections > 0) "  (corrected ${l.corrections}x)" else ""
                    appendLine(
                        "  ${l.date}  ${l.externalId.padEnd(14)} ${l.type.code.padEnd(10)} ${Money.format(l.amountCents).padStart(11)} " +
                            "${Money.format(l.runningBalanceCents).padStart(11)}  ${l.description}$due$corrected",
                    )
                }
                append("Balance: ${Money.format(result.balanceCents)} (positive is owed, negative is a credit)")
            }
            AccountStatement.Result.UnitNotFound -> throw CommandException("No unit '${o["unit"]}' in this HOA")
            AccountStatement.Result.NoAccount -> throw CommandException("${o["unit"]} has no account mapped (see import-accounts)")
            AccountStatement.Result.Forbidden -> throw CommandException(forbidden)
        }
    }

    fun reconcileBalances(args: List<String>): String {
        val o = parseOptions(args, setOf("operator", "hoa", "file"))
        val path = file(o.required("file"))
        val result = ReconcileBalances(db)(operator(o.required("operator")), hoaId(o.required("hoa")), path.fileName.toString(), Files.readAllBytes(path))
        return when (result) {
            is ReconcileBalances.Result.Reconciled -> {
                val r = result.report
                val report = buildString {
                    appendLine(if (r.reconciled) "Reconciled: all ${r.matched} balances match." else "NOT reconciled.")
                    if (!r.reconciled) appendLine("  Matching balances: ${r.matched}")
                    r.mismatches.forEach {
                        appendLine("  ${it.account} (${it.unitLabel}): spreadsheet ${Money.format(it.expectedCents)}, ledger ${Money.format(it.actualCents)}")
                    }
                    if (r.unknownAccounts.isNotEmpty()) appendLine("  In the file but not mapped: ${r.unknownAccounts.joinToString()}")
                    if (r.missingAccounts.isNotEmpty()) appendLine("  Mapped but missing from the file: ${r.missingAccounts.joinToString()}")
                    if (r.inconsistentAccounts.isNotEmpty()) {
                        appendLine("  LEDGER INTEGRITY FAILURE (ledger differs from transactions): ${r.inconsistentAccounts.joinToString()}")
                    }
                }.trimEnd()
                if (r.reconciled) report else throw CommandException(report)
            }
            is ReconcileBalances.Result.Invalid -> rejected(result.errors)
            ReconcileBalances.Result.Forbidden -> throw CommandException(forbidden)
        }
    }
}
