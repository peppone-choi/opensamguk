package opensamguk.logic.actions.military

import opensamguk.common.constants.CityConst
import opensamguk.common.josa.JosaUtil
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.WorldEnv
import opensamguk.logic.domain.metaDouble
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.world.CalcCityDistance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Port-faithful resolve tests for che_이동 + che_집합 (the first multi-row general UPDATE shape).
 *
 * Fixture cities (CityConst golden): 업(1) ↔ 남피(9) are bidirectionally adjacent. JosaUtil.pick("남피","로")="로".
 *  - 이동 atmos -5 floor 20; roaming-leader (officer 12, wandering nation 0) moves ALL same-nation generals
 *    + per-target PLAIN logs; gold -= develcost.
 *  - 집합 troop multi-general city UPDATE + per-target PLAIN logs (troopName embedded).
 *  - multi-dirty general lands in draft.cascadeGenerals.
 */
class MoveAndGatherTest {

    private val pipeline = GeneralActionPipeline()
    private val MONTH = 3
    private val date = "12:34"

    private val CITY_EOP = 1   // 업
    private val CITY_NAMPI = 9 // 남피 (adjacent to 업)

    private fun general(
        id: Int = 42, nationId: Int = 1, cityId: Int = CITY_EOP,
        officerLevel: Int = 1, atmos: Double = 50.0, gold: Int = 1000, troop: Int = 42,
    ) = General(
        id = id, nationId = nationId, cityId = cityId,
        leadership = 70, strength = 70, intel = 70, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = officerLevel,
        gold = gold, rice = 1000, crew = 1000, atmos = atmos, troop = troop,
        meta = linkedMapOf("leadership_exp" to 0.0),
    )

    private fun city(id: Int = CITY_EOP, nationId: Int = 1) = City(
        id = id, nationId = nationId, level = 5,
        commerce = 1000, commerceMax = 20000, agriculture = 1000, agricultureMax = 20000,
        supplyState = 1, frontState = 0, trust = 50.0, population = 100000, populationMax = 200000,
    )

    private val env = WorldEnv(year = 190, startYear = 184, develCost = 120)

    private fun rng(cls: String) = RandUtil(LiteHashDrbg(serializeSeed("0".repeat(32), "generalCommand", 190, MONTH, 42, cls)))

    // === 이동 ===

    @Test
    fun `이동 key name category and args schema`() {
        val a = CheIdong(pipeline)
        assertEquals("che_이동", a.key); assertEquals("이동", a.name); assertEquals("인사", a.category)
        assertTrue(a.argsSchema.containsKey("destCityID"))
    }

    @Test
    fun `이동 single general moves city atmos floor gold cost exp`() {
        val nation = Nation(id = 1, level = 7, capitalCityId = 99)
        val d = GeneralActionDraft(general(officerLevel = 1, atmos = 50.0, gold = 1000), city(CITY_EOP), nation)
        d.destCity = city(CITY_NAMPI)
        val c = GeneralActionResolveContext(d, rng("che_이동"), env, MONTH, date,
            args = linkedMapOf("destCityID" to CITY_NAMPI))
        CheIdong(pipeline).resolve(c)

        assertEquals(CITY_NAMPI, d.general.cityId, "moved to dest city")
        assertEquals(45.0, d.general.atmos, 1e-9, "atmos -5 = 45 (above floor 20)")
        assertEquals(1000 - 120, d.general.gold, "gold -= develcost 120")
        assertEquals(50.0, d.general.experience, 1e-9)
        assertEquals(1.0, metaDouble(d.general.meta, "leadership_exp"), 1e-9)
        // no followers moved (officer not roaming-leader)
        assertTrue(d.cascadeGenerals.isEmpty(), "no cascade for a normal general")
    }

    @Test
    fun `이동 atmos floors at 20`() {
        val nation = Nation(id = 1, level = 7, capitalCityId = 99)
        val d = GeneralActionDraft(general(atmos = 22.0), city(CITY_EOP), nation)
        d.destCity = city(CITY_NAMPI)
        CheIdong(pipeline).resolve(GeneralActionResolveContext(d, rng("che_이동"), env, MONTH, date,
            args = linkedMapOf("destCityID" to CITY_NAMPI)))
        // 22 - 5 = 17 → floor 20
        assertEquals(20.0, d.general.atmos, 1e-9)
    }

    @Test
    fun `이동 log byte-exact (남피 josa 로)`() {
        val nation = Nation(id = 1, level = 7, capitalCityId = 99)
        val d = GeneralActionDraft(general(), city(CITY_EOP), nation)
        d.destCity = city(CITY_NAMPI)
        val c = GeneralActionResolveContext(d, rng("che_이동"), env, MONTH, date,
            args = linkedMapOf("destCityID" to CITY_NAMPI))
        CheIdong(pipeline).resolve(c)
        val josaRo = JosaUtil.pick("남피", "로")
        assertEquals("<C>●</>${MONTH}월:<G><b>남피</b></>${josaRo} 이동했습니다. <1>$date</>", c.logs()[0])
    }

    @Test
    fun `이동 roaming-leader moves ALL same-nation generals with per-target PLAIN logs`() {
        // officer_level 12 + nation.level 0 (wandering) → roaming leader.
        val nation = Nation(id = 1, level = 0, capitalCityId = null)
        val leader = general(id = 42, officerLevel = 12, cityId = CITY_EOP)
        val f1 = general(id = 7, nationId = 1, cityId = CITY_EOP)
        val f2 = general(id = 8, nationId = 1, cityId = CITY_EOP)
        val other = general(id = 9, nationId = 2, cityId = CITY_EOP)   // different nation — NOT moved
        val d = GeneralActionDraft(leader, city(CITY_EOP), nation)
        d.destCity = city(CITY_NAMPI)
        val c = GeneralActionResolveContext(d, rng("che_이동"), env, MONTH, date,
            args = linkedMapOf("destCityID" to CITY_NAMPI),
            candidateGenerals = listOf(f1, f2, other))
        CheIdong(pipeline).resolve(c)

        assertEquals(CITY_NAMPI, d.general.cityId, "leader moved")
        // both same-nation followers moved; the other-nation general untouched.
        assertEquals(2, d.cascadeGenerals.size, "2 followers moved")
        assertTrue(d.cascadeGenerals.all { it.cityId == CITY_NAMPI }, "followers in dest city")
        assertEquals(setOf(7, 8), d.cascadeGenerals.map { it.id }.toSet())
        // per-target PLAIN logs (no month prefix; PLAIN format <C>●</>{text})
        val josaRo = JosaUtil.pick("남피", "로")
        val expected = "<C>●</>방랑군 세력이 <G><b>남피</b></>${josaRo} 이동했습니다."
        assertEquals(listOf(expected), c.logsTo(7))
        assertEquals(listOf(expected), c.logsTo(8))
        assertEquals(emptyList(), c.logsTo(9))
    }

    // === 집합 ===

    @Test
    fun `집합 key name category`() {
        val a = CheJiphap(pipeline)
        assertEquals("che_집합", a.key); assertEquals("집합", a.name); assertEquals("군사", a.category)
    }

    @Test
    fun `집합 gathers troop members to the leader city with per-target PLAIN logs`() {
        val nation = Nation(id = 1, level = 7, capitalCityId = 99)
        val leader = general(id = 42, nationId = 1, cityId = CITY_EOP, troop = 42)
        // members: same nation, different city, troop == leader.id (42), not the leader
        val m1 = general(id = 7, nationId = 1, cityId = CITY_NAMPI, troop = 42)
        val m2 = general(id = 8, nationId = 1, cityId = CITY_NAMPI, troop = 42)
        val sameCity = general(id = 9, nationId = 1, cityId = CITY_EOP, troop = 42)   // already in city → skip
        val otherTroop = general(id = 10, nationId = 1, cityId = CITY_NAMPI, troop = 99) // diff troop → skip
        val d = GeneralActionDraft(leader, city(CITY_EOP), nation)
        val c = GeneralActionResolveContext(d, rng("che_집합"), env, MONTH, date,
            candidateGenerals = listOf(m1, m2, sameCity, otherTroop),
            troopName = "선봉대")
        CheJiphap(pipeline).resolve(c)

        assertEquals(2, d.cascadeGenerals.size, "only m1, m2 gathered")
        assertEquals(setOf(7, 8), d.cascadeGenerals.map { it.id }.toSet())
        assertTrue(d.cascadeGenerals.all { it.cityId == CITY_EOP }, "members moved to leader city")
        // leader exp/ded/leadership_exp
        assertEquals(70.0, d.general.experience, 1e-9)
        assertEquals(100.0, d.general.dedication, 1e-9)
        assertEquals(1.0, metaDouble(d.general.meta, "leadership_exp"), 1e-9)
        // per-target PLAIN logs embed troopName + city + josa
        val expected = "<C>●</>선봉대 부대원들은 <G><b>업</b></>으로 집합되었습니다."
        assertEquals(listOf(expected), c.logsTo(7))
        assertEquals(listOf(expected), c.logsTo(8))
        assertEquals(emptyList(), c.logsTo(9))
    }

    @Test
    fun `집합 leader log byte-exact`() {
        val nation = Nation(id = 1, level = 7, capitalCityId = 99)
        val d = GeneralActionDraft(general(id = 42, cityId = CITY_EOP, troop = 42), city(CITY_EOP), nation)
        val c = GeneralActionResolveContext(d, rng("che_집합"), env, MONTH, date, troopName = "선봉대")
        CheJiphap(pipeline).resolve(c)
        assertEquals("<C>●</>${MONTH}월:<G><b>업</b></>에서 집합을 실시했습니다. <1>$date</>", c.logs()[0])
    }

    @Test
    fun `move and gather draw zero turn-RNG (different seeds identical cascade)`() {
        val nation = Nation(id = 1, level = 0, capitalCityId = null)
        fun runIdong(seed: String): GeneralActionDraft {
            val leader = general(id = 42, officerLevel = 12, cityId = CITY_EOP)
            val f1 = general(id = 7, nationId = 1, cityId = CITY_EOP)
            val d = GeneralActionDraft(leader, city(CITY_EOP), nation)
            d.destCity = city(CITY_NAMPI)
            CheIdong(pipeline).resolve(GeneralActionResolveContext(d,
                RandUtil(LiteHashDrbg(serializeSeed(seed, "generalCommand", 190, MONTH, 42, "che_이동"))),
                env, MONTH, date, args = linkedMapOf("destCityID" to CITY_NAMPI), candidateGenerals = listOf(f1)))
            return d
        }
        val a = runIdong("a".repeat(32)); val b = runIdong("f".repeat(32))
        assertEquals(a.general.cityId, b.general.cityId)
        assertEquals(a.general.atmos, b.general.atmos)
        assertEquals(a.cascadeGenerals.map { it.id to it.cityId }, b.cascadeGenerals.map { it.id to it.cityId })
    }

    @Test
    fun `이동 NearCity over the F-MAP module (업 → 남피 is adjacent)`() {
        // structural confirmation that 업(1) → 남피(9) is in the F-MAP nearCity(1) set the constraint consumes.
        assertTrue(CalcCityDistance.nearCity(CITY_EOP, 1).contains(CITY_NAMPI))
        assertEquals("남피", CityConst.byId(CITY_NAMPI)?.name)
    }
}
