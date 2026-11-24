package cc.seebrook.shared.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InvitationCodeTest {
    private val code = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde"

    @Test
    fun readsTheCodeOrTheWholeLink() {
        assertEquals(43, code.length)
        assertEquals(code, InvitationCode.fromInput(code))
        assertEquals(code, InvitationCode.fromInput("  $code\n"))
        assertEquals(code, InvitationCode.fromInput("https://seebrook.cc/invite#token=$code"))
        assertEquals(code, InvitationCode.fromInput("https://seebrook.cc/invite#token=$code&x=1"))
        assertEquals(code, InvitationCode.fromInput("open this link: https://seebrook.cc/invite#token=$code thanks"))
    }

    @Test
    fun refusesAnythingElse() {
        listOf("", "hello", code.dropLast(1), code + "x", code.replace('A', '+'), "https://seebrook.cc/invite").forEach {
            assertNull(InvitationCode.fromInput(it), it)
        }
    }
}
