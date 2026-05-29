package opensamguk.infra.persistence

import opensamguk.logic.domain.General
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeneralRowMapperTest {

    private fun row(meta: String): Map<String, Any?> = linkedMapOf(
        "id" to 10,
        "nation_id" to 2,
        "city_id" to 5,
        "leadership" to 70,
        "strength" to 65,
        "intel" to 80,
        "injury" to 0,
        "experience" to 1234,
        "dedication" to 567,
        "officer_level" to 4,
        "gold" to 1500,
        "rice" to 1200,
        "meta" to meta,
    )

    @Test
    fun `row -- entity -- column-map round trips scalar columns`() {
        val g = GeneralRowMapper.fromRow(row("""{"explevel":5,"intel_exp":3,"max_domestic_critical":2}"""))
        assertEquals(10, g.id)
        assertEquals(2, g.nationId)
        assertEquals(5, g.cityId)
        assertEquals(70, g.leadership)
        assertEquals(65, g.strength)
        assertEquals(80, g.intel)
        assertEquals(0, g.injury)
        assertEquals(1234.0, g.experience)
        assertEquals(567.0, g.dedication)
        assertEquals(4, g.officerLevel)
        assertEquals(1500, g.gold)
        assertEquals(1200, g.rice)

        val cols = GeneralRowMapper.toColumns(g)
        assertEquals(10, cols["id"])
        assertEquals(2, cols["nation_id"])
        assertEquals(5, cols["city_id"])
        assertEquals(70, cols["leadership"])
        assertEquals(65, cols["strength"])
        assertEquals(80, cols["intel"])
        assertEquals(0, cols["injury"])
        assertEquals(1234, cols["experience"])
        assertEquals(567, cols["dedication"])
        assertEquals(4, cols["officer_level"])
        assertEquals(1500, cols["gold"])
        assertEquals(1200, cols["rice"])
    }

    @Test
    fun `experience and dedication round half-away-from-zero at flush (float -- integer column store)`() {
        // PHP stores the raw float into the `integer` column; Postgres ROUNDS (not truncates).
        // The G2 golden proves it: 3030 + 44*0.7 = 3060.8 → 3061, 3030 + 64*0.7 = 3074.8 → 3075.
        val g = General(
            id = 1, nationId = 0, cityId = 0,
            leadership = 50, strength = 50, intel = 50, injury = 0,
            experience = 1234.99, dedication = 567.5,
            officerLevel = 0, gold = 1000, rice = 1000,
        )
        val cols = GeneralRowMapper.toColumns(g)
        assertEquals(1235, cols["experience"], "1234.99 rounds up to 1235 (not truncated to 1234)")
        assertEquals(568, cols["dedication"], "567.5 rounds half-away-from-zero to 568")
    }

    @Test
    fun `experience and dedication round the G2 golden fractional accumulators`() {
        // The exact float→int cases the G1/G2 golden pins (commerce_normal / agri_normal).
        val g = General(
            id = 1, nationId = 0, cityId = 0,
            leadership = 50, strength = 50, intel = 50, injury = 0,
            experience = 3060.8, dedication = 3074.8,
            officerLevel = 0, gold = 1000, rice = 1000,
        )
        val cols = GeneralRowMapper.toColumns(g)
        assertEquals(3061, cols["experience"], "3060.8 → 3061 (golden commerce_normal experience)")
        assertEquals(3075, cols["dedication"], "3074.8 → 3075 (golden agri_normal experience)")
    }

    @Test
    fun `meta key insertion order preserved through round trip even after overwrite`() {
        // insert keys in this order, then overwrite intel_exp -- order must be unchanged.
        val meta = linkedMapOf<String, Any?>(
            "explevel" to 1,
            "intel_exp" to 2,
            "max_domestic_critical" to 3,
        )
        meta["intel_exp"] = 9 // overwrite existing key -> position unchanged
        val g = General(
            id = 1, nationId = 0, cityId = 0,
            leadership = 50, strength = 50, intel = 50, injury = 0,
            experience = 0.0, dedication = 0.0,
            officerLevel = 0, gold = 1000, rice = 1000,
            meta = meta,
        )
        val encoded = GeneralRowMapper.toColumns(g)["meta"] as String
        assertEquals("""{"explevel":1,"intel_exp":9,"max_domestic_critical":3}""", encoded)

        // decode -> re-encode keeps the same order
        val reparsed = GeneralRowMapper.fromRow(row(encoded))
        assertEquals(listOf("explevel", "intel_exp", "max_domestic_critical"), reparsed.meta.keys.toList())
        val reEncoded = GeneralRowMapper.toColumns(reparsed)["meta"] as String
        assertEquals(encoded, reEncoded)
    }

    @Test
    fun `integer jsonb values serialize as 5 not 5_0`() {
        val meta = linkedMapOf<String, Any?>("explevel" to 5)
        val g = General(
            id = 1, nationId = 0, cityId = 0,
            leadership = 50, strength = 50, intel = 50, injury = 0,
            experience = 0.0, dedication = 0.0,
            officerLevel = 0, gold = 1000, rice = 1000,
            meta = meta,
        )
        val encoded = GeneralRowMapper.toColumns(g)["meta"] as String
        assertTrue(encoded.contains("\"explevel\":5"), "expected 5 not 5.0, got: $encoded")
        assertTrue(!encoded.contains("5.0"), "must not emit trailing .0: $encoded")
    }
}
