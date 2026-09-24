package opensamguk.logic.input

import kotlin.test.*

class HwihaRetireInputTest {
    @Test fun `retirement requires one chosen successor id`() {
        val request = HwihaRetireInput.parse(7, """{"successorGeneralId":8}""")!!
        assertEquals(HwihaRetireRequest(7, 8), request)
        assertEquals("""{"successorGeneralId":8}""", HwihaRetireInput.canonicalJson(request))
        for (raw in listOf("{}", """{"successorGeneralId":7}""", """{"successorGeneralId":"8"}""",
            """{"successorGeneralId":8,"successorGeneralId":9}""", """{"successorGeneralId":8} tail"""))
            assertNull(HwihaRetireInput.parse(7, raw), raw)
    }
}
