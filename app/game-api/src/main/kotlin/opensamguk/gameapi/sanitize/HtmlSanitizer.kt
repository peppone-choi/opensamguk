package opensamguk.gameapi.sanitize

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.safety.Safelist

object HtmlSanitizer {

    data class RawLengthViolation(
        val field: String,
        val maxCodePoints: Int,
    )

    private val richTextFieldsByCommand = mapOf(
        "boardArticle" to setOf("text"),
        "boardComment" to setOf("text"),
        "sendMessage" to setOf("text"),
        "diploSendLetter" to setOf("brief", "detail", "textBrief", "textDetail"),
        "setNotice" to setOf("msg"),
        "setScoutMsg" to setOf("msg"),
    )

    private val rawLengthLimitsByCommand = mapOf(
        "setNotice" to mapOf("msg" to 16384),
        "setScoutMsg" to mapOf("msg" to 1000),
    )

    private val safelist = Safelist.none()
        .addTags("b", "strong", "i", "em", "s", "u", "span", "br", "p")
        .addAttributes("span", "style")

    private val colorStyle = Regex("""^\s*color\s*:\s*(#[0-9a-fA-F]{3,8}|[a-zA-Z]{1,20})\s*;?\s*$""")
    private val htmlTag = Regex("""</?[a-zA-Z][^>]*>""")

    fun sanitizeRequestJson(commandCode: String, argJson: String, objectMapper: ObjectMapper): String {
        val fields = richTextFieldsByCommand[commandCode] ?: return argJson
        val root = runCatching { objectMapper.readTree(argJson) as? ObjectNode }.getOrNull() ?: return argJson
        var changed = false

        fields.forEach { field ->
            val value = root.get(field)
            if (value?.isTextual == true) {
                val original = value.asText()
                if (!htmlTag.containsMatchIn(original)) return@forEach
                val cleaned = sanitize(original)
                if (cleaned != original) {
                    root.put(field, cleaned)
                    changed = true
                }
            }
        }

        return if (changed) objectMapper.writeValueAsString(root) else argJson
    }

    fun rawLengthViolation(commandCode: String, argJson: String, objectMapper: ObjectMapper): RawLengthViolation? {
        val limits = rawLengthLimitsByCommand[commandCode] ?: return null
        val root = runCatching { objectMapper.readTree(argJson) as? ObjectNode }.getOrNull() ?: return null

        for ((field, maxCodePoints) in limits) {
            val value = root.get(field)
            if (value?.isTextual == true) {
                val text = value.asText()
                if (text.codePointCount(0, text.length) > maxCodePoints) {
                    return RawLengthViolation(field, maxCodePoints)
                }
            }
        }
        return null
    }

    fun sanitize(html: String): String {
        val outputSettings = Document.OutputSettings().prettyPrint(false)
        val cleaned = Jsoup.clean(html, "", safelist, outputSettings)
        val document = Jsoup.parseBodyFragment(cleaned)
        document.outputSettings().prettyPrint(false)
        document.select("[style]").forEach(::retainColorStyleOnly)
        return document.body().html()
    }

    private fun retainColorStyleOnly(element: Element) {
        val match = colorStyle.matchEntire(element.attr("style"))
        if (element.tagName() != "span" || match == null) {
            element.removeAttr("style")
            return
        }
        element.attr("style", "color: ${match.groupValues[1]}")
    }
}
