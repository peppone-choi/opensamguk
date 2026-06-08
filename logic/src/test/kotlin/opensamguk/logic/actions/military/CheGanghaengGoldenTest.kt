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
 * Port-faithful resolve test for che_강행 (강행군). Deterministic — ZERO turn-RNG draws.
 *
 * che_강행.php run() (lines 113-165) vs che_이동([CheIdong]):
 *   - cost = [develcost * 5, 0]   (이동 = [develcost, 0])
 *   - NearCity(3)                  (이동 = NearCity(1))
 *   - train -= 5 floor 20 + atmos -= 5 floor 20   (이동 = atmos만)
 *   - exp = 100                    (이동 = 50)
 *   - 로그 동사 "강행"             (이동 = "이동")
 *
 * Fixture cities (CityConst golden, shared with MoveAndGatherTest): 업(1) ↔ 남피(9) bidirectionally
 * adjacent → 남피 ∈ nearCity(업, 1) ⊆ nearCity(업, 3). JosaUtil.pick("남피","로")="로".
 * 로그 문자열은 che_강행.php run()에서 verbatim 복사. NO docker capture.
 */
class CheGanghaengGoldenTest {

    private val pipeline = GeneralActionPipeline()
    private val MONTH = 3
    private val date = "12:34"

    private val CITY_EOP = 1   // 업
    private val CITY_NAMPI = 9 // 남피 (업 인접)

    private fun general(
        id: Int = 42, nationId: Int = 1, cityId: Int = CITY_EOP,
        officerLevel: Int = 1, train: Double = 80.0, atmos: Double = 80.0, gold: Int = 5000,
    ) = General(
        id = id, nationId = nationId, cityId = cityId,
        leadership = 70, strength = 70, intel = 70, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = officerLevel,
        gold = gold, rice = 1000, crew = 1000, train = train, atmos = atmos, troop = id,
        meta = linkedMapOf("leadership_exp" to 0.0),
    )

    private fun city(id: Int = CITY_EOP, nationId: Int = 1) = City(
        id = id, nationId = nationId, level = 5,
        commerce = 1000, commerceMax = 20000, agriculture = 1000, agricultureMax = 20000,
        supplyState = 1, frontState = 0, trust = 50.0, population = 100000, populationMax = 200000,
    )

    // develCost 120 → 강행 cost gold = 120 * 5 = 600
    private val env = WorldEnv(year = 190, startYear = 184, develCost = 120)

    private fun rng() = RandUtil(LiteHashDrbg(serializeSeed("0".repeat(32), "generalCommand", 190, MONTH, 42, "che_강행")))

    @Test
    fun `강행 key name category and args schema`() {
        val a = CheGanghaeng(pipeline)
        assertEquals("che_강행", a.key); assertEquals("강행", a.name); assertEquals("인사", a.category)
        assertTrue(a.argsSchema.containsKey("destCityID"))
    }

    @Test
    fun `강행 cost is develcost times 5 gold rice 0`() {
        val a = CheGanghaeng(pipeline)
        assertEquals(600, a.getCostGold(env), "develcost 120 * 5 = 600")
    }

    @Test
    fun `강행 single general moves city train atmos floor gold cost exp leadership_exp`() {
        val nation = Nation(id = 1, level = 7, capitalCityId = 99)
        val d = GeneralActionDraft(general(officerLevel = 1, train = 80.0, atmos = 80.0, gold = 5000), city(CITY_EOP), nation)
        d.destCity = city(CITY_NAMPI)
        val c = GeneralActionResolveContext(d, rng(), env, MONTH, date,
            args = linkedMapOf("destCityID" to CITY_NAMPI))
        CheGanghaeng(pipeline).resolve(c)

        assertEquals(CITY_NAMPI, d.general.cityId, "moved to dest city")
        assertEquals(75.0, d.general.train, 1e-9, "train -5 = 75 (above floor 20)")
        assertEquals(75.0, d.general.atmos, 1e-9, "atmos -5 = 75 (above floor 20)")
        assertEquals(5000 - 600, d.general.gold, "gold -= develcost*5 = 600")
        assertEquals(100.0, d.general.experience, 1e-9, "exp += 100")
        assertEquals(1.0, metaDouble(d.general.meta, "leadership_exp"), 1e-9)
        assertTrue(d.cascadeGenerals.isEmpty(), "no cascade for a normal general")
    }

    @Test
    fun `강행 train and atmos floor at 20`() {
        val nation = Nation(id = 1, level = 7, capitalCityId = 99)
        // train 22 - 5 = 17 → floor 20 ; atmos 23 - 5 = 18 → floor 20
        val d = GeneralActionDraft(general(train = 22.0, atmos = 23.0), city(CITY_EOP), nation)
        d.destCity = city(CITY_NAMPI)
        CheGanghaeng(pipeline).resolve(GeneralActionResolveContext(d, rng(), env, MONTH, date,
            args = linkedMapOf("destCityID" to CITY_NAMPI)))
        assertEquals(20.0, d.general.train, 1e-9)
        assertEquals(20.0, d.general.atmos, 1e-9)
    }

    @Test
    fun `강행 gold floors at 0`() {
        val nation = Nation(id = 1, level = 7, capitalCityId = 99)
        // gold 300 - 600 = -300 → floor 0
        val d = GeneralActionDraft(general(gold = 300), city(CITY_EOP), nation)
        d.destCity = city(CITY_NAMPI)
        CheGanghaeng(pipeline).resolve(GeneralActionResolveContext(d, rng(), env, MONTH, date,
            args = linkedMapOf("destCityID" to CITY_NAMPI)))
        assertEquals(0, d.general.gold)
    }

    @Test
    fun `강행 actor log byte-exact (남피 josa 로 강행했습니다)`() {
        val nation = Nation(id = 1, level = 7, capitalCityId = 99)
        val d = GeneralActionDraft(general(), city(CITY_EOP), nation)
        d.destCity = city(CITY_NAMPI)
        val c = GeneralActionResolveContext(d, rng(), env, MONTH, date,
            args = linkedMapOf("destCityID" to CITY_NAMPI))
        CheGanghaeng(pipeline).resolve(c)
        val josaRo = JosaUtil.pick("남피", "로")
        assertEquals("<C>●</>${MONTH}월:<G><b>남피</b></>${josaRo} 강행했습니다. <1>$date</>", c.logs()[0])
    }

    @Test
    fun `강행 roaming-leader moves ALL same-nation generals with per-target PLAIN logs`() {
        // officer_level 12 + nation.level 0 (wandering) → roaming leader.
        val nation = Nation(id = 1, level = 0, capitalCityId = null)
        val leader = general(id = 42, officerLevel = 12, cityId = CITY_EOP)
        val f1 = general(id = 7, nationId = 1, cityId = CITY_EOP)
        val f2 = general(id = 8, nationId = 1, cityId = CITY_EOP)
        val other = general(id = 9, nationId = 2, cityId = CITY_EOP)   // 타국 — 미이동
        val d = GeneralActionDraft(leader, city(CITY_EOP), nation)
        d.destCity = city(CITY_NAMPI)
        val c = GeneralActionResolveContext(d, rng(), env, MONTH, date,
            args = linkedMapOf("destCityID" to CITY_NAMPI),
            candidateGenerals = listOf(f1, f2, other))
        CheGanghaeng(pipeline).resolve(c)

        assertEquals(CITY_NAMPI, d.general.cityId, "leader moved")
        assertEquals(2, d.cascadeGenerals.size, "2 same-nation followers moved")
        assertTrue(d.cascadeGenerals.all { it.cityId == CITY_NAMPI }, "followers in dest city")
        assertEquals(setOf(7, 8), d.cascadeGenerals.map { it.id }.toSet())
        val josaRo = JosaUtil.pick("남피", "로")
        val expected = "<C>●</>방랑군 세력이 <G><b>남피</b></>${josaRo} 강행했습니다."
        assertEquals(listOf(expected), c.logsTo(7))
        assertEquals(listOf(expected), c.logsTo(8))
        assertEquals(emptyList(), c.logsTo(9))
    }

    @Test
    fun `강행 draws zero turn-RNG (different seeds identical effect)`() {
        val nation = Nation(id = 1, level = 0, capitalCityId = null)
        fun runGanghaeng(seed: String): GeneralActionDraft {
            val leader = general(id = 42, officerLevel = 12, cityId = CITY_EOP)
            val f1 = general(id = 7, nationId = 1, cityId = CITY_EOP)
            val d = GeneralActionDraft(leader, city(CITY_EOP), nation)
            d.destCity = city(CITY_NAMPI)
            CheGanghaeng(pipeline).resolve(GeneralActionResolveContext(d,
                RandUtil(LiteHashDrbg(serializeSeed(seed, "generalCommand", 190, MONTH, 42, "che_강행"))),
                env, MONTH, date, args = linkedMapOf("destCityID" to CITY_NAMPI), candidateGenerals = listOf(f1)))
            return d
        }
        val a = runGanghaeng("a".repeat(32)); val b = runGanghaeng("f".repeat(32))
        assertEquals(a.general.cityId, b.general.cityId)
        assertEquals(a.general.train, b.general.train)
        assertEquals(a.general.atmos, b.general.atmos)
        assertEquals(a.general.gold, b.general.gold)
        assertEquals(a.cascadeGenerals.map { it.id to it.cityId }, b.cascadeGenerals.map { it.id to it.cityId })
    }

    @Test
    fun `강행 NearCity over the F-MAP module (업 → 남피 within radius 3)`() {
        // 업(1) → 남피(9)가 NearCity(3) 가용집합에 포함됨을 구조적으로 확인.
        assertTrue(CalcCityDistance.nearCity(CITY_EOP, 3).contains(CITY_NAMPI))
        assertEquals("남피", CityConst.byId(CITY_NAMPI)?.name)
    }
}
