package opensamguk.logic.input

import kotlin.test.*

class HwihaEnlistmentInputTest {
    @Test fun `valid modes preserve exact target and canonical roundtrip`() {
        for (request in listOf(EnlistmentRequest(7, EnlistmentMode.RANDOM),
            EnlistmentRequest(7, EnlistmentMode.NATION, 1),
            EnlistmentRequest(7, EnlistmentMode.GENERAL, Int.MAX_VALUE))) {
            assertEquals(request, HwihaEnlistmentInput.parse(7, HwihaEnlistmentInput.canonicalJson(request)))
        }
        assertEquals("""{"mode":"RANDOM"}""", HwihaEnlistmentInput.canonicalJson(EnlistmentRequest(7, EnlistmentMode.RANDOM)))
        assertEquals("""{"mode":"NATION","targetId":1}""", HwihaEnlistmentInput.canonicalJson(EnlistmentRequest(7, EnlistmentMode.NATION, 1)))
        assertEquals(EnlistmentRequest(7, EnlistmentMode.GENERAL, 123),
            HwihaEnlistmentInput.parse(7, " \n { \"targetId\" : 123, \"mode\":\"GENERAL\" } \t"))
        assertEquals(EnlistmentRequest(7, EnlistmentMode.RANDOM),
            HwihaEnlistmentInput.parse(7, """{"\u006dode":"RANDOM"}"""))
    }

    @Test fun `duplicates including escaped keys fail instead of last value winning`() {
        for (raw in listOf(
            """{"mode":"RANDOM","mode":"RANDOM"}""",
            """{"mode":"NATION","targetId":1,"targetId":2}""",
            """{"mode":"RANDOM","\u006dode":"RANDOM"}""",
        )) assertNull(HwihaEnlistmentInput.parse(7, raw), raw)
    }

    @Test fun `wrong types extra keys malformed json and missing input fail closed`() {
        for (value in listOf("0", "-1", "2147483648", "1.0", "1e0", "true", "null", "\"1\"", "[]", "{}", "+1", "01")) {
            assertNull(HwihaEnlistmentInput.parse(7, "{\"mode\":\"NATION\",\"targetId\":$value}"), value)
        }
        for (raw in listOf(null, "", "null", "[]", "{}", """{"mode":"NATION"}""",
            """{"mode":"RANDOM","targetId":null}""", """{"mode":"RANDOM","actorId":7}""",
            """{"mode":"RANDOM","cost":1}""", """{"mode":"random"}""",
            """{"mode":true}""", """{"mode":"RANDOM",}""", """{"mode":"RANDOM"}{}""",
            """{"mode":"RANDOM}""", """{"mode":"RAN\qDOM"}""")) {
            assertNull(HwihaEnlistmentInput.parse(7, raw), raw)
        }
        assertNull(HwihaEnlistmentInput.parse(0, """{"mode":"RANDOM"}"""))
    }

    @Test fun `canonical encoder rejects invalid requests`() {
        for (request in listOf(EnlistmentRequest(0, EnlistmentMode.RANDOM),
            EnlistmentRequest(7, EnlistmentMode.RANDOM, 1), EnlistmentRequest(7, EnlistmentMode.NATION),
            EnlistmentRequest(7, EnlistmentMode.GENERAL, -1))) {
            assertFailsWith<IllegalArgumentException> { HwihaEnlistmentInput.canonicalJson(request) }
        }
    }
}
