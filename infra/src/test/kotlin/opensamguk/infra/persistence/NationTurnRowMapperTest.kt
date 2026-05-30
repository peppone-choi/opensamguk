package opensamguk.infra.persistence

import opensamguk.logic.domain.NationTurn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NationTurnRowMapperTest {

    @Test
    fun `row -- entity -- column-map round trips with arg jsonb + brief text`() {
        val row = linkedMapOf<String, Any?>(
            "nation_id" to 1,
            "officer_level" to 12,
            "turn_idx" to 0,
            "action_code" to "che_거병",
            "arg" to """{"destCityID":12,"destGeneralID":34}""",
            "brief" to "휴식",
        )
        val t = NationTurnRowMapper.fromRow(row)
        assertEquals(1, t.nationId)
        assertEquals(12, t.officerLevel)
        assertEquals(0, t.turnIdx)
        assertEquals("che_거병", t.action)
        assertEquals(mapOf("destCityID" to 12, "destGeneralID" to 34), t.arg)
        assertEquals("휴식", t.brief)

        val cols = NationTurnRowMapper.toColumns(t)
        assertEquals(1, cols["nation_id"])
        assertEquals(12, cols["officer_level"])
        assertEquals(0, cols["turn_idx"])
        assertEquals("che_거병", cols["action_code"])
        // arg renders to byte-comparable jsonb; brief is a plain text column (NOT jsonb)
        assertEquals("""{"destCityID":12,"destGeneralID":34}""", cols["arg"])
        assertEquals("휴식", cols["brief"])
    }

    @Test
    fun `empty arg jsonb materializes to null and binds back as empty object`() {
        val row = linkedMapOf<String, Any?>(
            "nation_id" to 2,
            "officer_level" to 5,
            "turn_idx" to 3,
            "action_code" to "휴식",
            "arg" to "{}",
            "brief" to "",
        )
        val t = NationTurnRowMapper.fromRow(row)
        assertNull(t.arg)
        assertEquals("", t.brief)
        assertEquals("{}", NationTurnRowMapper.toColumns(t)["arg"])
    }

    @Test
    fun `brief defaults to empty when the column is null`() {
        val row = linkedMapOf<String, Any?>(
            "nation_id" to 2,
            "officer_level" to 5,
            "turn_idx" to 3,
            "action_code" to "휴식",
            "arg" to null,
            "brief" to null,
        )
        val t = NationTurnRowMapper.fromRow(row)
        assertEquals(NationTurn(nationId = 2, officerLevel = 5, turnIdx = 3, action = "휴식", arg = null, brief = ""), t)
    }
}
