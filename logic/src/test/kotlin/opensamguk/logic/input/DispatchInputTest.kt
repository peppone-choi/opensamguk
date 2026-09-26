package opensamguk.logic.input

import kotlin.test.*

class DispatchInputTest {
    @Test fun `dispatch accepts positive integer boundaries and canonical roundtrip`() {
        val request = DispatchRequest(7, 1, Int.MAX_VALUE)
        assertEquals("""{"targetGeneralId":1,"countyId":2147483647}""", DispatchInput.canonicalJson(request))
        assertEquals(request, DispatchInput.parse(7, DispatchInput.canonicalJson(request)))
        assertEquals(DispatchRequest(7, 2, 3), DispatchInput.parse(7,
            """ { "countyId":3, "\u0074argetGeneralId":2 } """))
    }

    @Test fun `dispatch rejects numeric coercion missing extra and duplicate fields`() {
        for (value in listOf("0", "-1", "2147483648", "1.0", "1e0", "+1", "01", "true", "null", "\"1\"", "[]", "{}")) {
            assertNull(DispatchInput.parse(7, "{\"targetGeneralId\":$value,\"countyId\":1}"), value)
            assertNull(DispatchInput.parse(7, "{\"targetGeneralId\":1,\"countyId\":$value}"), value)
        }
        for (raw in listOf(
            """{"targetGeneralId":1}""", """{"countyId":1}""",
            """{"targetGeneralId":1,"countyId":2,"actorId":7}""",
            """{"targetGeneralId":1,"countyId":2,"countyId":2}""",
            """{"targetGeneralId":1,"countyId":2,"\u0063ountyId":3}""",
            """{"targetGeneralId":1,"targetGeneralId":2,"countyId":2}""",
        )) assertNull(DispatchInput.parse(7, raw), raw)
    }

    @Test fun `reply accepts only bounded ASCII identifier and literal booleans`() {
        for (id in listOf("a", "A0._:-", "a".repeat(128))) for (accept in listOf(true, false)) {
            val request = DispatchReplyRequest(7, id, accept)
            assertEquals(request, DispatchReplyInput.parse(7, DispatchReplyInput.canonicalJson(request)))
        }
        assertEquals(DispatchReplyRequest(7, "a:1", false),
            DispatchReplyInput.parse(7, """{"accept":false,"dispatch\u0049d":"a:1"}"""))
        for (id in listOf("", " ", "a b", "a/b", "한", "é", "a".repeat(129))) {
            assertNull(DispatchReplyInput.parse(7, "{\"dispatchId\":\"$id\",\"accept\":true}"), id)
        }
        for (value in listOf("\"true\"", "\"false\"", "True", "FALSE", "1", "0", "null", "[]", "{}")) {
            assertNull(DispatchReplyInput.parse(7, "{\"dispatchId\":\"a\",\"accept\":$value}"), value)
        }
    }

    @Test fun `reply rejects duplicate escaped keys extra authority and nonstring IDs`() {
        for (raw in listOf(
            """{"dispatchId":"a","accept":true,"accept":false}""",
            """{"dispatchId":"a","accept":true,"\u0061ccept":true}""",
            """{"dispatchId":"a","dispatchId":"b","accept":true}""",
            """{"dispatchId":1,"accept":true}""", """{"dispatchId":null,"accept":true}""",
            """{"dispatchId":"a"}""", """{"accept":true}""",
            """{"dispatchId":"a","accept":true,"actorId":7}""",
        )) assertNull(DispatchReplyInput.parse(7, raw), raw)
    }

    @Test fun `both parsers reject malformed roots trailing data and invalid actor`() {
        for (raw in listOf(null, "", "null", "[]", "{}", "true", "{", "{\"x\":\"a\\q\"}")) {
            assertNull(DispatchInput.parse(7, raw))
            assertNull(DispatchReplyInput.parse(7, raw))
        }
        val dispatch = """{"targetGeneralId":1,"countyId":2}"""
        val reply = """{"dispatchId":"a","accept":true}"""
        for (suffix in listOf("{}", "true", ",", "\u0000")) {
            assertNull(DispatchInput.parse(7, dispatch + suffix))
            assertNull(DispatchReplyInput.parse(7, reply + suffix))
        }
        for (actor in listOf(0, -1)) {
            assertNull(DispatchInput.parse(actor, dispatch))
            assertNull(DispatchReplyInput.parse(actor, reply))
        }
        assertNull(DispatchInput.parse(7, dispatch.dropLast(1) + ",}"))
        assertNull(DispatchReplyInput.parse(7, reply.dropLast(1) + ",}"))
    }

    @Test fun `canonical encoders reject invalid constructed requests`() {
        for (request in listOf(DispatchRequest(0, 1, 1), DispatchRequest(1, 0, 1), DispatchRequest(1, 1, -1)))
            assertFailsWith<IllegalArgumentException> { DispatchInput.canonicalJson(request) }
        for (request in listOf(DispatchReplyRequest(0, "a", true), DispatchReplyRequest(1, "", false),
            DispatchReplyRequest(1, "a".repeat(129), true), DispatchReplyRequest(1, "한", true)))
            assertFailsWith<IllegalArgumentException> { DispatchReplyInput.canonicalJson(request) }
    }
}
