package cc.seebrook.app.cli

import cc.seebrook.app.support.Fixtures
import cc.seebrook.app.support.MemoryStorage
import cc.seebrook.app.support.TestPdfs
import cc.seebrook.app.support.TestPostgres
import cc.seebrook.db.generated.identity.Tables.USER_ACCOUNT
import cc.seebrook.platform.db.Database
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class AdminCommandsTest {
    private val db: Database = TestPostgres.freshDatabase()
    private val commands = AdminCommands(db)

    @AfterEach
    fun close() = db.close()

    @Test
    fun `options are parsed strictly`() {
        parseOptions(listOf("--user", "a@example.test", "--role", "hoa_admin"), setOf("user", "role")) shouldBe
            mapOf("user" to "a@example.test", "role" to "hoa_admin")
        shouldThrow<CommandException> { parseOptions(listOf("--bogus", "x"), setOf("user")) }.exitCode shouldBe 2
        shouldThrow<CommandException> { parseOptions(listOf("--user"), setOf("user")) }.exitCode shouldBe 2
        shouldThrow<CommandException> { parseOptions(listOf("--user", "--role"), setOf("user", "role")) }
        shouldThrow<CommandException> { parseOptions(listOf("--user", "a", "--user", "b"), setOf("user")) }
        shouldThrow<CommandException> { parseOptions(listOf("stray"), setOf("user")) }
    }

    @Test
    fun `operator bootstraps Root, then grants and revokes by HOA slug`() {
        Fixtures.hoa(db, "channel-harbor")
        db.dsl.insertInto(USER_ACCOUNT).set(USER_ACCOUNT.EMAIL, "board@example.test").execute()

        commands.bootstrapRoot(listOf("--email", "root@example.test")) shouldContain "Root granted"
        val grant = listOf(
            "--operator", "root@example.test", "--user", "board@example.test",
            "--role", "hoa_admin", "--hoa", "channel-harbor", "--reason", "Elected treasurer",
        )
        commands.grantRole(grant) shouldContain "Granted hoa_admin"
        commands.grantRole(grant) shouldContain "nothing changed"
        commands.revokeRole(grant) shouldContain "Revoked hoa_admin"
    }

    @Test
    fun `unknown operator, HOA or role is refused`() {
        Fixtures.hoa(db, "channel-harbor")
        commands.bootstrapRoot(listOf("--email", "root@example.test"))
        fun grant(operator: String, hoa: String, role: String) = commands.grantRole(
            listOf("--operator", operator, "--user", "root@example.test", "--role", role, "--hoa", hoa, "--reason", "r"),
        )

        shouldThrow<CommandException> { grant("nobody@example.test", "channel-harbor", "hoa_admin") }
            .message shouldContain "not a user"
        shouldThrow<CommandException> { grant("root@example.test", "no-such-hoa", "hoa_admin") }
            .message shouldContain "No HOA"
        shouldThrow<CommandException> { grant("root@example.test", "channel-harbor", "janitor") }.exitCode shouldBe 2
        shouldThrow<CommandException> { commands.bootstrapRoot(listOf("--email", "x@example.test")) }
            .message shouldContain "already exists"
    }

    @Test
    fun `invitation and transfer commands`() {
        Fixtures.hoa(db, "channel-harbor")
        commands.bootstrapRoot(listOf("--email", "root@example.test"))
        val file = kotlin.io.path.createTempFile(suffix = ".csv").toFile().apply {
            writeText(
                "unit_label,address_line1,address_line2,city,state_code,postal_code,owner_email,owner_name\n" +
                    "Unit 1,1 Harbor Dr,,Ocean Isle Beach,NC,28469,owner1@example.test,\n",
            )
            deleteOnExit()
        }
        val who = listOf("--operator", "root@example.test", "--hoa", "channel-harbor")
        commands.importOwners(who + listOf("--file", file.path, "--commit", "--reason", "Roster"))

        commands.sendInvitations(who) shouldContain "Dry run"
        commands.sendInvitations(who + "--commit") shouldContain "Queued 1 invitation email;"
        commands.listInvitations(who) shouldContain "Unit 1  owner1@example.test  queued"
        val one = who + listOf("--unit", "Unit 1", "--email", "owner1@example.test")
        commands.resendInvitation(one) shouldContain "already queued"
        shouldThrow<CommandException> { commands.revokeInvitation(one) }.exitCode shouldBe 2
        commands.revokeInvitation(one + listOf("--reason", "Sold")) shouldContain "Revoked"
        commands.listInvitations(who) shouldContain "revoked"
        shouldThrow<CommandException> { commands.resendInvitation(one) }.message shouldContain "No pending invitation"

        val transfer = who + listOf("--unit", "Unit 1", "--to", "buyer@example.test, co-buyer@example.test")
        commands.transferOwnership(transfer) shouldContain "Dry run"
        shouldThrow<CommandException> { commands.transferOwnership(transfer + "--commit") }.exitCode shouldBe 2
        commands.transferOwnership(transfer + listOf("--commit", "--reason", "Sale")) shouldContain
            "New owners invited (sent separately): buyer@example.test, co-buyer@example.test"
        commands.transferOwnership(transfer + listOf("--commit", "--reason", "Sale")) shouldContain "nothing changed"
    }

    @Test
    fun `document commands create a category, upload a PDF and list it`() {
        Fixtures.hoa(db, "channel-harbor")
        commands.bootstrapRoot(listOf("--email", "root@example.test"))
        val docs = AdminCommands(db, MemoryStorage(), 1024 * 1024)
        val who = listOf("--operator", "root@example.test", "--hoa", "channel-harbor")
        val pdf = kotlin.io.path.createTempFile(suffix = ".pdf").toFile().apply { writeBytes(TestPdfs.pdf(pages = 3)); deleteOnExit() }
        val notPdf = kotlin.io.path.createTempFile(suffix = ".pdf").toFile().apply { writeText("hello"); deleteOnExit() }

        docs.createCategory(who + listOf("--name", "Minutes")) shouldContain "Created category 'Minutes'"
        docs.listCategories(who) shouldBe "Minutes"
        val upload = who + listOf("--category", "minutes", "--title", "March meeting", "--description", "Board minutes")
        docs.uploadDocument(upload + listOf("--file", pdf.path, "--tags", "board,2026", "--document-date", "2026-03-10")) shouldContain
            "Uploaded (3 pages)"
        shouldThrow<CommandException> { docs.uploadDocument(upload + listOf("--file", notPdf.path)) }.message shouldContain "not a PDF"
        shouldThrow<CommandException> { docs.uploadDocument(upload + listOf("--file", pdf.path, "--document-date", "March")) }
            .exitCode shouldBe 2
        docs.listDocuments(who) shouldBe "Minutes  March meeting  processing"
        shouldThrow<CommandException> { commands.uploadDocument(upload + listOf("--file", pdf.path)) }
            .message shouldContain "storage is not configured"
    }
}
