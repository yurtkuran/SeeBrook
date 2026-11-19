package cc.seebrook.app.cli

import cc.seebrook.finance.ConfigurePayments
import cc.seebrook.finance.ExportPayments
import cc.seebrook.finance.RegisterPaymentRows
import cc.seebrook.finance.FeeRule
import cc.seebrook.finance.GetPaymentSettings
import cc.seebrook.finance.ListHoaPayments
import cc.seebrook.finance.Money
import cc.seebrook.finance.PaymentSettings
import cc.seebrook.identity.ResolveOperator
import cc.seebrook.platform.auth.Permission
import cc.seebrook.platform.auth.UserActor
import cc.seebrook.platform.db.Database
import cc.seebrook.platform.payments.PaymentMethod
import cc.seebrook.platform.payments.PaymentProviders
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
            appendLine("Exported ${rows.size} received payment(s) from $from to $to to $out")
            if (rows.isNotEmpty()) {
                appendLine("  Dues total: ${Money.format(rows.sumOf { it.duesCents })}; fees paid by owners: ${Money.format(rows.sumOf { it.feeCents })}")
            }
            append("Paste the rows into the register with Paste Special > Skip blanks, so its formula columns are kept.")
        }
    }

    private fun describe(s: PaymentSettings): String = buildString {
        append("${s.provider} merchant ${s.merchantRef}")
        if (s.suspended) append(" (SUSPENDED)")
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
