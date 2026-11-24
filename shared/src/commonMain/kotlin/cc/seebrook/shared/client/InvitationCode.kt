package cc.seebrook.shared.client

/**
 * Reads an invitation code from what the owner typed or pasted: the code itself or the whole
 * link from the invitation email (`…/invite#token=<code>`). A format check only (ED-17, X-01):
 * whether the invitation is valid is decided by the server.
 */
object InvitationCode {
    /** 256 random bits, URL-safe Base64 without padding: the format the server issues (ED-29). */
    private val CODE = Regex("^[A-Za-z0-9_-]{43}$")

    /** Whether [code] has the format of an invitation code. The server checks with this too (X-01). */
    fun isWellFormed(code: String): Boolean = CODE.matches(code)

    /** The code, or null when [input] does not contain one in the expected format. */
    fun fromInput(input: String): String? {
        val text = input.trim()
        val candidate = text.substringAfter("token=", text).takeWhile { !it.isWhitespace() && it != '&' }
        return candidate.takeIf(::isWellFormed)
    }
}
