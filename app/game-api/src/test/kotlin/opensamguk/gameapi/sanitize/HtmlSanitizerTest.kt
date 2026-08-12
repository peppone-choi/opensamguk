package opensamguk.gameapi.sanitize

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class HtmlSanitizerTest {

    private val objectMapper = ObjectMapper()

    @Test
    fun `removes active markup and preserves the allowlisted rich text`() {
        val raw = """{"msg":"<p><strong>천하</strong><span style=\"color: #2e7d32\">통일</span><img src=x onerror=alert(1)><script>alert(1)</script></p>"}"""

        val sanitized = HtmlSanitizer.sanitizeRequestJson("setNotice", raw, objectMapper)
        val richText = objectMapper.readTree(sanitized).path("msg").asText()

        assertEquals("<p><strong>천하</strong><span style=\"color: #2e7d32\">통일</span></p>", richText)
        assertFalse(richText.contains("script"))
        assertFalse(richText.contains("onerror"))
        assertFalse(richText.contains("alert("))
    }

    @Test
    fun `keeps color only styles and strips unsafe style declarations`() {
        val raw = """{"msg":"<p><span style=\"color: red\">허용</span><span style=\"color: #2e7d32; background: url(javascript:alert(1))\">제거</span></p>"}"""

        val sanitized = HtmlSanitizer.sanitizeRequestJson("setScoutMsg", raw, objectMapper)
        val document = org.jsoup.Jsoup.parseBodyFragment(objectMapper.readTree(sanitized).path("msg").asText())
        val spans = document.select("span")

        assertEquals("color: red", spans[0].attr("style"))
        assertFalse(spans[1].hasAttr("style"))
    }

    @Test
    fun `sanitizes each current rich text intake field`() {
        val unsafe = "<p onclick=alert(1)>안전<script>alert(1)</script></p>"
        val expected = "<p>안전</p>"
        val fields = linkedMapOf(
            "boardArticle" to listOf("text"),
            "boardComment" to listOf("text"),
            "sendMessage" to listOf("text"),
            "diploSendLetter" to listOf("brief", "detail"),
            "setNotice" to listOf("msg"),
            "setScoutMsg" to listOf("msg"),
        )

        fields.forEach { (commandCode, names) ->
            val raw = objectMapper.writeValueAsString(names.associateWith { unsafe })
            val sanitized = objectMapper.readTree(HtmlSanitizer.sanitizeRequestJson(commandCode, raw, objectMapper))

            names.forEach { field -> assertEquals(expected, sanitized.path(field).asText(), "$commandCode.$field") }
        }
    }

    @Test
    fun `leaves plain text and non rich text commands byte unchanged`() {
        val plainText = """{"text":"첫 줄\\n둘째 줄"}"""
        val ordinaryTextWithEntities = objectMapper.writeValueAsString(mapOf("msg" to "천하 & 평화 < 안전 >"))
        val nonRichText = """{"msg":"<script>alert(1)</script>"}"""

        assertEquals(plainText, HtmlSanitizer.sanitizeRequestJson("sendMessage", plainText, objectMapper))
        assertEquals(ordinaryTextWithEntities, HtmlSanitizer.sanitizeRequestJson("setNotice", ordinaryTextWithEntities, objectMapper))
        assertEquals(nonRichText, HtmlSanitizer.sanitizeRequestJson("setRate", nonRichText, objectMapper))
    }

    @Test
    fun `leaves malformed request bodies untouched`() {
        val malformed = "{msg:"

        assertEquals(malformed, HtmlSanitizer.sanitizeRequestJson("setNotice", malformed, objectMapper))
        assertNull(objectMapper.readTree("{} ").get("msg"))
    }

    @Test
    fun `counts raw rich text limits in Unicode code points rather than UTF 16 units`() {
        val withinLimit = objectMapper.writeValueAsString(mapOf("msg" to "😀".repeat(1000)))
        val overLimit = objectMapper.writeValueAsString(mapOf("msg" to "😀".repeat(1001)))

        assertNull(HtmlSanitizer.rawLengthViolation("setScoutMsg", withinLimit, objectMapper))
        val violation = assertNotNull(HtmlSanitizer.rawLengthViolation("setScoutMsg", overLimit, objectMapper))
        assertEquals("msg", violation.field)
        assertEquals(1000, violation.maxCodePoints)
    }
}
