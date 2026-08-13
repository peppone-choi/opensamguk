package opensamguk.gateway.board

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class GatewayBoardContentSanitizerTest {
    private val sanitizer = GatewayBoardContentSanitizer()

    @ParameterizedTest
    @ValueSource(
        strings = [
            "<p>&#x034f;</p>",
            "<p>&#x2060;</p>",
            "<p>&#x3164;</p>",
            "<p>&#xfe0f;</p>",
            "<p>&#xfeff;</p>",
            "<p>&#xe0100;</p>",
        ],
    )
    fun `rich text containing only default ignorable code points is rejected`(content: String) {
        assertThrows(IllegalArgumentException::class.java) {
            sanitizer.toSafeHtml(content, GatewayBoardContentFormat.RICH_HTML)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["<p>&#x2007;</p>", "<p>&#x202f;</p>"])
    fun `rich text containing only Unicode space separators is rejected`(content: String) {
        assertThrows(IllegalArgumentException::class.java) {
            sanitizer.toSafeHtml(content, GatewayBoardContentFormat.RICH_HTML)
        }
    }

    @Test
    fun `visible emoji with default ignorable code points is retained`() {
        val content = "<p>❤️ 👩‍💻</p>"

        val safeHtml = sanitizer.toSafeHtml(content, GatewayBoardContentFormat.RICH_HTML)

        assertEquals(content, safeHtml)
    }
}
