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
        val hasVisibleContent = visibleText.codePoints().anyMatch { codePoint ->
            !isUnicodeWhiteSpace(codePoint) &&
                !isDefaultIgnorableCodePoint(codePoint)
        }
        require(hasVisibleContent) { "내용을 입력해주세요." }
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

    private fun isUnicodeWhiteSpace(codePoint: Int): Boolean =
        codePoint == 0x0085 || Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)

    // Unicode 15.0 Default_Ignorable_Code_Point ranges; used only for semantic emptiness.
    private fun isDefaultIgnorableCodePoint(codePoint: Int): Boolean =
        when (codePoint) {
            0x00AD, 0x034F, 0x061C, 0x3164, 0xFEFF, 0xFFA0 -> true
            in 0x115F..0x1160,
            in 0x17B4..0x17B5,
            in 0x180B..0x180F,
            in 0x200B..0x200F,
            in 0x202A..0x202E,
            in 0x2060..0x206F,
            in 0xFE00..0xFE0F,
            in 0xFFF0..0xFFF8,
            in 0x1BCA0..0x1BCA3,
            in 0x1D173..0x1D17A,
            in 0xE0000..0xE0FFF,
            -> true
            else -> false
        }
}
