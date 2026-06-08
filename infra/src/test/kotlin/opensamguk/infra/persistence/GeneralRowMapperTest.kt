package opensamguk.infra.persistence

import opensamguk.logic.domain.General
import opensamguk.logic.domain.LastTurn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeneralRowMapperTest {

    private fun row(meta: String, lastTurn: String = "{}"): Map<String, Any?> = linkedMapOf(
        "id" to 10,
        "user_id" to null,
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
        "crew" to 500,
        "train" to 80,
        "atmos" to 90,
        "crew_type_id" to 3,
        "troop_id" to 7,
        "horse_code" to "여포의적토마",
        "weapon_code" to "방천화극",
        "book_code" to "None",
        "item_code" to "None",
        "npc_state" to 2,
        "last_turn" to lastTurn,
        "penalty" to "{}",
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
    fun `P2 military and equip surface round trips through the mapper`() {
        val g = GeneralRowMapper.fromRow(row("{}", lastTurn = """{"command":"휴식"}"""))
        assertEquals(500, g.crew)
        assertEquals(80.0, g.train)
        assertEquals(90.0, g.atmos)
        assertEquals(3, g.crewTypeId)
        assertEquals(7, g.troop)
        assertEquals("여포의적토마", g.horse)
        assertEquals("방천화극", g.weapon)
        assertEquals("None", g.book)
        assertEquals("None", g.item)
        assertEquals(2, g.npcType)
        assertEquals(LastTurn("휴식"), g.lastTurn)

        val cols = GeneralRowMapper.toColumns(g)
        assertEquals(500, cols["crew"])
        assertEquals(80, cols["train"])          // Double -> int (integer column)
        assertEquals(90, cols["atmos"])
        assertEquals(3, cols["crew_type_id"])
        assertEquals(7, cols["troop_id"])
        assertEquals("여포의적토마", cols["horse_code"])
        assertEquals("방천화극", cols["weapon_code"])
        assertEquals("None", cols["book_code"])
        assertEquals("None", cols["item_code"])
        assertEquals(2, cols["npc_state"])
    }

    @Test
    fun `possession owner and penalty round trip through dedicated columns`() {
        val r = row("""{"owner_name":"peppone"}""").toMutableMap()
        r["user_id"] = "7"
        r["penalty"] = """{"NoChief":true,"SendPrivateMsgDelay":3}"""

        val g = GeneralRowMapper.fromRow(r)
        assertEquals("7", g.userId)
        assertEquals(true, g.penalty["NoChief"])
        assertEquals(3, g.penalty["SendPrivateMsgDelay"])

        val cols = GeneralRowMapper.toColumns(g)
        assertEquals("7", cols["user_id"])
        assertEquals("""{"NoChief":true,"SendPrivateMsgDelay":3}""", cols["penalty"])
    }

    @Test
    fun `last_turn jsonb round trips delete-on-default and a full 4-field turn`() {
        // default last_turn ({}) materializes to the default 휴식 turn; toRaw emits only command.
        val def = GeneralRowMapper.fromRow(row("{}", lastTurn = "{}"))
        assertEquals(LastTurn("휴식"), def.lastTurn)
        assertEquals("""{"command":"휴식"}""", GeneralRowMapper.toColumns(def)["last_turn"])

        // a 4-field last_turn round-trips with key order command, arg, term, seq.
        val full = GeneralRowMapper.fromRow(
            row("{}", lastTurn = """{"command":"che_이동","arg":{"destCityID":12},"term":3,"seq":1}"""),
        )
        assertEquals("che_이동", full.lastTurn.command)
        assertEquals(mapOf("destCityID" to 12), full.lastTurn.arg)
        assertEquals(3, full.lastTurn.term)
        assertEquals(1, full.lastTurn.seq)
        assertEquals(
            """{"command":"che_이동","arg":{"destCityID":12},"term":3,"seq":1}""",
            GeneralRowMapper.toColumns(full)["last_turn"],
        )
    }

    @Test
    fun `null equip codes materialize as the None sentinel`() {
        val r = row("{}").toMutableMap()
        r["horse_code"] = null
        r["weapon_code"] = null
        val g = GeneralRowMapper.fromRow(r)
        assertEquals("None", g.horse)
        assertEquals("None", g.weapon)
        assertNull(g.meta["nonexistent"])
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
