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
import kotlin.test.assertTrue

/**
 * Port-faithful resolve tests for che_훈련 + cr_맹훈련.
 * 훈련: score = clamp(round(L*100/crew*trainDelta), 0, clamp(100-train,0)); atmos side-effect; addDex(score).
 * 맹훈련: score = round(L*100/crew*trainDelta*2/3) (no score clamp); increaseVarWithLimit train/atmos [0..100];
 *        addDex(score*2); exp 150 ded 100.
 */
class TrainTest {

    private val pipeline = GeneralActionPipeline()
    private val MONTH = 3
    private val date = "12:34"

    private fun general(
        leadership: Int = 100, crew: Int = 1000, crewTypeId: Int = 1100,
        train: Double = 0.0, atmos: Double = 0.0,
    ) = General(
        id = 42, nationId = 1, cityId = 7,
        leadership = leadership, strength = 70, intel = 70, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = 1,
        gold = 1000, rice = 1000, crew = crew, train = train, atmos = atmos, crewTypeId = crewTypeId,
        meta = linkedMapOf("leadership_exp" to 0.0),
    )

    private fun city() = City(
        id = 7, nationId = 1, level = 5,
        commerce = 1000, commerceMax = 20000, agriculture = 1000, agricultureMax = 20000,
        supplyState = 1, frontState = 0, trust = 50.0, population = 100000, populationMax = 200000,
    )

    private val nation = Nation(id = 1, level = 7, capitalCityId = 99)
    private val env = WorldEnv(year = 190, startYear = 184, develCost = 120)

    private fun ctx(d: GeneralActionDraft, cls: String) = GeneralActionResolveContext(
        d, RandUtil(LiteHashDrbg(serializeSeed("0".repeat(32), "generalCommand", 190, MONTH, 42, cls))),
        env, MONTH, date)

    // === 훈련 ===

    @Test
    fun `훈련 key name and constraints metadata`() {
        val a = CheHullyeon(pipeline)
        assertEquals("che_훈련", a.key); assertEquals("훈련", a.name); assertEquals("군사", a.category)
    }

    @Test
    fun `훈련 score caps at trainMargin and sets atmos side-effect`() {
        // L100 crew1000 → raw = round(100*100/1000*30) = 300; cap = clamp(100-0,0)=100 → score 100.
        // atmos 50 → side-effect = trunc(50*1.0) = 50.
        val d = GeneralActionDraft(general(train = 0.0, atmos = 50.0), city(), nation)
        CheHullyeon(pipeline).resolve(ctx(d, "che_훈련"))
        assertEquals(100.0, d.general.train, 1e-9, "train += score (capped 100)")
        assertEquals(50.0, d.general.atmos, 1e-9, "atmos set to side-effect trunc(50)")
        // addDex footman score 100 → dex1 = 100
        assertEquals(100.0, metaDouble(d.general.meta, "dex1"), 1e-9)
        assertEquals(100.0, d.general.experience, 1e-9)
        assertEquals(70.0, d.general.dedication, 1e-9)
        assertEquals(1.0, metaDouble(d.general.meta, "leadership_exp"), 1e-9)
    }

    @Test
    fun `훈련 trainMargin cap respects already-high train`() {
        // train 80 → cap = clamp(100-80,0)=20; raw=300 → score min(300,20)=20.
        val d = GeneralActionDraft(general(train = 80.0), city(), nation)
        CheHullyeon(pipeline).resolve(ctx(d, "che_훈련"))
        assertEquals(100.0, d.general.train, 1e-9, "train 80 + score 20 = 100")
    }

    @Test
    fun `훈련 score floor 0 when leadership tiny`() {
        // L1 crew 1_000_000 → raw = round(1*100/1000000*30) = round(0.003)=0 → score 0.
        val d = GeneralActionDraft(general(leadership = 1, crew = 1_000_000, train = 0.0), city(), nation)
        CheHullyeon(pipeline).resolve(ctx(d, "che_훈련"))
        assertEquals(0.0, d.general.train, 1e-9)
    }

    @Test
    fun `훈련 log byte-exact`() {
        val d = GeneralActionDraft(general(atmos = 50.0), city(), nation)
        val c = ctx(d, "che_훈련")
        CheHullyeon(pipeline).resolve(c)
        assertEquals(1, c.logs().size)
        assertEquals("<C>●</>${MONTH}월:훈련치가 <C>100</> 상승했습니다. <1>$date</>", c.logs()[0])
    }

    // === 맹훈련 ===

    @Test
    fun `맹훈련 key name rawClassName preserve cr prefix`() {
        val a = CrMaenghullyeon(pipeline)
        assertEquals("cr_맹훈련", a.key); assertEquals("맹훈련", a.name)
        assertEquals("cr_맹훈련", a.rawClassName)   // 6th RNG seed token preserves the cr_ prefix
        assertEquals("맹훈련", a.lotteryActionName)  // distinct trailing lottery token
    }

    @Test
    fun `맹훈련 score formula no clamp and increaseVarWithLimit both 0 to 100`() {
        // L100 crew1000 → score = round(100*100/1000*30*2/3) = round(200) = 200 (NO score clamp).
        // train 0 + 200 → clamp 100; atmos 0 + 200 → clamp 100.
        val d = GeneralActionDraft(general(train = 0.0, atmos = 0.0), city(), nation)
        CrMaenghullyeon(pipeline).resolve(ctx(d, "cr_맹훈련"))
        assertEquals(100.0, d.general.train, 1e-9, "train clamped 100")
        assertEquals(100.0, d.general.atmos, 1e-9, "atmos clamped 100")
        // addDex score*2 = 400 (footman, no 0.9)
        assertEquals(400.0, metaDouble(d.general.meta, "dex1"), 1e-9)
        assertEquals(150.0, d.general.experience, 1e-9)
        assertEquals(100.0, d.general.dedication, 1e-9)
        assertEquals(1.0, metaDouble(d.general.meta, "leadership_exp"), 1e-9)
    }

    @Test
    fun `맹훈련 partial increase below cap`() {
        // L70 crew2100 → score = round(70*100/2100*30*2/3) = round(3.3333*30*0.6667)=round(66.666)=67.
        // train 10 + 67 = 77 (below 100); atmos 20 + 67 = 87.
        val d = GeneralActionDraft(general(leadership = 70, crew = 2100, train = 10.0, atmos = 20.0), city(), nation)
        CrMaenghullyeon(pipeline).resolve(ctx(d, "cr_맹훈련"))
        // recompute exact: 70*100/2100 = 3.333..., *30 = 100, *2/3 = 66.666..., round = 67
        assertEquals(77.0, d.general.train, 1e-9)
        assertEquals(87.0, d.general.atmos, 1e-9)
    }

    @Test
    fun `맹훈련 log byte-exact`() {
        val d = GeneralActionDraft(general(), city(), nation)
        val c = ctx(d, "cr_맹훈련")
        CrMaenghullyeon(pipeline).resolve(c)
        assertEquals(1, c.logs().size)
        assertEquals("<C>●</>${MONTH}월:훈련, 사기치가 <C>200</> 상승했습니다. <1>$date</>", c.logs()[0])
    }

    @Test
    fun `addDex wizard 0_9 multiplier on 맹훈련`() {
        // wizard crewType 1400 (armType 4): score 200 *2 = 400, *0.9 = 360.
        val d = GeneralActionDraft(general(crewTypeId = 1400), city(), nation)
        CrMaenghullyeon(pipeline).resolve(ctx(d, "cr_맹훈련"))
        assertEquals(360.0, metaDouble(d.general.meta, "dex4"), 1e-9)
    }

    @Test
    fun `train commands draw zero turn-RNG (different seeds identical)`() {
        val a = GeneralActionDraft(general(atmos = 50.0), city(), nation)
        CheHullyeon(pipeline).resolve(GeneralActionResolveContext(a,
            RandUtil(LiteHashDrbg(serializeSeed("a".repeat(32), "generalCommand", 190, MONTH, 42, "che_훈련"))),
            env, MONTH, date))
        val b = GeneralActionDraft(general(atmos = 50.0), city(), nation)
        CheHullyeon(pipeline).resolve(GeneralActionResolveContext(b,
            RandUtil(LiteHashDrbg(serializeSeed("f".repeat(32), "generalCommand", 190, MONTH, 42, "che_훈련"))),
            env, MONTH, date))
        assertEquals(a.general.train, b.general.train)
        assertEquals(a.general.atmos, b.general.atmos)
        assertTrue(metaDouble(a.general.meta, "dex1") == metaDouble(b.general.meta, "dex1"))
    }
}
