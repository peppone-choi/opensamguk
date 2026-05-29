package opensamguk.common.golden

import kotlinx.serialization.json.*
import opensamguk.common.josa.JosaUtil
import opensamguk.common.log.LogFormat
import opensamguk.common.log.convertLog
import opensamguk.common.log.formatLogText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JosaLogGoldenTest {
    private fun load(p: String): JsonArray =
        Json.parseToJsonElement(this::class.java.getResource(p)!!.readText()).jsonArray

    @Test
    fun `josa pick golden`() {
        for (c in load("/golden/josa/pick.json")) {
            val o = c.jsonObject
            val text = o["text"]?.jsonPrimitive?.contentOrNull
            val josa = o["josa"]!!.jsonPrimitive.content
            if (o["throws"]?.jsonPrimitive?.booleanOrNull == true) {
                val ex = assertFailsWith<IllegalArgumentException> { JosaUtil.pick(text, josa) }
                assertEquals(o["message"]!!.jsonPrimitive.content, ex.message)
            } else {
                assertEquals(o["expected"]!!.jsonPrimitive.content, JosaUtil.pick(text, josa), "pick($text,$josa)")
            }
        }
    }

    @Test
    fun `convertLog golden`() {
        for (c in load("/golden/log/convertLog.json")) {
            val o = c.jsonObject
            assertEquals(o["expected"]!!.jsonPrimitive.content, convertLog(o["value"]!!.jsonPrimitive.content, o["type"]!!.jsonPrimitive.int))
        }
    }

    @Test
    fun `formatLogText golden`() {
        for (c in load("/golden/log/formatLogText.json")) {
            val o = c.jsonObject
            assertEquals(o["expected"]!!.jsonPrimitive.content, formatLogText("본문", LogFormat.fromCode(o["format"]!!.jsonPrimitive.int), 190, 3))
        }
    }

    @Test
    fun `render e2e golden`() {
        for (c in load("/golden/log/render-e2e.json")) {
            val o = c.jsonObject
            assertEquals(o["expected"]!!.jsonPrimitive.content, convertLog(formatLogText(o["raw"]!!.jsonPrimitive.content, LogFormat.fromCode(o["format"]!!.jsonPrimitive.int), 190, 3)))
        }
    }
}
