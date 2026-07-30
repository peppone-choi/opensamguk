package opensamguk.logic.ai.families

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.logic.ai.GeneralAiDoBodies
import opensamguk.logic.ai.GeneralAiFactory
import opensamguk.logic.ai.NationPassHooks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * L-RATES — the WORLD-DRIVEN RatesPromoFamily nation-pass side-effect hooks ([RatesPromoFamily.bodies]).
 *
 * GRAND TRUTH = PHP `legacy/devsam-core/hwe/sammo/GeneralAI.php` (read in full):
 *  - `chooseNonLordPromotion` (`:3881-3963`): per EMPTY chief slot (Util::range(minChiefLevel,12), skipping
 *    isOfficerSet / already-in-chiefGenerals / self) up to 5 `choice` from the first non-empty pool
 *    npcWar→npcCivil→userWar→userCivil (redraw-on-reject: officer_level!=1 / below chiefStatMin) → on accept
 *    queue the officer_level=chiefLevel / officer_city=0 deltas.
 *  - `choosePromotion`        (`:3978-4170`): per OCCUPIED chief slot in the demote/promote loop (range
 *    11→minChiefLevel-1) exactly ONE `nextBool(0.1)` (`:4099`); the `:4102` newChiefProb gate NEVER draws
 *    (decision #9 phantom). Two `uasort` deterministic chief-selects (NO draw).
 *  - `chooseTexRate`          (`:4172-4199`): ZERO draws — the `($devRate['pop']+$devRate['all'])/2 → rate`
 *    ladder over [RatesPromoFamily.calcNationDevelopedRate]; queue the `war=0` / `rate` deltas.
 *  - `chooseGoldBillRate`     (`:4201-4246`): ZERO draws — `intval(income/outcome*90)` clamp 20-200; queue `bill`.
 *  - `chooseRiceBillRate`     (`:4248-4292`): ZERO draws — identical shape, rice+wall income; queue `bill`.
 *
 * These are the `chooseNationTurn` npcType>=2 side-effect hooks (PHP `:3633-3646`): promotion hooks MAY draw;
 * the rate hooks do NOT. Each test builds [RatesPromoFamily.bodies] over a DETERMINISTIC fixture
 * [RatesPromoFamily.RatesPromoContext] and asserts the queued deltas + the ordered draw stream off a recording
 * [RandUtil]. The candidate-set ORDER (the chief-slot scan + the pool priority + the develRate keys) is the
 * draw-for-draw parity target.
 */
class RatesPromoBodiesTest {

    /**
     * A draw-recording RNG over a REAL [LiteHashDrbg]. Records the top-level SEMANTIC draw stream (`nextBool`
     * prob + each `choice` as a `nextInt`); a re-entrancy guard prevents the inner primitive a `nextBool`
     * consumes from being double-recorded.
     */
    private class RecordingRng(seed: String) : RandUtil(LiteHashDrbg(seed)) {
        data class Draw(val kind: String, val prob: Double)
        /** ALL `nextBool`/`choice` invocations (incl. byte-free short-circuits — the phantom :4102 gate). */
        val draws = ArrayList<Draw>()
        /** ONLY byte-CONSUMING draws (the cursor-for-cursor parity target — excludes phantom short-circuits). */
        val byteDraws = ArrayList<Draw>()
        private var inNextBool = false
        private var pendingBool: Double? = null
        override fun nextBool(prob: Double): Boolean {
            draws.add(Draw("nextBool", prob))
            inNextBool = true
            pendingBool = prob
            try {
                return super.nextBool(prob)
            } finally {
                inNextBool = false
                pendingBool = null
            }
        }
        override fun <T> choice(items: List<T>): T {
            draws.add(Draw("choice", 0.0))
            byteDraws.add(Draw("choice", 0.0))
            return super.choice(items)
        }
        override fun nextFloat1(): Double {
            // A nextFloat1 reached inside a nextBool means that nextBool actually consumed a byte (prob∉{0,≥1}).
            if (inNextBool) byteDraws.add(Draw("nextBool", pendingBool!!))
            return super.nextFloat1()
        }
        override fun nextBit(): Boolean {
            if (inNextBool) byteDraws.add(Draw("nextBool", pendingBool!!))
            return super.nextBit()
        }
    }

    /** A queued meta-KV delta `(kind, targetId, key, value)` — insertion order is the write order. */
    private data class Delta(val kind: String, val targetId: Int, val key: String, val value: Any?)

    private fun recorder(out: MutableList<Delta>) = object : RatesPromoFamily.RatesPromoDeltaSink {
        override fun recordGeneralKv(generalId: Int, key: String, value: Any?) {
            out.add(Delta("general", generalId, key, value))
        }
        override fun recordNationKv(nationId: Int, key: String, value: Any?) {
            out.add(Delta("nation", nationId, key, value))
        }
    }

    private fun candidate(
        id: Int,
        officerLevel: Int = 1,
        leadership: Double = 100.0,
        strength: Double = 100.0,
        intel: Double = 100.0,
        npcType: Int = 2,
        killturn: Int = 1000,
        belong: Int = 5,
    ) = RatesPromoFamily.PromotionCandidate(
        generalId = id, officerLevel = officerLevel, leadership = leadership, strength = strength, intel = intel,
        npcType = npcType, killturn = killturn, belong = belong, hasNoChief = false, hasNoAmbassador = false,
    )

    private fun develCity(ratio: Double) = RatesPromoFamily.CityDevelInput(
        trust = ratio * 100, pop = ratio, popMax = 1.0, agri = ratio, agriMax = 1.0,
        comm = ratio, commMax = 1.0, secu = ratio, secuMax = 1.0, def = ratio, defMax = 1.0,
        wall = ratio, wallMax = 1.0,
    )

    // ==================================================================================================
    // (A) chooseNonLordPromotion (:3881-3963) — up to 5 choice per EMPTY chief slot; first non-empty pool.
    // ==================================================================================================

    @Test
    fun `chooseNonLordPromotion fills an empty chief slot with one accepted choice and queues the officer deltas`() {
        // nation level 1 → minChiefLevel = getNationChiefLevel(1) = 11 (so range(11,12) = {11,12}).
        // chiefSet has level 12 set, level 11 empty + not in chiefGenerals + not self → ONE empty slot (11).
        // chiefLevel 11 short-circuits the stat gate (PHP :3928 `if chiefLevel==11 {picked=true; break}`).
        // The npcWarGenerals pool is non-empty → the first attempt's choice picks (officer_level==1 → accept).
        val rng = RecordingRng("nonlord")
        val deltas = ArrayList<Delta>()
        val ctx = RatesPromoFamily.RatesPromoContext(
            rng = rng,
            nationId = 7,
            nationLevel = 1,
            chiefSet = setOf(12),                 // level 12 occupied; level 11 empty.
            chiefGeneralLevels = emptySet(),      // no chief already chosen at any level.
            selfOfficerLevel = 0,                 // self is not at any chief level in {11,12}.
            chiefStatMin = 65.0,
            killturnEnv = 0, turnTerm = 120,
            npcWarGenerals = listOf(candidate(101, officerLevel = 1)),
            deltaSink = recorder(deltas),
        )

        RatesPromoFamily.bodies(ctx).chooseNonLordPromotion()

        // ONE empty slot (11) × ONE accepted attempt → exactly one choice draw.
        assertEquals(listOf(RecordingRng.Draw("choice", 0.0)), rng.draws,
            "one choice per accepted attempt; chiefLevel 11 bypasses the stat gate (PHP :3910/:3928)")
        // PHP :3950-3955 — the picked general's officer_level=chiefLevel(11), officer_city=0 deltas, then the
        // nation chief_set |= doOfficerSet(0,11) delta (PHP :3958-3961).
        assertEquals(
            listOf(
                Delta("general", 101, "officer_level", 11),
                Delta("general", 101, "officer_city", 0),
                Delta("nation", 7, "chief_set", 11),
            ),
            deltas,
            "the accepted candidate gets officer_level/officer_city deltas; the nation chief_set bit is added",
        )
    }

    @Test
    fun `chooseNonLordPromotion redraws up to 5 times on reject then leaves the slot empty with no deltas`() {
        // ONE empty slot (11) but every candidate has officer_level != 1 → rejected every attempt → 5 choice
        // draws (Util::range(5) redraw-on-reject, PHP :3905/:3924-3925), no accept, no delta.
        val rng = RecordingRng("nonlord-reject")
        val deltas = ArrayList<Delta>()
        val ctx = RatesPromoFamily.RatesPromoContext(
            rng = rng,
            nationId = 7,
            nationLevel = 1,
            chiefSet = setOf(12),
            chiefGeneralLevels = emptySet(),
            selfOfficerLevel = 0,
            chiefStatMin = 65.0,
            killturnEnv = 0, turnTerm = 120,
            npcWarGenerals = listOf(candidate(101, officerLevel = 2)), // officer_level != 1 → always rejected.
            deltaSink = recorder(deltas),
        )

        RatesPromoFamily.bodies(ctx).chooseNonLordPromotion()

        assertEquals(5, rng.draws.count { it.kind == "choice" },
            "all candidates rejected (officer_level!=1) → 5 redraws per empty slot (PHP :3905)")
        assertTrue(deltas.isEmpty(), "no accept → no officer/chief_set delta")
    }

    @Test
    fun `chooseNonLordPromotion makes ZERO draws when there is no empty chief slot`() {
        // chiefSet has BOTH 11 and 12 set → no empty slot in range(11,12) → no inner loop → ZERO draws.
        val rng = RecordingRng("nonlord-full")
        val deltas = ArrayList<Delta>()
        val ctx = RatesPromoFamily.RatesPromoContext(
            rng = rng,
            nationId = 7,
            nationLevel = 1,
            chiefSet = setOf(11, 12),
            chiefGeneralLevels = emptySet(),
            selfOfficerLevel = 0,
            chiefStatMin = 65.0,
            killturnEnv = 0, turnTerm = 120,
            npcWarGenerals = listOf(candidate(101, officerLevel = 1)),
            deltaSink = recorder(deltas),
        )

        RatesPromoFamily.bodies(ctx).chooseNonLordPromotion()

        assertTrue(rng.draws.isEmpty(), "no empty chief slot → no choice draw (PHP :3889-3895 continue)")
        assertTrue(deltas.isEmpty())
    }

    // ==================================================================================================
    // (B) choosePromotion (:3978-4170) — ONE nextBool(0.1) per OCCUPIED slot; :4102 newChiefProb NEVER draws.
    // ==================================================================================================

    @Test
    fun `choosePromotion draws exactly one nextBool(0_1) per occupied chief slot and the 4102 gate never draws`() {
        // nation level 1 → minChiefLevel 11 → the demote/promote loop range(11,10,-1) = {11}.
        // chiefSet empty → level 11 is NOT isOfficerSet → not skipped by :4082; chiefGenerals has level 11
        // OCCUPIED (an old chief) → :4096 else-branch → ONE nextBool(0.1). nationGenerals empty → no newChief
        // → no delta. The :4102 newChiefProb gate (prob ∈ {0,1}) consumes ZERO bytes (phantom).
        val rng = RecordingRng("promo")
        val deltas = ArrayList<Delta>()
        val ctx = RatesPromoFamily.RatesPromoContext(
            rng = rng,
            nationId = 7,
            nationLevel = 1,
            chiefSet = emptySet(),
            chiefGeneralLevels = setOf(11),       // level 11 occupied by an old chief (npcType 2 default).
            chiefGeneralOf = { lvl -> if (lvl == 11) candidate(900, officerLevel = 11) else null },
            selfOfficerLevel = 0,
            chiefStatMin = 65.0,
            killturnEnv = 100, turnTerm = 120,
            userGenerals = emptyList(),
            nationGenerals = emptyList(),         // no eligible newChief → no delta.
            deltaSink = recorder(deltas),
        )

        RatesPromoFamily.bodies(ctx).choosePromotion()

        // exactly ONE byte-CONSUMING nextBool(0.1) for the single occupied slot; the :4102 newChiefProb gate
        // is INVOKED (it appears in `draws`) but consumes ZERO bytes (decision #9 phantom) → absent from byteDraws.
        assertEquals(listOf(RecordingRng.Draw("nextBool", 0.1)), rng.byteDraws,
            "one byte-consuming nextBool(0.1) per OCCUPIED slot; the :4102 newChiefProb gate never draws (decision #9)")
        // The :4099 nextBool(0.1) returned false → newChiefProb=0 → the :4102 gate IS invoked as nextBool(0.0)
        // (short-circuits <=0→false, ZERO bytes). Confirm the invocation is byte-free, not absent.
        assertTrue(
            rng.draws.none { it.kind == "nextBool" && it.prob !in setOf(0.1, 0.0, 1.0) },
            "the only nextBool probs are 0.1 (the churn roll) and the 0/1 phantom gate (PHP :4099/:4102)",
        )
    }

    @Test
    fun `choosePromotion draws ZERO when the only chief level is empty (newChiefProb 1 no draw)`() {
        // chiefGenerals empty AND nextChiefs empty for level 11 → :4096 if-branch → newChiefProb=1 → NO draw.
        val rng = RecordingRng("promo-empty")
        val deltas = ArrayList<Delta>()
        val ctx = RatesPromoFamily.RatesPromoContext(
            rng = rng,
            nationId = 7,
            nationLevel = 1,
            chiefSet = emptySet(),
            chiefGeneralLevels = emptySet(),      // level 11 empty → newChiefProb=1, NO draw.
            selfOfficerLevel = 0,
            chiefStatMin = 65.0,
            killturnEnv = 100, turnTerm = 120,
            userGenerals = emptyList(),
            nationGenerals = emptyList(),
            deltaSink = recorder(deltas),
        )

        RatesPromoFamily.bodies(ctx).choosePromotion()

        assertTrue(rng.draws.isEmpty(), "empty chief slot → newChiefProb=1, NO nextBool draw (PHP :4097)")
    }

    @Test
    fun `choosePromotion ranks candidates by double leadership plus strength and intel`() {
        val rng = RecordingRng("promo-stat-order")
        val deltas = ArrayList<Delta>()
        val ctx = RatesPromoFamily.RatesPromoContext(
            rng = rng,
            nationId = 7,
            nationLevel = 1,
            chiefSet = emptySet(),
            chiefGeneralLevels = emptySet(),
            selfOfficerLevel = 12,
            chiefStatMin = 65.0,
            killturnEnv = 100,
            turnTerm = 120,
            nationGenerals = listOf(
                candidate(101, leadership = 65.0, strength = 65.0, intel = 65.0),
                candidate(202, leadership = 100.0, strength = 100.0, intel = 100.0),
            ),
            deltaSink = recorder(deltas),
        )

        RatesPromoFamily.bodies(ctx).choosePromotion()

        assertEquals(
            listOf(Delta("general", 202, "officer_level", 11)),
            deltas.filter { it.kind == "general" && it.key == "officer_level" },
        )
    }

    @Test
    fun `choosePromotion cannot reuse a newly promoted general in a later slot of the same pass`() {
        val rng = RecordingRng("promo-single-pass")
        val deltas = ArrayList<Delta>()
        val ctx = RatesPromoFamily.RatesPromoContext(
            rng = rng,
            nationId = 7,
            nationLevel = 7,
            chiefSet = emptySet(),
            chiefGeneralLevels = emptySet(),
            selfOfficerLevel = 12,
            chiefStatMin = 65.0,
            killturnEnv = 100,
            turnTerm = 120,
            nationGenerals = listOf(candidate(101)),
            deltaSink = recorder(deltas),
        )

        RatesPromoFamily.bodies(ctx).choosePromotion()

        assertEquals(
            listOf(Delta("general", 101, "officer_level", 11)),
            deltas.filter { it.kind == "general" && it.key == "officer_level" },
        )
    }

    // ==================================================================================================
    // (C) chooseTexRate (:4172-4199) — ZERO draws; the avg→rate ladder; queues war=0 + rate deltas.
    // ==================================================================================================

    @Test
    fun `chooseTexRate with no supply cities keeps the default 15 and queues war 0 + rate 15 with 0 draws`() {
        val rng = RecordingRng("tex")
        val deltas = ArrayList<Delta>()
        val ctx = RatesPromoFamily.RatesPromoContext(
            rng = rng, nationId = 7, nationLevel = 1,
            chiefStatMin = 65.0, killturnEnv = 0, turnTerm = 120,
            supplyCities = emptyList(),           // no supply cities → default rate 15 (PHP :4180/:4182).
            deltaSink = recorder(deltas),
        )

        val rate = RatesPromoFamily.bodies(ctx).chooseTexRate()

        assertEquals(15, rate)
        assertEquals(
            listOf(Delta("nation", 7, "war", 0), Delta("nation", 7, "rate", 15)),
            deltas,
            "chooseTexRate queues war=0 then rate (PHP :4194-4197) in array-literal order",
        )
        assertTrue(rng.draws.isEmpty(), "chooseTexRate makes ZERO draws (PHP :4172-4199)")
    }

    @Test
    fun `chooseTexRate uses the develRate ladder when supply cities exist`() {
        val rng = RecordingRng("tex-supply")
        val deltas = ArrayList<Delta>()
        // One fully-developed city → every develKey ratio 1.0 → avg = (pop1.0 + all1.0)/2 = 1.0 > 0.95 → rate 25.
        val ctx = RatesPromoFamily.RatesPromoContext(
            rng = rng, nationId = 7, nationLevel = 1,
            chiefStatMin = 65.0, killturnEnv = 0, turnTerm = 120,
            supplyCities = listOf(develCity(1.0)),
            deltaSink = recorder(deltas),
        )

        val rate = RatesPromoFamily.bodies(ctx).chooseTexRate()

        assertEquals(25, rate, "avg 1.0 > 0.95 → rate 25 (PHP :4185-4187)")
        assertEquals(Delta("nation", 7, "rate", 25), deltas.last())
        assertTrue(rng.draws.isEmpty())
    }

    // ==================================================================================================
    // (D) chooseGoldBillRate / chooseRiceBillRate (:4201-4292) — ZERO draws; intval(income/outcome*90) clamp.
    // ==================================================================================================

    @Test
    fun `chooseGoldBillRate returns 20 with no supply cities and queues no bill delta`() {
        val rng = RecordingRng("gold-bill")
        val deltas = ArrayList<Delta>()
        val ctx = RatesPromoFamily.RatesPromoContext(
            rng = rng, nationId = 7, nationLevel = 1,
            chiefStatMin = 65.0, killturnEnv = 0, turnTerm = 120,
            supplyCities = emptyList(),
            goldIncome = 99999.0, riceIncome = 0.0,
            deltaSink = recorder(deltas),
        )

        val bill = RatesPromoFamily.bodies(ctx).chooseGoldBillRate()

        assertEquals(20, bill, "no supply cities → early-return 20 (PHP :4211); NO db update")
        assertTrue(deltas.isEmpty(), "the early-return 20 path queues NO bill delta (PHP :4211 returns before :4241)")
        assertTrue(rng.draws.isEmpty())
    }

    @Test
    fun `chooseGoldBillRate computes intval(income div outcome times 90) clamp 20-200 and queues the bill delta`() {
        val rng = RecordingRng("gold-bill-supply")
        val deltas = ArrayList<Delta>()
        // income=goldIncome+warIncome=100+0; outcome=valueFit(getOutcome(100, dedicationList),1). dedicationList
        // empty (no nationGenerals) → getOutcome=phpRound(0)=0 → valueFit(0,1)=1. bill=intval(100/1*90)=9000
        // → clamp(.,20,200)=200. The moreBill branch needs gold+income-outcome > reqNationGold*2; gold 0 →
        // 0+100-1=99 ≤ 20000 → branch OFF.
        val ctx = RatesPromoFamily.RatesPromoContext(
            rng = rng, nationId = 7, nationLevel = 1,
            chiefStatMin = 65.0, killturnEnv = 0, turnTerm = 120,
            supplyCities = listOf(develCity(1.0)),
            nationGold = 0, nationRice = 0,
            goldIncome = 100.0, riceIncome = 0.0,
            warGoldIncome = 0.0, wallRiceIncome = 0.0,
            nationGenerals = emptyList(),
            reqNationGold = 10000, reqNationRice = 12000,
            deltaSink = recorder(deltas),
        )

        val bill = RatesPromoFamily.bodies(ctx).chooseGoldBillRate()

        assertEquals(200, bill, "intval(100/1*90)=9000 → valueFit(.,20,200)=200 (PHP :4231/:4239)")
        assertEquals(listOf(Delta("nation", 7, "bill", 200)), deltas, "queues the single bill delta (PHP :4241)")
        assertTrue(rng.draws.isEmpty(), "chooseGoldBillRate makes ZERO draws (PHP :4201-4246)")
    }

    @Test
    fun `chooseRiceBillRate uses rice+wall income and queues the bill delta with 0 draws`() {
        val rng = RecordingRng("rice-bill")
        val deltas = ArrayList<Delta>()
        // riceIncome=50, wallRiceIncome=50 → income=100; outcome=1 (empty dedicationList). bill=intval(100/1*90)
        // =9000 → clamp 200.
        val ctx = RatesPromoFamily.RatesPromoContext(
            rng = rng, nationId = 7, nationLevel = 1,
            chiefStatMin = 65.0, killturnEnv = 0, turnTerm = 120,
            supplyCities = listOf(develCity(1.0)),
            nationGold = 0, nationRice = 0,
            riceIncome = 50.0, wallRiceIncome = 50.0,
            nationGenerals = emptyList(),
            reqNationGold = 10000, reqNationRice = 12000,
            deltaSink = recorder(deltas),
        )

        val bill = RatesPromoFamily.bodies(ctx).chooseRiceBillRate()

        assertEquals(200, bill, "intval((50+50)/1*90)=9000 → 200 (PHP :4277/:4285)")
        assertEquals(listOf(Delta("nation", 7, "bill", 200)), deltas)
        assertTrue(rng.draws.isEmpty(), "chooseRiceBillRate makes ZERO draws (PHP :4248-4292)")
    }

    // ==================================================================================================
    // ASSEMBLE — the rate/promotion hooks wire into NationPassHooks for the Assemble step.
    // ==================================================================================================

    @Test
    fun `bodies expose the 5 nation-pass hooks the factory wires into NationPassHooks`() {
        val rng = RandUtil(LiteHashDrbg("assemble"))
        val deltas = ArrayList<Delta>()
        val ctx = RatesPromoFamily.RatesPromoContext(
            rng = rng, nationId = 7, nationLevel = 1,
            chiefStatMin = 65.0, killturnEnv = 0, turnTerm = 120,
            supplyCities = emptyList(),
            deltaSink = recorder(deltas),
        )
        val hooks = RatesPromoFamily.bodies(ctx)

        // The 5 nation-pass hooks (promotion MAY draw; rate hooks do NOT) wire into NationPassHooks.
        val nationHooks = NationPassHooks(
            choosePromotion = { hooks.choosePromotion() },
            chooseNonLordPromotion = { hooks.chooseNonLordPromotion() },
            chooseTexRate = { hooks.chooseTexRate() },
            chooseGoldBillRate = { hooks.chooseGoldBillRate() },
            chooseRiceBillRate = { hooks.chooseRiceBillRate() },
        )
        GeneralAiFactory.build(
            generalPolicy = opensamguk.logic.ai.AutorunGeneralPolicy(npcType = 2, nationId = 7),
            bodies = GeneralAiDoBodies(),
            nationHooks = nationHooks,
        )
        // smoke: the rate hook runs through the factory-wired path with no draw.
        nationHooks.chooseTexRate()
        assertEquals(15, ctx.let { RatesPromoFamily.bodies(it).chooseTexRate() })
    }
}
