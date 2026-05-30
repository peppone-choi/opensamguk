package opensamguk.infra.persistence

import opensamguk.logic.domain.Diplomacy
import kotlin.test.Test
import kotlin.test.assertEquals

class DiplomacyRowMapperTest {

    @Test
    fun `row -- entity -- column-map round trips the directional pair`() {
        val row = linkedMapOf<String, Any?>(
            "src_nation_id" to 1,
            "dest_nation_id" to 2,
            "state_code" to 7,
            "term" to 12,
        )
        val d = DiplomacyRowMapper.fromRow(row)
        assertEquals(1, d.me)
        assertEquals(2, d.you)
        assertEquals(7, d.state)
        assertEquals(12, d.term)

        val cols = DiplomacyRowMapper.toColumns(d)
        assertEquals(1, cols["src_nation_id"])
        assertEquals(2, cols["dest_nation_id"])
        assertEquals(7, cols["state_code"])
        assertEquals(12, cols["term"])
    }

    @Test
    fun `term defaults to zero when the column is absent`() {
        val row = linkedMapOf<String, Any?>(
            "src_nation_id" to 3,
            "dest_nation_id" to 4,
            "state_code" to 0,
        )
        val d = DiplomacyRowMapper.fromRow(row)
        assertEquals(Diplomacy(me = 3, you = 4, state = 0, term = 0), d)
    }
}
