package opensamguk.logic.actions.military

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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Port-faithful resolve tests for che_사기진작 + che_소집해제.
 * 사기진작: atmos-shape of 훈련 — atmos += clamped score; train side-effect; gold = round(crew/100); addDex(score).
 * 소집해제: pop += crewUp; crew = 0; exp 70 ded 100; NO leadership_exp, NO addDex, NO lottery.
 */
class MoraleAndDisbandTest {

    private val pipeline = GeneralActionPipeline()
    private val MONTH = 3
    private val date = "12:34"

    private fun general(
        leadership: Int = 100, crew: Int = 1000, crewTypeId: Int = 1100,
        train: Double = 0.0, atmos: Double = 0.0, gold: Int = 1000,
    ) = General(
        id = 42, nationId = 1, cityId = 7,
        leadership = leadership, strength = 70, intel = 70, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = 1,
        gold = gold, rice = 1000, crew = crew, train = train, atmos = atmos, crewTypeId = crewTypeId,
        meta = linkedMapOf("leadership_exp" to 0.0),
    )

    private fun city(pop: Int = 100000) = City(
        id = 7, nationId = 1, level = 5,
        commerce = 1000, commerceMax = 20000, agriculture = 1000, agricultureMax = 20000,
        supplyState = 1, frontState = 0, trust = 50.0, population = pop, populationMax = 200000,
    )

    private val nation = Nation(id = 1, level = 7, capitalCityId = 99)
    private val env = WorldEnv(year = 190, startYear = 184, develCost = 120)

    private fun ctx(d: GeneralActionDraft, cls: String) = GeneralActionResolveContext(
        d, RandUtil(LiteHashDrbg(serializeSeed("0".repeat(32), "generalCommand", 190, MONTH, 42, cls))),
        env, MONTH, date)

    // === 사기진작 ===

    @Test
    fun `사기진작 key name category`() {
        val a = CheSagiJinjak(pipeline)
        assertEquals("che_사기진작", a.key); assertEquals("사기진작", a.name); assertEquals("군사", a.category)
    }

    @Test
    fun `사기진작 atmos score gold cost train side-effect addDex`() {
        // L100 crew1000 atmos0 → raw=round(100*100/1000*30)=300; cap=clamp(100-0,0)=100 → score 100.
        // train 50 → side-effect trunc(50)=50. gold cost = round(1000/100)=10.
        val d = GeneralActionDraft(general(train = 50.0, atmos = 0.0, gold = 1000), city(), nation)
        CheSagiJinjak(pipeline).resolve(ctx(d, "che_사기진작"))
        assertEquals(100.0, d.general.atmos, 1e-9, "atmos += score (cap 100)")
        assertEquals(50.0, d.general.train, 1e-9, "train set to side-effect")
        assertEquals(990, d.general.gold, "gold -= round(crew/100)=10")
        assertEquals(100.0, metaDouble(d.general.meta, "dex1"), 1e-9, "addDex footman score 100")
        assertEquals(100.0, d.general.experience, 1e-9)
        assertEquals(70.0, d.general.dedication, 1e-9)
        assertEquals(1.0, metaDouble(d.general.meta, "leadership_exp"), 1e-9)
    }

    @Test
    fun `사기진작 atmos cap respects high atmos`() {
        // atmos 80 → cap = clamp(100-80,0)=20; raw 300 → score 20.
        val d = GeneralActionDraft(general(atmos = 80.0), city(), nation)
        CheSagiJinjak(pipeline).resolve(ctx(d, "che_사기진작"))
        assertEquals(100.0, d.general.atmos, 1e-9, "atmos 80 + 20 = 100")
    }

    @Test
    fun `사기진작 gold floored at 0`() {
        val d = GeneralActionDraft(general(crew = 100000, gold = 5), city(), nation)
        CheSagiJinjak(pipeline).resolve(ctx(d, "che_사기진작"))
        // cost = round(100000/100) = 1000 > gold 5 → floored 0
        assertEquals(0, d.general.gold)
    }

    @Test
    fun `사기진작 log byte-exact`() {
        val d = GeneralActionDraft(general(train = 50.0), city(), nation)
        val c = ctx(d, "che_사기진작")
        CheSagiJinjak(pipeline).resolve(c)
        assertEquals(1, c.logs().size)
        assertEquals("<C>●</>${MONTH}월:사기치가 <C>100</> 상승했습니다. <1>$date</>", c.logs()[0])
    }

    // === 소집해제 ===

    @Test
    fun `소집해제 key name category`() {
        val a = CheSojipHaeje(pipeline)
        assertEquals("che_소집해제", a.key); assertEquals("소집해제", a.name); assertEquals("군사", a.category)
    }

    @Test
    fun `소집해제 pop add crew zero exp ded no leadership_exp`() {
        val d = GeneralActionDraft(general(crew = 1000), city(pop = 100000), nation)
        CheSojipHaeje(pipeline).resolve(ctx(d, "che_소집해제"))
        assertEquals(101000, d.city.population, "pop += crewUp (1000)")
        assertEquals(0, d.general.crew, "crew = 0")
        assertEquals(70.0, d.general.experience, 1e-9)
        assertEquals(100.0, d.general.dedication, 1e-9)
        // NO leadership_exp (stays at the initial 0)
        assertEquals(0.0, metaDouble(d.general.meta, "leadership_exp"), 1e-9)
        // NO addDex — dex var absent
        assertFalse(d.general.meta.containsKey("dex1"), "no addDex write")
    }

    @Test
    fun `소집해제 log byte-exact`() {
        val d = GeneralActionDraft(general(crew = 1000), city(), nation)
        val c = ctx(d, "che_소집해제")
        CheSojipHaeje(pipeline).resolve(c)
        assertEquals(1, c.logs().size)
        assertEquals("<C>●</>${MONTH}월:병사들을 <R>소집해제</>하였습니다. <1>$date</>", c.logs()[0])
    }

    @Test
    fun `morale and disband draw zero turn-RNG (different seeds identical)`() {
        val a = GeneralActionDraft(general(train = 50.0), city(), nation)
        CheSagiJinjak(pipeline).resolve(GeneralActionResolveContext(a,
            RandUtil(LiteHashDrbg(serializeSeed("a".repeat(32), "generalCommand", 190, MONTH, 42, "che_사기진작"))),
            env, MONTH, date))
        val b = GeneralActionDraft(general(train = 50.0), city(), nation)
        CheSagiJinjak(pipeline).resolve(GeneralActionResolveContext(b,
            RandUtil(LiteHashDrbg(serializeSeed("f".repeat(32), "generalCommand", 190, MONTH, 42, "che_사기진작"))),
            env, MONTH, date))
        assertEquals(a.general.atmos, b.general.atmos)
        assertEquals(a.general.gold, b.general.gold)
    }
}
