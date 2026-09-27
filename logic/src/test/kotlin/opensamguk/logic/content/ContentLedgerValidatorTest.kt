package opensamguk.logic.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContentLedgerValidatorTest {
    private val valid = """
        {"schemaVersion":1,"rows":[{"id":"office.prefect","grade":"PRIMARY","sources":[
          {"book":"後漢書","volume":"118","section":"百官志","quote":"毎郡置太守一人","grade":"PRIMARY"}],
          "values":{"gameRank":{"value":2,"status":"CONFIRMED","decidedBy":"구현 에이전트","decidedAt":"2026-09-25","basis":"게임 등급"}}}]}
    """.trimIndent()

    @Test
    fun `valid historical row passes`() {
        assertEquals(emptyList(), ContentLedgerValidator.validate(valid))
    }

    @Test
    fun `missing quote grade and decider fail independently`() {
        assertTrue(ContentLedgerValidator.validate(valid.replace("\"quote\":\"毎郡置太守一人\",", "")).any { it.endsWith("quote: required") })
        assertTrue(ContentLedgerValidator.validate(valid.replace("\"grade\":\"PRIMARY\"}],", "\"grade\":\"MIXED\"}],")).any { it.contains("grade: unknown MIXED") })
        assertTrue(ContentLedgerValidator.validate(valid.replace("\"decidedBy\":\"구현 에이전트\",", "")).any { it.contains("decidedBy: required") })
    }

    @Test
    fun `game term needs reason and visible badge`() {
        val gameTerm = """{"schemaVersion":1,"rows":[{"id":"preset.example","grade":"GAME_TERM","sources":[],"gameTermReason":"게임 분류","displayBadge":"게임 용어"}]}"""
        assertEquals(emptyList(), ContentLedgerValidator.validate(gameTerm))
        assertTrue(ContentLedgerValidator.validate(gameTerm.replace("\"displayBadge\":\"게임 용어\",", "").replace(",\"displayBadge\":\"게임 용어\"", "")).any { it.contains("displayBadge") })
    }
}
