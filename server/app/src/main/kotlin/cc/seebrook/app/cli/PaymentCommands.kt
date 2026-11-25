package cc.seebrook.app.cli

import cc.seebrook.finance.ConfigurePayments
import cc.seebrook.finance.ConfigureRecurringSchedule
import cc.seebrook.finance.Frequency
import cc.seebrook.finance.ListRecurringPlans
import cc.seebrook.finance.RecurringSchedule
import cc.seebrook.finance.RunRecurringCharges
import cc.seebrook.finance.ExportPayments
import cc.seebrook.finance.RegisterPaymentRows
import cc.seebrook.finance.FeeRule
import cc.seebrook.finance.GetPaymentSettings
import cc.seebrook.finance.ListHoaPayments
import cc.seebrook.finance.Money
import cc.seebrook.finance.MonitorPayments
import cc.seebrook.finance.PayoutReport
import cc.seebrook.finance.PaymentSettings
import cc.seebrook.identity.ResolveOperator
import cc.seebrook.platform.auth.Permission
import cc.seebrook.platform.auth.UserActor
import cc.seebrook.platform.db.Database
import cc.seebrook.platform.payments.PaymentMethod
import cc.seebrook.platform.payments.PaymentProviders
import cc.seebrook.platform.payments.SettlementKind
import cc.seebrook.property.Properties
import cc.seebrook.tenant.FindHoa
import java.math.BigDecimal
import java.nio.file.Files
import java.time.LocalDate
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID

/** Payment admin commands (ED-43): thin adapters over the payment actions. */
class PaymentCommands(private val db: Database, private val providers: PaymentProviders) {
    private fun operator(email: String): UserActor = when (val result = ResolveOperator(db)(email)) {
        is ResolveOperator.Result.Active -> result.actor
        ResolveOperator.Result.NotFound -> throw CommandException("Operator '$email' is not a user")
        ResolveOperator.Result.Disabled -> throw CommandException("Operator '$email' is disabled")
    }

    private fun hoaId(slug: String): UUID = FindHoa(db).bySlug(slug)?.id ?: throw CommandException("No HOA with slug '$slug'")

    private fun Map<String, String>.required(name: String) =
        this[name]?.takeIf { it.isNotBlank() } ?: throw CommandException("Missing --$name", 2)

    fun configure(args: List<String>): String {
        val o = parseOptions(
            args.filter { it != "--commit" },
            setOf("operator", "hoa", "provider", "merchant", "card-fee", "ach-fee", "suspended", "reason"),
        )
        val methods = buildMap {
            parseFee(o.required("card-fee"))?.let { put(PaymentMethod.CARD, it) }
            parseFee(o.required("ach-fee"))?.let { put(PaymentMethod.ACH, it) }
        }
        val suspended = o["suspended"]?.let { it.toBooleanStrictOrNull() ?: throw CommandException("--suspended must be true or false", 2) } ?: false
        val change = ConfigurePayments.Change(o.required("provider"), o.required("merchant"), methods, suspended)
        val commit = "--commit" in args
        return when (val result = ConfigurePayments(db, providers)(operator(o.required("operator")), hoaId(o.required("hoa")), change, commit, o["reason"])) {
            is ConfigurePayments.Result.Planned -> buildString {
                appendLine(if (result.committed) "Payment settings saved." else "Dry run — nothing was changed.")
                appendLine("  Before: ${result.before?.let(::describe) ?: "not set up"}")
                append("  After:  ${describe(result.after)}")
                if (!result.committed) append("\nRe-run with --commit --reason <text> to apply.")
            }
            is ConfigurePayments.Result.Invalid -> throw CommandException(result.message)
            ConfigurePayments.Result.Forbidden -> throw CommandException("Operator may not manage finances in this HOA")
        }
    }

    fun settings(args: List<String>): String {
        val o = parseOptions(args, setOf("operator", "hoa"))
        val actor = operator(o.required("operator"))
        val hoa = hoaId(o.required("hoa"))
        if (!actor.can(Permission.MANAGE_FINANCES, hoa)) throw CommandException("Operator may not manage finances in this HOA")
        return GetPaymentSettings(db)(actor, hoa)?.let(::describe) ?: "Payments are not set up for this HOA."
    }

    fun list(args: List<String>): String {
        val o = parseOptions(args, setOf("operator", "hoa", "unit"))
        val hoa = hoaId(o.required("hoa"))
        val units = db.transaction { tx -> Properties.byUnitLabel(tx, hoa) }
        val propertyId = o["unit"]?.let { units[it]?.id ?: throw CommandException("No unit '$it' in this HOA") }
        val labels = units.entries.associate { (label, p) -> p.id to label }
        return when (val result = ListHoaPayments(db)(operator(o.required("operator")), hoa, propertyId)) {
            is ListHoaPayments.Result.Listed -> if (result.payments.isEmpty()) "No payments." else result.payments.joinToString("\n") {
                listOf(
                    it.createdAt.toString().take(19).replace('T', ' '), it.reference, labels[it.propertyId].orEmpty().padEnd(8),
                    it.method.code.padEnd(4), Money.format(it.duesCents).padStart(11), "fee " + Money.format(it.feeCents).padStart(7),
                    it.status.code + (it.failureReason?.let { r -> " ($r)" } ?: ""),
                ).joinToString("  ")
            }
            ListHoaPayments.Result.Forbidden -> throw CommandException("Operator may not manage finances in this HOA")
        }
    }

    /** Received payments as register rows (ED-44). Refuses to overwrite a file. */
    fun export(args: List<String>): String {
        val o = parseOptions(args, setOf("operator", "hoa", "from", "to", "out", "first-id", "category-id"))
        fun date(name: String) = o.required(name).let { runCatching { LocalDate.parse(it) }.getOrNull() ?: throw CommandException("--$name '$it' is not a date (YYYY-MM-DD)", 2) }
        val from = date("from")
        val to = date("to")
        val out = Path.of(o.required("out"))
        if (Files.exists(out)) throw CommandException("$out already exists; choose another file name")
        if (out.parent != null && !Files.isDirectory(out.parent)) throw CommandException("No folder ${out.parent}")
        val firstId = o["first-id"]?.also { if (!Regex("^.{0,20}\\d{1,10}$").matches(it)) throw CommandException("--first-id must end in a number, e.g. 2026-00391", 2) }
        val rows = when (val result = ExportPayments(db)(operator(o.required("operator")), hoaId(o.required("hoa")), from, to)) {
            is ExportPayments.Result.Exported -> result.rows
            ExportPayments.Result.InvalidRange -> throw CommandException("--to must be on or after --from, and within a year of it", 2)
            ExportPayments.Result.Forbidden -> throw CommandException("Operator may not manage finances in this HOA")
        }
        Files.write(out, RegisterPaymentRows.csv(rows, o["category-id"] ?: DEFAULT_DUES_CATEGORY_ID, firstId), StandardOpenOption.CREATE_NEW)
        return buildString {
            val (received, back) = rows.partition { it.amountCents > 0 }
            appendLine("Exported ${received.size} received payment(s) and ${back.size} refund(s) or return(s) from $from to $to to $out")
            if (rows.isNotEmpty()) {
                appendLine("  Net to units: ${Money.format(rows.sumOf { it.amountCents })}; fees paid by owners: ${Money.format(rows.sumOf { it.feeCents })}")
            }
            append("Paste the rows into the register with Paste Special > Skip blanks, so its formula columns are kept.")
        }
    }

    /** What each bank deposit from the provider is made of (ED-47); optionally every line as CSV. */
    fun payoutReport(args: List<String>): String {
        val o = parseOptions(args, setOf("operator", "hoa", "from", "to", "out"))
        fun date(name: String) = o.required(name).let { runCatching { LocalDate.parse(it) }.getOrNull() ?: throw CommandException("--$name '$it' is not a date (YYYY-MM-DD)", 2) }
        val from = date("from")
        val to = date("to")
        val out = o["out"]?.let { Path.of(it) }?.also {
            if (Files.exists(it)) throw CommandException("$it already exists; choose another file name")
            if (it.parent != null && !Files.isDirectory(it.parent)) throw CommandException("No folder ${it.parent}")
        }
        val report = when (val r = PayoutReport(db, providers)(operator(o.required("operator")), hoaId(o.required("hoa")), from, to)) {
            is PayoutReport.Result.Report -> r
            PayoutReport.Result.Forbidden -> throw CommandException("Operator may not manage finances in this HOA")
            PayoutReport.Result.InvalidRange -> throw CommandException("--to must be on or after --from, and within a year of it", 2)
            PayoutReport.Result.NotConfigured -> throw CommandException("Payments are not set up for this HOA on this server")
            PayoutReport.Result.ProviderUnavailable -> throw CommandException("The payment provider could not be reached; try again later")
            is PayoutReport.Result.ProviderRefused -> throw CommandException("The payment provider refused: ${r.message}")
        }
        out?.let { Files.write(it, PayoutReport.csv(report.payouts), StandardOpenOption.CREATE_NEW) }
        return describePayouts(report.payouts, from, to) + (out?.let { "\nEvery line written to $it." } ?: "")
    }

    private fun describePayouts(payouts: List<PayoutReport.Payout>, from: LocalDate, to: LocalDate): String = buildString {
        if (payouts.isEmpty()) {
            append("No payouts arriving from $from to $to.")
            return@buildString
        }
        fun row(label: String, cents: Long) = appendLine("  ${label.padEnd(30)}${Money.format(cents).padStart(13)}")
        payouts.forEach { p ->
            appendLine("Payout ${p.payoutId}, arriving ${p.arrivalDate} (${p.status.name.lowercase().replace('_', ' ')}): ${Money.format(p.depositCents)} to the bank")
            val payments = p.lines.count { it.kind == SettlementKind.PAYMENT }
            row("Payments received ($payments)", p.paymentsCents)
            if (p.refundsCents != 0L) row("Refunds and returns", p.refundsCents)
            row("Provider fees", p.providerFeesCents)
            if (p.otherCents != 0L) row("Other", p.otherCents)
            row("Net deposit", p.netCents)
            appendLine("  Of the payments: dues ${Money.format(p.duesCents)}, fees paid by owners ${Money.format(p.ownerFeesCents)}.")
            appendLine(
                when {
                    p.processingCostCents > 0 -> "  Processing cost to the HOA: ${Money.format(p.processingCostCents)} (provider fees less owners' fees)."
                    p.processingCostCents < 0 -> "  Owners' fees exceeded provider fees by ${Money.format(-p.processingCostCents)}."
                    else -> "  Owners' fees covered the provider fees exactly."
                },
            )
            val problems = p.problems
            if (problems.isEmpty()) appendLine("  Every line matches Seebrook.") else problems.forEach { appendLine("  CHECK: $it") }
            appendLine()
        }
        val problems = payouts.sumOf { it.problems.size }
        append(
            "${payouts.size} payout(s), ${Money.format(payouts.sumOf { it.depositCents })} to the bank; " +
                "provider fees ${Money.format(-payouts.sumOf { it.providerFeesCents })}; " +
                if (problems == 0) "everything matches." else "$problems item(s) to check.",
        )
    }

    /** One monitoring pass now (ED-46); the server runs the same pass every few minutes. A system action: no operator. */
    /** `recurring-schedule` (ED-59): when an HOA's recurring plans charge and which frequencies owners may choose. */
    fun recurringSchedule(args: List<String>): String {
        val o = parseOptions(args, setOf("operator", "hoa", "day", "anchor-month", "frequencies", "reason"))
        val day = o.required("day").toIntOrNull() ?: throw CommandException("--day must be a number from 1 to 28", 2)
        val anchor = o["anchor-month"]?.let { it.toIntOrNull() ?: throw CommandException("--anchor-month must be a number from 1 to 12", 2) } ?: 1
        val frequencies = o.required("frequencies").split(',').map { code ->
            Frequency.fromCode(code) ?: throw CommandException("Unknown frequency '$code' (monthly, quarterly, annual)", 2)
        }.toSet()
        return when (val result = ConfigureRecurringSchedule(db)(operator(o.required("operator")), hoaId(o.required("hoa")), day, anchor, frequencies, o["reason"])) {
            is ConfigureRecurringSchedule.Result.Set -> buildString {
                appendLine("Recurring schedule saved.")
                appendLine("  Before: ${result.before?.let(::describe) ?: "not offered"}")
                append("  After:  ${describe(result.after)}")
            }
            is ConfigureRecurringSchedule.Result.Invalid -> throw CommandException(result.message)
            ConfigureRecurringSchedule.Result.Forbidden -> throw CommandException("Operator may not manage finances in this HOA")
        }
    }

    private fun describe(s: RecurringSchedule): String {
        val month = java.time.Month.of(s.anchorMonth).getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.US)
        return s.frequencies.sortedBy { it.months }.joinToString("; ") { f ->
            when (f) {
                Frequency.MONTHLY -> "monthly on day ${s.dayOfMonth}"
                Frequency.QUARTERLY -> "quarterly on day ${s.dayOfMonth} from $month"
                Frequency.ANNUAL -> "annually on $month ${s.dayOfMonth}"
            }
        } + " (weekends and bank holidays: next business day)"
    }

    fun listRecurring(args: List<String>): String {
        val all = "--all" in args
        val o = parseOptions(args.filter { it != "--all" }, setOf("operator", "hoa"))
        return when (val result = ListRecurringPlans(db)(operator(o.required("operator")), hoaId(o.required("hoa")), includeCanceled = all)) {
            is ListRecurringPlans.Result.Listed -> result.rows.joinToString("\n") { (p, unit) ->
                val next = p.nextChargeDate?.let { " next $it" }.orEmpty()
                val reason = p.statusReason?.let { " ($it)" }.orEmpty()
                "$unit  ${p.frequency.code}  ${Money.format(p.duesCents)} + ${Money.format(p.feeCents)} fee  ${p.method.label}  ${p.status.code}$reason$next"
            }.ifEmpty { "No recurring plans" }
            ListRecurringPlans.Result.Forbidden -> throw CommandException("Operator may not manage finances in this HOA")
        }
    }

    fun runRecurring(args: List<String>): String {
        if (args.isNotEmpty()) throw CommandException("run-recurring-charges takes no options", 2)
        if (providers.names.isEmpty()) return "Payments are disabled; nothing to charge."
        val s = RunRecurringCharges(db, providers)()
        return "Recurring charges: ${s.started} started, ${s.submitted} submitted to the provider, ${s.skipped} skipped, " +
            "${s.waiting} waiting for paused payments, ${s.canceled} plan(s) canceled (owner no longer owns the unit)."
    }

    fun reconcile(args: List<String>): String {
        if (args.isNotEmpty()) throw CommandException("reconcile-payments takes no options", 2)
        if (providers.names.isEmpty()) return "Payments are disabled; nothing to reconcile."
        val s = MonitorPayments(db, providers)()
        return buildString {
            append("Checked ${s.checked} unsettled payment(s) with the provider; ${s.applied} updated")
            if (s.mismatched > 0) append("; ${s.mismatched} did not match and need investigation (see the log)")
            if (s.providerErrors > 0) append("; ${s.providerErrors} lookup(s) failed")
            append(".")
            if (s.outagesStarted > 0) append("\nPayments paused for ${s.outagesStarted} HOA(s): the provider is not answering.")
            if (s.resumed > 0) append("\nPayments resumed for ${s.resumed} HOA(s).")
            if (s.paused > 0) append("\n${s.paused} HOA(s) remain paused until the provider answers.")
        }
    }

    private fun describe(s: PaymentSettings): String = buildString {
        append("${s.provider} merchant ${s.merchantRef}")
        if (s.suspended) append(" (SUSPENDED)")
        s.outageSince?.let { append(" (PAUSED: provider unreachable since ${it.toString().take(19).replace('T', ' ')} UTC; resumes automatically)") }
        PaymentMethod.entries.forEach { m ->
            append("; ${m.code}: ")
            append(s.methods[m]?.let(::describeFee) ?: "not accepted")
        }
    }

    companion object {
        /** The register's `ID` code for HOA dues (its budget category list). */
        const val DEFAULT_DUES_CATEGORY_ID = "101"

        /**
         * `off` (method not accepted), `0` (accepted; the HOA absorbs the fee), `3%`, `1.50`
         * (dollars) or `2.9%+0.30`.
         */
        fun parseFee(spec: String): FeeRule? {
            val s = spec.trim().lowercase()
            if (s == "off") return null
            val bad = CommandException("Fee '$spec' must be off, 0, a percentage (3%), dollars (1.50) or both (2.9%+0.30)", 2)
            var bps = 0
            var fixed = 0L
            for (part in s.split('+').map { it.trim() }) {
                if (part.endsWith("%")) {
                    val pct = part.removeSuffix("%").toBigDecimalOrNull() ?: throw bad
                    val value = pct.movePointRight(2)
                    if (value.stripTrailingZeros().scale() > 0) throw bad
                    bps += value.toInt()
                } else {
                    fixed += Money.parseCents(part.removePrefix("$")) ?: throw bad
                }
            }
            return try {
                FeeRule(bps, fixed)
            } catch (e: IllegalArgumentException) {
                throw CommandException("Fee '$spec': ${e.message}", 2)
            }
        }

        fun describeFee(f: FeeRule): String = when {
            f.absorbed -> "accepted, HOA absorbs the fee"
            else -> "owner pays " + listOfNotNull(
                f.basisPoints.takeIf { it > 0 }?.let { BigDecimal(it).movePointLeft(2).stripTrailingZeros().toPlainString() + "%" },
                f.fixedCents.takeIf { it > 0 }?.let(Money::format),
            ).joinToString(" + ")
        }
    }
}
