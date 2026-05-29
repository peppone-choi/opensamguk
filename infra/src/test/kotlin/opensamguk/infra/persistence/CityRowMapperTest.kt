package opensamguk.infra.persistence

import opensamguk.logic.domain.City
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CityRowMapperTest {

    private fun row(meta: String): Map<String, Any?> = linkedMapOf(
        "id" to 5,
        "nation_id" to 2,
        "level" to 5,
        "comm" to 800,
        "comm_max" to 1000,
        "agri" to 700,
        "agri_max" to 1200,
        "supply_state" to 1,
        "front_state" to 0,
        "trust" to 90,
        "meta" to meta,
    )

    @Test
    fun `row -- entity -- column-map round trips scalar columns`() {
        val c = CityRowMapper.fromRow(row("{}"))
        assertEquals(5, c.id)
        assertEquals(2, c.nationId)
        assertEquals(5, c.level)
        assertEquals(800, c.commerce)
        assertEquals(1000, c.commerceMax)
        assertEquals(700, c.agriculture)
        assertEquals(1200, c.agricultureMax)
        assertEquals(1, c.supplyState)
        assertEquals(0, c.frontState)
        assertEquals(90.0, c.trust)

        val cols = CityRowMapper.toColumns(c)
        assertEquals(5, cols["id"])
        assertEquals(2, cols["nation_id"])
        assertEquals(5, cols["level"])
        assertEquals(800, cols["comm"])
        assertEquals(1000, cols["comm_max"])
        assertEquals(700, cols["agri"])
        assertEquals(1200, cols["agri_max"])
        assertEquals(1, cols["supply_state"])
        assertEquals(0, cols["front_state"])
        assertEquals(90, cols["trust"]) // Double -> int (integer-valued trust is lossless)
        assertEquals("{}", cols["meta"])
    }

    @Test
    fun `meta key insertion order preserved through round trip even after overwrite`() {
        val meta = linkedMapOf<String, Any?>(
            "explevel" to 1,
            "intel_exp" to 2,
            "max_domestic_critical" to 3,
        )
        meta["intel_exp"] = 9 // overwrite -> position unchanged
        val c = City(
            id = 5, nationId = 2, level = 5,
            commerce = 800, commerceMax = 1000,
            agriculture = 700, agricultureMax = 1200,
            supplyState = 1, frontState = 0, trust = 90.0,
            meta = meta,
        )
        val encoded = CityRowMapper.toColumns(c)["meta"] as String
        assertEquals("""{"explevel":1,"intel_exp":9,"max_domestic_critical":3}""", encoded)

        val reparsed = CityRowMapper.fromRow(row(encoded))
        assertEquals(listOf("explevel", "intel_exp", "max_domestic_critical"), reparsed.meta.keys.toList())
        val reEncoded = CityRowMapper.toColumns(reparsed)["meta"] as String
        assertEquals(encoded, reEncoded)
    }

    @Test
    fun `integer jsonb values serialize as 5 not 5_0`() {
        val c = City(
            id = 5, nationId = 2, level = 5,
            commerce = 800, commerceMax = 1000,
            agriculture = 700, agricultureMax = 1200,
            supplyState = 1, frontState = 0, trust = 90.0,
            meta = linkedMapOf("region" to 5),
        )
        val encoded = CityRowMapper.toColumns(c)["meta"] as String
        assertTrue(encoded.contains("\"region\":5"), "expected 5 not 5.0, got: $encoded")
        assertTrue(!encoded.contains("5.0"), "must not emit trailing .0: $encoded")
    }

    @Test
    fun `meta encoder keeps Korean literal and slashes unescaped (PHP Json encode parity)`() {
        // JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES: Korean stays literal, '/' not escaped.
        val c = City(
            id = 5, nationId = 2, level = 5,
            commerce = 800, commerceMax = 1000,
            agriculture = 700, agricultureMax = 1200,
            supplyState = 1, frontState = 0, trust = 90.0,
            meta = linkedMapOf("note" to "성도/촉"),
        )
        val encoded = CityRowMapper.toColumns(c)["meta"] as String
        assertEquals("""{"note":"성도/촉"}""", encoded)
    }
}
