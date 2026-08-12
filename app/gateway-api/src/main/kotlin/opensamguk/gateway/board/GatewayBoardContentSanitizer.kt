package opensamguk.gateway.board

import org.springframework.stereotype.Component

@Component
class GatewayBoardContentSanitizer {
    fun plainTextToSafeHtml(content: String): String =
        content
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace("\n", "<br>")
}
