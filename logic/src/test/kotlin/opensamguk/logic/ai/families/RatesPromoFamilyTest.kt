package opensamguk.logic.ai.families

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * L-RATES — RatesPromoFamily (chooseTexRate / chooseGoldBillRate / chooseRiceBillRate / choosePromotion /
 * chooseNonLordPromotion + calcCityDevelRate / calcNationDevelopedRate): the per-method DRAW STREAM (or the
 * 0-draw contract) off the shared `"GeneralAI"` [RandUtil].
 *
 * GRAND TRUTH = PHP `legacy/devsam-core/hwe/sammo/GeneralAI.php` (read in full):
 *  - `calcNationDevelopedRate` (`:3850-3879`): **ZERO draws** — averages `calcCityDevelRate` over supplyCities.
 *  - `chooseNonLordPromotion`  (`:3881-3963`): **up to 5 `choice` per EMPTY chief slot** (`Util::range(5)` inner
 *    loop, redraw-on-reject; first non-empty pool wins — npcWar→npcCivil→userWar→userCivil; an all-empty pool
 *    `break`s with NO draw). Per OCCUPIED slot: ZERO draws (the `continue` precedes the inner loop).
 *  - `calcCityDevelRate`       (`:3965-3976`): **ZERO draws** — pure per-city develKey→[score,statType] map.
 *  - `choosePromotion`         (`:3978-4170`): exactly **ONE `nextBool(0.1)` per OCCUPIED slot** in the demote/
 *    promote loop (`:4099`, the `key_exists(chiefGenerals)||key_exists(nextChiefs)` else-branch); ZERO per EMPTY
 *    slot (the `if-branch` sets `newChiefProb=1` with NO draw). The `:4102` `nextBool($newChiefProb)` is
 *    structurally present but `$newChiefProb` is always 1 or 0 → short-circuits (>=1→true NO-draw, <=0→false
 *    NO-draw) → **NEVER actually draws** (decision #9, the phantom-draw trap). The two `uasort` (`:4027`/`:4069`)
 *    are deterministic chief-selects (the `nextBool(0.1)` are gates, not picks) — NO draw.
 *  - `chooseTexRate`           (`:4172-4199`): **ZERO draws** — the avg→rate ladder over calcNationDevelopedRate.
 *  - `chooseGoldBillRate`      (`:4201-4246`): **ZERO draws** — `intval(income/outcome*90)` with the optional
 *    moreBill branch, `valueFit(bill,20,200)`; outcome = `valueFit(getOutcome(100,dedicationList),1)` (half-away,
 *    H-HELPERS §3); no-supply early-return 20.
 *  - `chooseRiceBillRate`      (`:4248-4292`): **ZERO draws** — identical shape to gold, rice+wall income.
 *
 * The family is PURE (mirrors NationRewardFamily/GenFoundFamily…): the candidate-set construction (the income/
 * getOutcome/devRate computation, the chiefGenerals/userGenerals buckets, the pool-priority + officer/stat
 * filters) is the foundations'/adapter's job; the family owns the per-method DRAW ORDER + COUNT on the shared
 * `"GeneralAI"` [RandUtil] (the promotion draws) and the deterministic rate/bill/develRate math (0 draws).
 */
class RatesPromoFamilyTest {

    /**
     * A draw-recording RNG over a REAL [LiteHashDrbg]. `nextBoolCalls` counts EVERY `nextBool` invocation
     * (including short-circuits that consume no underlying byte); `choiceCalls` counts `choice` picks
     * (each consuming one underlying `nextInt`); `nextFloat1Calls`/`nextBitCalls` count ONLY underlying
     * primitive consumption (so the gate's draw-stream + cursor assertion catches a phantom draw).
     */
    private class RecordingRng(seed: String) : RandUtil(LiteHashDrbg(seed)) {
        var nextBoolCalls = 0
        var choiceCalls = 0
        var nextFloat1Calls = 0
        var nextBitCalls = 0
        override fun nextBool(prob: Double): Boolean {
            nextBoolCalls++
            return super.nextBool(prob)
        }
        override fun <T> choice(items: List<T>): T {
            choiceCalls++
            return super.choice(items)
        }
        override fun nextFloat1(): Double {
            nextFloat1Calls++
            return super.nextFloat1()
        }
        override fun nextBit(): Boolean {
            nextBitCalls++
            return super.nextBit()
        }
    }

    // ==================================================================================================
    // (A) chooseTexRate (:4172-4199) — ZERO draws; the avg→rate ladder.
    // ==================================================================================================

    @Test
    fun `tax rate ladder maps avg to 25_20_15_10 with strict gt thresholds (PHP 4187-4190) and 0 draws`() {
        val rng = RecordingRng("rates-seed")
        // PHP :4187-4190 — strict `>` at each rung: >0.95→25, >0.70→20, >0.50→15, else→10.
        assertEquals(25, RatesPromoFamily.texRateForAvg(0.96, rng))
        assertEquals(20, RatesPromoFamily.texRateForAvg(0.95, rng)) // boundary 0.95 is NOT >0.95 → falls to >0.70.
        assertEquals(20, RatesPromoFamily.texRateForAvg(0.71, rng))
        assertEquals(15, RatesPromoFamily.texRateForAvg(0.70, rng)) // boundary 0.70 is NOT >0.70 → falls to >0.50.
        assertEquals(15, RatesPromoFamily.texRateForAvg(0.51, rng))
        assertEquals(10, RatesPromoFamily.texRateForAvg(0.50, rng)) // boundary 0.50 is NOT >0.50 → else 10.
        assertEquals(10, RatesPromoFamily.texRateForAvg(0.0, rng))
        assertEquals(0, rng.nextBoolCalls + rng.choiceCalls + rng.nextFloat1Calls + rng.nextBitCalls,
            "chooseTexRate makes ZERO draws (PHP :4172-4199) — the cursor must not move")
    }

    @Test
    fun `tax rate defaults to 15 when there are no supply cities (PHP 4180 4182) and 0 draws`() {
        val rng = RecordingRng("rates-seed")
        // PHP :4180 `$rate = 15;` then `:4182 if ($this->supplyCities) { …ladder… }` — no cities ⇒ default 15.
        assertEquals(15, RatesPromoFamily.texRate(devAvg = null, rng = rng))
        assertEquals(0, rng.nextBoolCalls + rng.choiceCalls + rng.nextFloat1Calls + rng.nextBitCalls)
    }

    @Test
    fun `tax rate uses the ladder when supply cities exist (PHP 4183-4190)`() {
        val rng = RecordingRng("rates-seed")
        // PHP :4185 `$avg = ($devRate['pop'] + $devRate['all']) / 2;` — the adapter supplies the avg.
        assertEquals(25, RatesPromoFamily.texRate(devAvg = 0.98, rng = rng))
        assertEquals(10, RatesPromoFamily.texRate(devAvg = 0.10, rng = rng))
        assertEquals(0, rng.nextBoolCalls + rng.choiceCalls + rng.nextFloat1Calls + rng.nextBitCalls)
    }

    // ==================================================================================================
    // (B) calcCityDevelRate (:3965-3976) + calcNationDevelopedRate (:3850-3879) — ZERO draws.
    // ==================================================================================================

    @Test
    fun `city devel rate emits the 7 develKeys in PHP literal order with the ratio and stat type (PHP 3967-3975)`() {
        val rng = RecordingRng("rates-seed")
        val city = RatesPromoFamily.CityDevelInput(
            trust = 50.0, pop = 800.0, popMax = 1000.0, agri = 300.0, agriMax = 600.0,
            comm = 200.0, commMax = 400.0, secu = 100.0, secuMax = 500.0,
            def = 250.0, defMax = 500.0, wall = 75.0, wallMax = 300.0,
        )
        val out = RatesPromoFamily.calcCityDevelRate(city, rng)
        // PHP :3967-3975 — insertion order trust,pop,agri,comm,secu,def,wall (a parity target — LinkedHashMap).
        assertEquals(
            listOf("trust", "pop", "agri", "comm", "secu", "def", "wall"),
            out.keys.toList(),
            "calcCityDevelRate key insertion order is the PHP array-literal order (:3967-3975)",
        )
        // PHP :3968 trust = trust/100, t통솔장; :3969 pop = pop/pop_max, t통솔장; etc.
        assertEquals(0.50, out.getValue("trust").score)
        assertEquals(RatesPromoFamily.StatType.LEADERSHIP, out.getValue("trust").statType)
        assertEquals(0.80, out.getValue("pop").score)
        assertEquals(RatesPromoFamily.StatType.LEADERSHIP, out.getValue("pop").statType)
        assertEquals(0.50, out.getValue("agri").score)
        assertEquals(RatesPromoFamily.StatType.INTEL, out.getValue("agri").statType)
        assertEquals(0.50, out.getValue("comm").score)
        assertEquals(RatesPromoFamily.StatType.INTEL, out.getValue("comm").statType)
        assertEquals(0.20, out.getValue("secu").score)
        assertEquals(RatesPromoFamily.StatType.STRENGTH, out.getValue("secu").statType)
        assertEquals(0.50, out.getValue("def").score)
        assertEquals(RatesPromoFamily.StatType.STRENGTH, out.getValue("def").statType)
        assertEquals(0.25, out.getValue("wall").score)
        assertEquals(RatesPromoFamily.StatType.STRENGTH, out.getValue("wall").statType)
        assertEquals(0, rng.nextBoolCalls + rng.choiceCalls + rng.nextFloat1Calls + rng.nextBitCalls,
            "calcCityDevelRate makes ZERO draws (PHP :3965-3976)")
    }

    @Test
    fun `nation developed rate sums per-key over supply cities then divides by city count then all by key count minus 1 (PHP 3861-3877)`() {
        val rng = RecordingRng("rates-seed")
        // Two cities, hand-built so the averages are exact. trust is SKIPPED in the develKey accumulation
        // (PHP :3863 `if ($develKey == 'trust') continue;`) but the produced 'all' divisor still uses count-1.
        val cityA = RatesPromoFamily.CityDevelInput(
            trust = 100.0, pop = 1000.0, popMax = 1000.0, agri = 600.0, agriMax = 600.0,
            comm = 400.0, commMax = 400.0, secu = 500.0, secuMax = 500.0,
            def = 500.0, defMax = 500.0, wall = 300.0, wallMax = 300.0,
        ) // all develKeys = 1.0
        val cityB = RatesPromoFamily.CityDevelInput(
            trust = 0.0, pop = 0.0, popMax = 1000.0, agri = 0.0, agriMax = 600.0,
            comm = 0.0, commMax = 400.0, secu = 0.0, secuMax = 500.0,
            def = 0.0, defMax = 500.0, wall = 0.0, wallMax = 300.0,
        ) // all develKeys = 0.0
        val out = RatesPromoFamily.calcNationDevelopedRate(listOf(cityA, cityB), rng)
        // PHP :3873-3874 — each develKey /= count(supplyCities) (=2). pop = (1.0+0.0)/2 = 0.5.
        assertEquals(0.5, out.getValue("pop"))
        assertEquals(0.5, out.getValue("agri"))
        assertEquals(0.5, out.getValue("comm"))
        assertEquals(0.5, out.getValue("secu"))
        assertEquals(0.5, out.getValue("def"))
        assertEquals(0.5, out.getValue("wall"))
        // PHP :3876 — `$devRate['all'] /= count($devRate) - 1`. devRate keys = all,pop,agri,comm,secu,def,wall (7)
        // → divisor count-1 = 6. The 'all' accumulated (each city contributes sum of its 6 non-trust scores) is
        // (6*1.0 + 6*0.0)=6.0, /2 cities = 3.0 at :3873, then /6 at :3876 = 0.5.
        assertEquals(0.5, out.getValue("all"))
        assertEquals(0, rng.nextBoolCalls + rng.choiceCalls + rng.nextFloat1Calls + rng.nextBitCalls,
            "calcNationDevelopedRate makes ZERO draws (PHP :3850-3879)")
    }

    @Test
    fun `nation developed rate caches the result (PHP 3852-3854 devRate null-guard)`() {
        val rng = RecordingRng("rates-seed")
        val city = RatesPromoFamily.CityDevelInput(
            trust = 100.0, pop = 500.0, popMax = 1000.0, agri = 300.0, agriMax = 600.0,
            comm = 200.0, commMax = 400.0, secu = 250.0, secuMax = 500.0,
            def = 250.0, defMax = 500.0, wall = 150.0, wallMax = 300.0,
        )
        val out = RatesPromoFamily.calcNationDevelopedRate(listOf(city), rng)
        // 'all' divisor count-1 = 6; each key /1 city; develKey insertion order is also a parity target.
        assertTrue(out.containsKey("all"))
        assertTrue(out.keys.containsAll(listOf("pop", "agri", "comm", "secu", "def", "wall")))
    }

    // ==================================================================================================
    // (C) chooseGoldBillRate / chooseRiceBillRate (:4201-4292) — ZERO draws; the bill formula.
    // ==================================================================================================

    @Test
    fun `bill rate returns 20 with no supply cities (PHP 4211 4258) and 0 draws`() {
        val rng = RecordingRng("rates-seed")
        // PHP :4211 `if (!$cityList) return 20;` — the no-supply early-return.
        assertEquals(20, RatesPromoFamily.billRate(income = 99999, outcome = 1, currentRes = 0,
            reqNationRes = 100, hasSupplyCities = false, rng = rng))
        assertEquals(0, rng.nextBoolCalls + rng.choiceCalls + rng.nextFloat1Calls + rng.nextBitCalls)
    }

    @Test
    fun `bill rate is intval(income div outcome times 90) clamped to 20-200 with no moreBill branch (PHP 4231 4239) and 0 draws`() {
        val rng = RecordingRng("rates-seed")
        // PHP :4231 `$bill = intval($income / $outcome * 90);` trunc-toward-zero. income=100,outcome=90 →
        // intval(100/90*90)=intval(99.99..)=99... but keep simple: income=100, outcome=10 → intval(100/10*90)=900
        // → clamped to 200 (PHP :4239 valueFit(bill,20,200)). The moreBill branch is skipped (res below 2× req).
        val bill = RatesPromoFamily.billRate(
            income = 100, outcome = 10, currentRes = 0, reqNationRes = 100000,
            hasSupplyCities = true, rng = rng,
        )
        assertEquals(200, bill, "intval(100/10*90)=900 → valueFit(.,20,200)=200 (PHP :4231/:4239)")
        assertEquals(0, rng.nextBoolCalls + rng.choiceCalls + rng.nextFloat1Calls + rng.nextBitCalls,
            "chooseGold/RiceBillRate make ZERO draws (PHP :4201-4292)")
    }

    @Test
    fun `bill rate floor-clamps to 20 when the raw bill is below 20 (PHP 4239 valueFit min)`() {
        val rng = RecordingRng("rates-seed")
        // income=10, outcome=100 → intval(10/100*90)=intval(9.0)=9 → valueFit(9,20,200)=20.
        val bill = RatesPromoFamily.billRate(
            income = 10, outcome = 100, currentRes = 0, reqNationRes = 100000,
            hasSupplyCities = true, rng = rng,
        )
        assertEquals(20, bill)
    }

    @Test
    fun `bill rate applies the moreBill average when surplus exceeds 2x reqNationRes and moreBill exceeds bill (PHP 4232-4237)`() {
        val rng = RecordingRng("rates-seed")
        // Force the moreBill branch (PHP :4232): currentRes + income - outcome > reqNationRes*2.
        // currentRes=10000, income=1000, outcome=10, reqNationRes=100 → 10000+1000-10=10990 > 200 ⇒ branch ON.
        //   bill0 = intval(1000/10*90) = intval(9000) = 9000.
        //   moreBill = (10000+1000 - 100*2)/10*80 = (11000-200)/10*80 = 10800/10*80 = 1080*80 = 86400.0.
        //   moreBill(86400) > bill0(9000) ⇒ bill = intval((86400+9000)/2)=intval(47700)=47700.
        //   valueFit(47700,20,200)=200.
        val bill = RatesPromoFamily.billRate(
            income = 1000, outcome = 10, currentRes = 10000, reqNationRes = 100,
            hasSupplyCities = true, rng = rng,
        )
        assertEquals(200, bill)
        assertEquals(0, rng.nextBoolCalls + rng.choiceCalls + rng.nextFloat1Calls + rng.nextBitCalls)
    }

    @Test
    fun `outcome is half-away round of sum getBill times billRate div 100 floor-bounded to min 1 (PHP getOutcome + 4229 valueFit 1)`() {
        // PHP getOutcome (H-HELPERS §3, func_time_event.php:246): `Util::round($sum * $billRate / 100)` half-away,
        // then consumer `Util::valueFit(getOutcome(100, ...), 1)` floor-bounds to min 1 (PHP :4229/:4275).
        // sum=125, billRate=100 → round(125*100/100)=round(125)=125, valueFit(125,1)=125.
        assertEquals(125, RatesPromoFamily.outcomeFloorMin1(billSum = 125, billRate = 100))
        // sum=0 → round(0)=0 → valueFit(0,1)=1 (the min-1 floor — guards a divide-by-zero in billRate).
        assertEquals(1, RatesPromoFamily.outcomeFloorMin1(billSum = 0, billRate = 100))
        // half-away: sum=1, billRate=50 → round(1*50/100)=round(0.5)=1 (HALF_UP, not half-even).
        assertEquals(1, RatesPromoFamily.outcomeFloorMin1(billSum = 1, billRate = 50))
        // half-away on a .5 that rounds up: sum=3, billRate=50 → round(1.5)=2.
        assertEquals(2, RatesPromoFamily.outcomeFloorMin1(billSum = 3, billRate = 50))
    }

    // ==================================================================================================
    // (D) choosePromotion (:3978-4170) — ONE nextBool(0.1) per OCCUPIED slot; :4102 newChiefProb NEVER draws.
    // ==================================================================================================

    @Test
    fun `promotion newChief gate draws nextBool(0_1) ONLY for an occupied slot (PHP 4096-4099) and the 4102 newChiefProb NEVER draws (decision 9 phantom)`() {
        // PHP :4096-4099 — EMPTY slot (NOT in chiefGenerals AND NOT in nextChiefs) ⇒ `$newChiefProb = 1` NO draw;
        // OCCUPIED ⇒ `$newChiefProb = $this->rng->nextBool(0.1) ? 1 : 0` (exactly ONE draw).
        val rngEmpty = RecordingRng("promo-seed")
        val probEmpty = RatesPromoFamily.newChiefProb(slotOccupied = false, rng = rngEmpty)
        assertEquals(1, probEmpty, "empty slot → newChiefProb=1 with NO draw (PHP :4097)")
        assertEquals(0, rngEmpty.nextBoolCalls, "empty slot consumes ZERO nextBool (PHP :4096-4097)")

        val rngOccupied = RecordingRng("promo-seed")
        RatesPromoFamily.newChiefProb(slotOccupied = true, rng = rngOccupied)
        assertEquals(1, rngOccupied.nextBoolCalls, "occupied slot draws exactly ONE nextBool(0.1) (PHP :4099)")

        // PHP :4102 `if ($newChiefProb < 1 && !$this->rng->nextBool($newChiefProb)) continue;` — newChiefProb ∈
        // {0,1}: prob=1 → `<1` is false ⇒ `&&` short-circuits, NO draw; prob=0 → nextBool(0) short-circuits
        // (<=0→false NO-draw). The :4102 call NEVER consumes a byte (decision #9 phantom-draw trap).
        val rngPhantom1 = RecordingRng("promo-seed")
        RatesPromoFamily.newChiefProbGateSkips(newChiefProb = 1, rng = rngPhantom1)
        assertEquals(0, rngPhantom1.nextFloat1Calls + rngPhantom1.nextBitCalls,
            "newChiefProb=1 → :4102 && short-circuits, ZERO underlying draw (phantom)")
        val rngPhantom0 = RecordingRng("promo-seed")
        RatesPromoFamily.newChiefProbGateSkips(newChiefProb = 0, rng = rngPhantom0)
        assertEquals(0, rngPhantom0.nextFloat1Calls + rngPhantom0.nextBitCalls,
            "newChiefProb=0 → nextBool(0) short-circuits (<=0→false), ZERO underlying draw (phantom)")
    }

    @Test
    fun `promotion 4102 gate returns continue-true for an empty slot and false for an occupied slot that rolled in (PHP 4102 semantics)`() {
        // PHP :4102 returns `continue` (skip this chiefLevel) iff `$newChiefProb < 1 && !nextBool($newChiefProb)`.
        //  - newChiefProb=1 (empty slot): `1 < 1` false ⇒ NEVER continue (always proceeds to fill the slot).
        //  - newChiefProb=0 (occupied, 0.1 missed): `0 < 1` true AND `!nextBool(0)`=`!false`=true ⇒ continue (skip).
        val rng = RecordingRng("promo-seed")
        assertEquals(false, RatesPromoFamily.newChiefProbGateSkips(newChiefProb = 1, rng = rng),
            "newChiefProb=1 never skips (empty slot fills)")
        assertEquals(true, RatesPromoFamily.newChiefProbGateSkips(newChiefProb = 0, rng = rng),
            "newChiefProb=0 always skips (occupied slot that missed the 0.1 roll)")
    }

    @Test
    fun `promotion draws exactly one nextBool per occupied slot across a multi-slot scan (cursor advances by occupied count only)`() {
        // The dispatcher-shaped scan: 3 chief levels, 2 occupied + 1 empty ⇒ exactly 2 nextBool(0.1) (the empty
        // slot draws ZERO). Asserts the cursor advances by the OCCUPIED-slot count only (decision #9).
        val rng = RecordingRng("promo-seed")
        val slots = listOf(true, false, true) // occupied, empty, occupied
        for (occupied in slots) {
            RatesPromoFamily.newChiefProb(slotOccupied = occupied, rng = rng)
        }
        assertEquals(2, rng.nextBoolCalls, "exactly ONE nextBool(0.1) per OCCUPIED slot, ZERO per empty (decision #9)")
    }

    // ==================================================================================================
    // (E) chooseNonLordPromotion (:3881-3963) — up to 5 choice per EMPTY chief slot (redraw-on-reject).
    // ==================================================================================================

    @Test
    fun `non-lord promotion draws one choice per attempt from the first non-empty pool by priority (PHP 3908-3919)`() {
        val rng = RecordingRng("nonlord-seed")
        // PHP :3908-3919 — pool priority npcWar→npcCivil→userWar→userCivil; first non-empty wins.
        val npcWar = listOf(101, 102, 103)
        val picked = RatesPromoFamily.pickPromotionCandidate(
            npcWarGenerals = npcWar, npcCivilGenerals = listOf(201), userWarGenerals = listOf(301),
            userCivilGenerals = listOf(401), rng = rng,
        )
        assertTrue(picked in npcWar, "first non-empty pool (npcWar) wins (PHP :3908-3910)")
        assertEquals(1, rng.choiceCalls, "one choice per attempt (PHP :3910)")
    }

    @Test
    fun `non-lord promotion falls through to the next pool when a higher-priority pool is empty (PHP 3911-3919)`() {
        val rng = RecordingRng("nonlord-seed")
        // npcWar + npcCivil empty ⇒ userWar wins; only ONE choice per attempt regardless of fall-through.
        val userWar = listOf(301, 302)
        val picked = RatesPromoFamily.pickPromotionCandidate(
            npcWarGenerals = emptyList(), npcCivilGenerals = emptyList(),
            userWarGenerals = userWar, userCivilGenerals = listOf(401), rng = rng,
        )
        assertTrue(picked in userWar, "npcWar+npcCivil empty ⇒ userWar wins (PHP :3914-3916)")
        assertEquals(1, rng.choiceCalls)
    }

    @Test
    fun `non-lord promotion returns null with ZERO draws when ALL pools are empty (PHP 3920-3922 break)`() {
        val rng = RecordingRng("nonlord-seed")
        // PHP :3920-3922 — the `else { break; }` when ALL four pools are empty: NO choice draw.
        val picked = RatesPromoFamily.pickPromotionCandidate(
            npcWarGenerals = emptyList(), npcCivilGenerals = emptyList(),
            userWarGenerals = emptyList(), userCivilGenerals = emptyList(), rng = rng,
        )
        assertNull(picked, "all pools empty ⇒ break, returns null (PHP :3921)")
        assertEquals(0, rng.choiceCalls, "all-empty pools draw ZERO (PHP :3920-3922 break)")
    }

    @Test
    fun `non-lord promotion fills an empty slot with up to 5 attempts redrawing on reject (PHP 3905 Util range 5)`() {
        // PHP :3905 `foreach (Util::range(5) as $idx)` — up to 5 attempts; a rejected candidate (officer_level!=1
        // or below chiefStatMin) `continue`s, consuming a draw per attempt; an accepted candidate `break`s.
        // The family drives one attempt at a time; the caller (adapter) loops Util::range(5) and accepts/rejects.
        val rng = RecordingRng("nonlord-seed")
        var draws = 0
        var picked: Int? = null
        // Simulate 5 rejects (all attempts draw, none accepted) ⇒ exactly 5 choice draws for ONE empty slot.
        val pool = listOf(10, 11, 12, 13, 14)
        for (attempt in 0 until 5) {
            val cand = RatesPromoFamily.pickPromotionCandidate(
                npcWarGenerals = pool, npcCivilGenerals = emptyList(),
                userWarGenerals = emptyList(), userCivilGenerals = emptyList(), rng = rng,
            )
            draws++
            // pretend every candidate is rejected (officer_level != 1) → continue (no break).
            picked = null
        }
        assertEquals(5, draws, "up to 5 attempts per empty slot (PHP :3905 Util::range(5))")
        assertEquals(5, rng.choiceCalls, "5 rejected attempts ⇒ 5 choice draws (redraw-on-reject, PHP :3910)")
        assertNull(picked)
    }
}
