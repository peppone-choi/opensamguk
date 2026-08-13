package opensamguk.gateway.board

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.safety.Safelist
import org.springframework.stereotype.Component

@Component
class GatewayBoardContentSanitizer {
    private val richTextSafelist = Safelist.none()
        .addTags("p", "br", "strong", "b", "em", "i", "s", "ul", "ol", "li", "blockquote", "code", "pre", "h1", "h2", "h3")

    fun toSafeHtml(content: String, format: GatewayBoardContentFormat): String {
        val normalized = content.replace("\r\n", "\n").replace("\r", "\n")
        if (format == GatewayBoardContentFormat.PLAIN_TEXT) return plainTextToSafeHtml(normalized)
        val safeHtml = Jsoup.clean(normalized, "", richTextSafelist, Document.OutputSettings().prettyPrint(false))
        val visibleText = Jsoup.parseBodyFragment(safeHtml).text().replace('\u00A0', ' ')
        require(visibleText.isNotBlank()) { "내용을 입력해주세요." }
        return safeHtml
    }

    private fun plainTextToSafeHtml(content: String): String =
        content
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
            .replace("\n", "<br>")
}
