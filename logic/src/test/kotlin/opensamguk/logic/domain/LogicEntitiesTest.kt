package opensamguk.logic.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FD0 — the P2 entity shape. Round-trips a fully-populated General/City/Nation and pins the
 * meta-resident exp/level accessors (leadership_exp/strength_exp/intel_exp/explevel/dedlevel ride
 * the `meta` jsonb, NOT dedicated columns).
 */
class LogicEntitiesTest {

    @Test
    fun `General carries the P2 military, equip, npc and last-turn surface`() {
        val g = General(
            id = 76, nationId = 2, cityId = 5,
            leadership = 31, strength = 68, intel = 49, injury = 0,
            experience = 3030.0, dedication = 1200.0, officerLevel = 1,
            gold = 5000, rice = 1000,
            crew = 1200, train = 70.0, atmos = 65.0, crewTypeId = 3, troop = 76,
            horse = "적토마", weapon = "방천화극", book = "손자병법", item = "옥새",
            npcType = 2,
            lastTurn = LastTurn("상업 투자"),
            meta = linkedMapOf(
                "explevel" to 5,
                "intel_exp" to 240.0,
                "leadership_exp" to 180.0,
                "strength_exp" to 300.0,
                "dedlevel" to 7,
                "aux" to linkedMapOf<String, Any?>("inheritBuff" to 3),
            ),
        )
        assertEquals(1200, g.crew)
        assertEquals(70.0, g.train)
        assertEquals(65.0, g.atmos)
        assertEquals(3, g.crewTypeId)
        assertEquals(76, g.troop)
        assertEquals("적토마", g.horse)
        assertEquals("방천화극", g.weapon)
        assertEquals("손자병법", g.book)
        assertEquals("옥새", g.item)
        assertEquals(2, g.npcType)
        assertEquals("상업 투자", g.lastTurn.command)
        // meta-resident exp/level accessors
        assertEquals(180.0, g.leadershipExp())
        assertEquals(300.0, g.strengthExp())
        assertEquals(240.0, g.intelExp())
        assertEquals(5, g.explevel())
        assertEquals(7, g.dedlevel())
        assertEquals(3, g.auxVar("inheritBuff"))
        assertNull(g.auxVar("missing"))
    }

    @Test
    fun `General P2 fields default to PHP-faithful values (None equip, npc 0, default last-turn)`() {
        val g = General(
            id = 1, nationId = 0, cityId = 0,
            leadership = 50, strength = 50, intel = 50, injury = 0,
            experience = 0.0, dedication = 0.0, officerLevel = 0,
            gold = 1000, rice = 1000,
        )
        assertEquals(0, g.crew)
        assertEquals(0.0, g.train)
        assertEquals("None", g.horse)
        assertEquals("None", g.weapon)
        assertEquals("None", g.book)
        assertEquals("None", g.item)
        assertEquals(0, g.npcType)
        assertEquals("휴식", g.lastTurn.command)
        assertEquals(0.0, g.leadershipExp())
        assertEquals(0, g.dedlevel())
        assertNull(g.auxVar("anything"))
    }

    @Test
    fun `City carries secu, def, wall, pop (+each max), trade and region`() {
        val c = City(
            id = 5, nationId = 2, level = 5,
            commerce = 800, commerceMax = 1000,
            agriculture = 700, agricultureMax = 900,
            supplyState = 1, frontState = 0, trust = 80.0,
            security = 400, securityMax = 500,
            defense = 600, defenseMax = 800,
            wall = 1200, wallMax = 1500,
            population = 50000, populationMax = 80000,
            trade = 102, region = 3,
        )
        assertEquals(400, c.security); assertEquals(500, c.securityMax)
        assertEquals(600, c.defense); assertEquals(800, c.defenseMax)
        assertEquals(1200, c.wall); assertEquals(1500, c.wallMax)
        assertEquals(50000, c.population); assertEquals(80000, c.populationMax)
        assertEquals(102, c.trade)
        assertEquals(3, c.region)
    }

    @Test
    fun `City trade is nullable (no-trade city) and P2 fields default to zero`() {
        val c = City(
            id = 1, nationId = 0, level = 1,
            commerce = 0, commerceMax = 0,
            agriculture = 0, agricultureMax = 0,
            supplyState = 1, frontState = 0, trust = 0.0,
        )
        assertNull(c.trade)
        assertEquals(0, c.security)
        assertEquals(0, c.region)
    }

    @Test
    fun `Nation carries the full shape name color typeCode gold rice tech gennum capset`() {
        val n = Nation(
            id = 2, level = 4, capitalCityId = 5,
            name = "촉", color = "#00aa00", typeCode = "che_명가",
            gold = 12000, rice = 8000, tech = 3500.0,
            gennum = 12, capset = 4,
            meta = linkedMapOf("rate" to 20, "bill" to 100),
        )
        assertEquals("촉", n.name)
        assertEquals("#00aa00", n.color)
        assertEquals("che_명가", n.typeCode)
        assertEquals(12000, n.gold)
        assertEquals(8000, n.rice)
        assertEquals(3500.0, n.tech)
        assertEquals(12, n.gennum)
        assertEquals(4, n.capset)
        assertEquals(20, metaInt(n.meta, "rate"))
    }

    @Test
    fun `Nation defaults to neutral type and zero economy`() {
        val n = Nation(id = 0, level = 0, capitalCityId = null)
        assertEquals("che_중립", n.typeCode)
        assertEquals(0, n.gold)
        assertEquals(0.0, n.tech)
        assertTrue(n.name.isEmpty())
    }

    @Test
    fun `Diplomacy is a directional me-you-state-term pair`() {
        val d = Diplomacy(me = 2, you = 3, state = 7, term = 12)
        assertEquals(2, d.me)
        assertEquals(3, d.you)
        assertEquals(7, d.state)
        assertEquals(12, d.term)
        assertEquals(0, Diplomacy(me = 1, you = 2, state = 0).term)
    }

    @Test
    fun `NationTurn keys on nation officerLevel turnIdx with a brief defaulting to empty`() {
        val t = NationTurn(nationId = 2, officerLevel = 12, turnIdx = 0, action = "휴식", brief = "휴식")
        assertEquals(2, t.nationId)
        assertEquals(12, t.officerLevel)
        assertEquals(0, t.turnIdx)
        assertEquals("휴식", t.action)
        assertEquals("휴식", t.brief)
        assertNull(t.arg)
        assertEquals("", NationTurn(nationId = 1, officerLevel = 1, turnIdx = 1, action = "휴식").brief)
    }
}
