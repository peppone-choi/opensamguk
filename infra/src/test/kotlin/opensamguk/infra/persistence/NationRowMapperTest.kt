package opensamguk.infra.persistence

import opensamguk.logic.domain.Nation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NationRowMapperTest {

    private fun row(meta: String, capital: Any? = 5): Map<String, Any?> = linkedMapOf(
        "id" to 1,
        "name" to "위",
        "color" to "#0000ff",
        "capital_city_id" to capital,
        "gold" to 12000,
        "rice" to 9000,
        "tech" to 1234.5,
        "level" to 7,
        "type_code" to "che_명사",
        "meta" to meta,
    )

    @Test
    fun `row -- entity -- column-map round trips scalar columns`() {
        val n = NationRowMapper.fromRow(row("{}"))
        assertEquals(1, n.id)
        assertEquals("위", n.name)
        assertEquals("#0000ff", n.color)
        assertEquals(5, n.capitalCityId)
        assertEquals(12000, n.gold)
        assertEquals(9000, n.rice)
        assertEquals(1234.5, n.tech)
        assertEquals(7, n.level)
        assertEquals("che_명사", n.typeCode)

        val cols = NationRowMapper.toColumns(n)
        assertEquals(1, cols["id"])
        assertEquals("위", cols["name"])
        assertEquals("#0000ff", cols["color"])
        assertEquals(5, cols["capital_city_id"])
        assertEquals(12000, cols["gold"])
        assertEquals(9000, cols["rice"])
        assertEquals(1234.5, cols["tech"])
        assertEquals(7, cols["level"])
        assertEquals("che_명사", cols["type_code"])
    }

    @Test
    fun `gennum and capset are read FROM meta (no dedicated columns)`() {
        val n = NationRowMapper.fromRow(row("""{"gennum":24,"capset":3,"rate":15}"""))
        assertEquals(24, n.gennum)
        assertEquals(3, n.capset)
        // meta carries them as the source of truth
        assertEquals(24, (n.meta["gennum"] as Number).toInt())
        assertEquals(3, (n.meta["capset"] as Number).toInt())
        assertEquals(15, (n.meta["rate"] as Number).toInt())
    }

    @Test
    fun `meta jsonb insertion order preserved + integers serialize as 5 not 5_0`() {
        val meta = linkedMapOf<String, Any?>(
            "gennum" to 24,
            "capset" to 3,
            "rate" to 15,
            "aux" to linkedMapOf<String, Any?>("foo" to "bar"),
        )
        val n = Nation(
            id = 1, level = 7, capitalCityId = 5, name = "위", color = "#00f",
            typeCode = "che_명사", gold = 1, rice = 1, tech = 0.0,
            gennum = 24, capset = 3, meta = meta,
        )
        val encoded = NationRowMapper.toColumns(n)["meta"] as String
        assertEquals("""{"gennum":24,"capset":3,"rate":15,"aux":{"foo":"bar"}}""", encoded)
        assertTrue(!encoded.contains("24.0"), "ints must not be 24.0: $encoded")

        // decode -> re-encode keeps the same order
        val reparsed = NationRowMapper.fromRow(row(encoded))
        assertEquals(listOf("gennum", "capset", "rate", "aux"), reparsed.meta.keys.toList())
        assertEquals(encoded, NationRowMapper.toColumns(reparsed)["meta"])
    }

    @Test
    fun `capital_city_id is nullable -- a wandering nation has null capital`() {
        val n = NationRowMapper.fromRow(row("{}", capital = null))
        assertNull(n.capitalCityId)
        assertNull(NationRowMapper.toColumns(n)["capital_city_id"])
    }
}
