package opensamguk.logic.ai.families

import opensamguk.common.rng.RandUtil
import opensamguk.logic.util.clamp
import opensamguk.logic.util.phpRound
import opensamguk.logic.util.phpToInt

/**
 * L-RATES — the rate / promotion `do<한글>`-helper family: chooseTexRate / chooseGoldBillRate /
 * chooseRiceBillRate / choosePromotion / chooseNonLordPromotion + calcCityDevelRate / calcNationDevelopedRate.
 *
 * GRAND TRUTH = PHP `legacy/devsam-core/hwe/sammo/GeneralAI.php` (read in full):
 *  - `calcNationDevelopedRate` (`:3850-3879`): **ZERO draws** — averages [calcCityDevelRate] over supplyCities.
 *  - `chooseNonLordPromotion`  (`:3881-3963`): **up to 5 `choice` per EMPTY chief slot** (`Util::range(5)` inner
 *    loop, redraw-on-reject; first non-empty pool wins — npcWar→npcCivil→userWar→userCivil; all-empty `break`s
 *    with NO draw). Per OCCUPIED slot: ZERO draws (the slot's `continue` precedes the inner loop).
 *  - `calcCityDevelRate`       (`:3965-3976`): **ZERO draws** — pure per-city `develKey → [score, statType]` map.
 *  - `choosePromotion`         (`:3978-4170`): exactly **ONE `nextBool(0.1)` per OCCUPIED slot** in the demote/
 *    promote loop (`:4099`); ZERO per EMPTY slot (`:4097` sets `newChiefProb = 1` with NO draw). The `:4102`
 *    `nextBool($newChiefProb)` is structurally present but `$newChiefProb` is always 1 or 0 → short-circuits
 *    (`>=1`→true NO-draw, `<=0`→false NO-draw) → **NEVER actually draws** (decision #9, the phantom-draw trap).
 *    The two `uasort` (`:4027`/`:4069`) are deterministic chief-selects — NO draw.
 *  - `chooseTexRate`           (`:4172-4199`): **ZERO draws** — the `avg → rate` ladder over the dev rate.
 *  - `chooseGoldBillRate`      (`:4201-4246`): **ZERO draws** — `intval(income/outcome*90)` + the optional
 *    moreBill branch, `valueFit(bill, 20, 200)`; outcome = `valueFit(getOutcome(100, dedicationList), 1)`
 *    (half-away, H-HELPERS §3); no-supply early-return 20.
 *  - `chooseRiceBillRate`      (`:4248-4292`): **ZERO draws** — identical shape to gold (rice + wall income).
 *
 * This file holds the PURE primitives each method composes (it mirrors the established
 * `NationRewardFamily`/`GenFoundFamily`/… pure-helper shape): the candidate-set construction — the income /
 * `getOutcome` / dev-rate computation, the chiefGenerals/userGenerals buckets, the officer/stat pool filters —
 * is the foundations'/adapter's job; the family owns the per-method DRAW ORDER + COUNT on the shared
 * `"GeneralAI"` [RandUtil] (the promotion draws) and the deterministic rate/bill/develRate math (0 draws).
 *
 * ## The load-bearing parity facts (catalog §5.P/§5.Q, decision #9, H-HELPERS §3/§5)
 *  1. **The tax/bill rate helpers + the two develRate helpers make ZERO draws.** `phpRound` is half-AWAY
 *     (`getOutcome`, H-HELPERS §3); `intval` is trunc-toward-zero ([phpToInt]); `valueFit` is a pure clamp
 *     (NO round). NEVER `Math.round`. Any accidental RNG in a 0-draw helper desyncs the whole decision.
 *  2. **`choosePromotion` draws exactly ONE `nextBool(0.1)` per OCCUPIED slot, ZERO per empty slot.** The
 *     `:4102` `nextBool($newChiefProb)` NEVER draws — `$newChiefProb ∈ {0,1}` short-circuits. A naive port
 *     that always calls `:4102` adds a phantom draw (decision #9).
 *  3. **`chooseNonLordPromotion` draws up to 5 `choice` per EMPTY slot** (redraw-on-reject); an all-empty
 *     pool returns null with ZERO draws (the `break`). `choice` walks the first non-empty pool by priority.
 */
object RatesPromoFamily {

    // ==================================================================================================
    // calcCityDevelRate (:3965-3976) + calcNationDevelopedRate (:3850-3879) — ZERO draws.
    // ==================================================================================================

    /** The develKey stat-type tags (PHP `self::t통솔장`/`t지장`/`t무장`) — pure labels, no draw effect. */
    enum class StatType { LEADERSHIP, INTEL, STRENGTH }

    /** One develKey's score + the stat-type it weights (PHP `[$ratio, self::t…]`, `:3968-3974`). */
    data class DevelScore(val score: Double, val statType: StatType)

    /**
     * The raw per-city fields `calcCityDevelRate` reads (PHP `$city['trust']`/`pop`/`pop_max`/…). Doubles to
     * mirror PHP's float division exactly (no integer truncation in the ratios).
     */
    data class CityDevelInput(
        val trust: Double,
        val pop: Double, val popMax: Double,
        val agri: Double, val agriMax: Double,
        val comm: Double, val commMax: Double,
        val secu: Double, val secuMax: Double,
        val def: Double, val defMax: Double,
        val wall: Double, val wallMax: Double,
    )

    /**
     * `calcCityDevelRate(array $city)` (PHP `:3965-3976`): the per-city `develKey → [ratio, statType]` map.
     * ZERO draws — [rng] is accepted only to document the 0-draw contract at the call site (a reviewer must
     * not "fix" this by inserting a draw; the cursor is a parity target).
     *
     * The key INSERTION order — `trust, pop, agri, comm, secu, def, wall` — is a parity target (a
     * `LinkedHashMap`, the PHP array-literal order, `:3968-3974`); `calcNationDevelopedRate` SKIPS `trust`
     * but iterates the rest in this order, and the produced `'all'` divisor `count(devRate) - 1` rides on it.
     */
    @Suppress("UNUSED_PARAMETER")
    fun calcCityDevelRate(city: CityDevelInput, rng: RandUtil): LinkedHashMap<String, DevelScore> =
        linkedMapOf(
            "trust" to DevelScore(city.trust / 100, StatType.LEADERSHIP), // PHP :3968 — /100, t통솔장.
            "pop" to DevelScore(city.pop / city.popMax, StatType.LEADERSHIP), // PHP :3969 — pop/pop_max, t통솔장.
            "agri" to DevelScore(city.agri / city.agriMax, StatType.INTEL), // PHP :3970 — agri/agri_max, t지장.
            "comm" to DevelScore(city.comm / city.commMax, StatType.INTEL), // PHP :3971 — comm/comm_max, t지장.
            "secu" to DevelScore(city.secu / city.secuMax, StatType.STRENGTH), // PHP :3972 — secu/secu_max, t무장.
            "def" to DevelScore(city.def / city.defMax, StatType.STRENGTH), // PHP :3973 — def/def_max, t무장.
            "wall" to DevelScore(city.wall / city.wallMax, StatType.STRENGTH), // PHP :3974 — wall/wall_max, t무장.
        )

    /**
     * `calcNationDevelopedRate()` (PHP `:3850-3879`): averages [calcCityDevelRate] over the supply cities.
     * ZERO draws.
     *
     * The accumulation (PHP `:3861-3872`):
     *  - seed `devRate = ['all' => 0]` (PHP `:3856-3858`);
     *  - per supply city, per develKey EXCEPT `trust` (PHP `:3863-3864` `continue`): `devRate[key] += score`
     *    AND `devRate['all'] += score`; new keys are created lazily in first-seen order (PHP `:3866-3868`),
     *    which — `trust` skipped — is `all, pop, agri, comm, secu, def, wall` (7 keys);
     *  - then per key `devRate[key] /= count(supplyCities)` (PHP `:3873-3874`);
     *  - finally `devRate['all'] /= count(devRate) - 1` (PHP `:3876`, the `-1` excludes `'all'` itself).
     *
     * @param supplyCities the nation's supply cities (PHP `$this->supplyCities`, DB-row insertion order).
     * @return the averaged `develKey → rate` map; `'all'` is the cross-key average (key insertion order
     *  `all, pop, agri, comm, secu, def, wall` preserved — a parity target). Empty list ⇒ PHP divides by 0
     *  (NaN/INF); the AI only calls this when `supplyCities` is non-empty (PHP `:4182`/`:4209` guard).
     */
    @Suppress("UNUSED_PARAMETER")
    fun calcNationDevelopedRate(supplyCities: List<CityDevelInput>, rng: RandUtil): LinkedHashMap<String, Double> {
        val devRate = LinkedHashMap<String, Double>()
        devRate["all"] = 0.0 // PHP :3856-3858 — seeded first (insertion-order head).
        for (city in supplyCities) {
            for ((develKey, score) in calcCityDevelRate(city, rng)) {
                if (develKey == "trust") continue // PHP :3863-3864 — trust excluded from the accumulation.
                if (!devRate.containsKey(develKey)) devRate[develKey] = 0.0 // PHP :3866-3868 — lazy first-seen.
                devRate[develKey] = devRate.getValue(develKey) + score.score // PHP :3869.
                devRate["all"] = devRate.getValue("all") + score.score // PHP :3870.
            }
        }
        val cityCount = supplyCities.size
        for (key in devRate.keys.toList()) {
            devRate[key] = devRate.getValue(key) / cityCount // PHP :3873-3874 — /= count(supplyCities).
        }
        devRate["all"] = devRate.getValue("all") / (devRate.size - 1) // PHP :3876 — /= count(devRate) - 1.
        return devRate
    }

    // ==================================================================================================
    // chooseTexRate (:4172-4199) — ZERO draws; the avg→rate ladder.
    // ==================================================================================================

    /**
     * `chooseTexRate`'s `avg → rate` ladder (PHP `:4187-4190`): `>0.95→25`, `>0.70→20`, `>0.50→15`, else `10`.
     * The thresholds are STRICT `>` (a boundary value falls to the next-lower rung). ZERO draws — [rng] is
     * accepted only to document the 0-draw contract. `$avg = ($devRate['pop'] + $devRate['all']) / 2` (PHP
     * `:4185`) is computed by the adapter from [calcNationDevelopedRate].
     */
    @Suppress("UNUSED_PARAMETER")
    fun texRateForAvg(avg: Double, rng: RandUtil): Int = when {
        avg > 0.95 -> 25 // PHP :4187.
        avg > 0.70 -> 20 // PHP :4188.
        avg > 0.50 -> 15 // PHP :4189.
        else -> 10 // PHP :4190.
    }

    /**
     * `chooseTexRate()` (PHP `:4172-4199`): the default rate `15` (PHP `:4180`), overridden by the
     * [texRateForAvg] ladder ONLY when the nation has supply cities (PHP `:4182`). ZERO draws.
     *
     * @param devAvg the `($devRate['pop'] + $devRate['all']) / 2` average (PHP `:4185`), or null when there
     *  are no supply cities (PHP `:4182` false → keep the default 15).
     */
    @Suppress("UNUSED_PARAMETER")
    fun texRate(devAvg: Double?, rng: RandUtil): Int =
        if (devAvg == null) 15 else texRateForAvg(devAvg, rng) // PHP :4180 default 15; :4182 ladder if supply cities.

    // ==================================================================================================
    // chooseGoldBillRate / chooseRiceBillRate (:4201-4292) — ZERO draws; the bill formula.
    // ==================================================================================================

    /**
     * The consumer-side `outcome = Util::valueFit(getOutcome(100, $dedicationList), 1)` (PHP `:4229`/`:4275`):
     * `getOutcome` (H-HELPERS §3, `func_time_event.php:246`) = `Util::round($billSum * $billRate / 100)`
     * **half-AWAY** ([phpRound]), then floor-bounded to **min 1** (a `valueFit(.,1)` clamp, no upper) — the
     * min-1 floor guards the `income / outcome` divide in [billRate]. ZERO draws.
     *
     * @param billSum `Σ getBill(general.dedication)` over the `dedicationList` (PHP-int sum; the adapter builds
     *  it from the `npc != 5`-filtered `nationGenerals`, excluding self — H-HELPERS §3, the dead self-append).
     * @param billRate the `getOutcome` rate (the AI passes the literal `100` at `:4229`/`:4275`).
     */
    fun outcomeFloorMin1(billSum: Int, billRate: Int): Int =
        // PHP getOutcome :246 phpRound(sum*rate/100) half-away; consumer :4229 valueFit(.,1) min-1 (no upper).
        maxOf(1, phpRound(billSum.toDouble() * billRate / 100))

    /**
     * The shared `chooseGoldBillRate`/`chooseRiceBillRate` bill formula (PHP `:4201-4246`/`:4248-4292` — the
     * two are byte-identical apart from the income source). ZERO draws.
     *
     * Verbatim:
     *  - no supply cities ⇒ `return 20` (PHP `:4211`/`:4258`);
     *  - `bill = intval($income / $outcome * 90)` (PHP `:4231`/`:4277`, trunc-toward-zero [phpToInt]);
     *  - IF `currentRes + income - outcome > reqNationRes * 2` (PHP `:4232`/`:4278`):
     *    `moreBill = (currentRes + income - reqNationRes*2) / outcome * 80` (FLOAT, PHP `:4233`/`:4279`);
     *    IF `moreBill > bill` ⇒ `bill = intval((moreBill + bill) / 2)` (PHP `:4235`/`:4281`, trunc);
     *  - `bill = valueFit(bill, 20, 200)` (PHP `:4239`/`:4285`, pure clamp).
     *
     * @param income `goldIncome + warIncome` (gold) or `riceIncome + wallIncome` (rice) — the adapter computes
     *  it via the H-HELPERS §3 income helpers (per-city half-away, `* nation['rate']/20`).
     * @param outcome the [outcomeFloorMin1] value (PHP `:4229`/`:4275`, already floor-bounded to ≥1).
     * @param currentRes `$nation['gold']` (gold) or `$nation['rice']` (rice) (PHP `:4232`/`:4278`).
     * @param reqNationRes `$this->nationPolicy->reqNationGold` (gold) or `reqNationRice` (rice).
     * @param hasSupplyCities whether `$this->supplyCities` is non-empty (PHP `:4209`/`:4256` — `!$cityList → 20`).
     */
    @Suppress("UNUSED_PARAMETER")
    fun billRate(
        income: Int,
        outcome: Int,
        currentRes: Int,
        reqNationRes: Int,
        hasSupplyCities: Boolean,
        rng: RandUtil,
    ): Int {
        if (!hasSupplyCities) return 20 // PHP :4211/:4258 — `if (!$cityList) return 20;`.
        var bill = phpToInt(income.toDouble() / outcome * 90) // PHP :4231/:4277 — intval(income/outcome*90).
        if (currentRes + income - outcome > reqNationRes * 2) { // PHP :4232/:4278 — surplus over 2× req.
            val moreBill = (currentRes + income - reqNationRes * 2).toDouble() / outcome * 80 // PHP :4233/:4279.
            if (moreBill > bill) {
                bill = phpToInt((moreBill + bill) / 2) // PHP :4235/:4281 — intval((moreBill+bill)/2).
            }
        }
        return clamp(bill.toDouble(), 20.0, 200.0).toInt() // PHP :4239/:4285 — valueFit(bill, 20, 200).
    }

    // ==================================================================================================
    // choosePromotion (:3978-4170) — ONE nextBool(0.1) per OCCUPIED slot; :4102 newChiefProb NEVER draws.
    // ==================================================================================================

    /**
     * `choosePromotion`'s new-chief probability gate (PHP `:4096-4100`):
     * ```php
     * if (!key_exists($chiefLevel, $this->chiefGenerals) && !key_exists($chiefLevel, $nextChiefs)) {
     *     $newChiefProb = 1;                                   // :4097  EMPTY slot — NO draw
     * } else {
     *     $newChiefProb = $this->rng->nextBool(0.1) ? 1 : 0;   // :4099  OCCUPIED slot — exactly ONE nextBool(0.1)
     * }
     * ```
     * **decision #9 — exactly ONE `nextBool(0.1)` per OCCUPIED slot, ZERO per EMPTY slot.** The empty-slot
     * branch sets `newChiefProb = 1` deterministically (no draw); the occupied-slot branch draws one
     * `nextBool(0.1)` (prob `0.1` ∉ {0, 0.5, ≥1} → one `nextFloat1`).
     *
     * @param slotOccupied true when the chiefLevel is already in `chiefGenerals` OR `nextChiefs` (PHP `:4096`).
     * @return `1` (empty: keep / will-fill) or `nextBool(0.1) ? 1 : 0` (occupied: 10% churn).
     */
    fun newChiefProb(slotOccupied: Boolean, rng: RandUtil): Int =
        if (!slotOccupied) 1 // PHP :4097 — empty slot, newChiefProb=1, NO draw.
        else if (rng.nextBool(0.1)) 1 else 0 // PHP :4099 — occupied slot, exactly ONE nextBool(0.1).

    /**
     * `choosePromotion`'s `:4102` gate (PHP):
     * `if ($newChiefProb < 1 && !$this->rng->nextBool($newChiefProb)) continue;`.
     *
     * **decision #9 — the phantom-draw trap. The `nextBool($newChiefProb)` NEVER consumes a byte.**
     * `$newChiefProb` is always 1 or 0 (from [newChiefProb]):
     *  - `newChiefProb == 1` ⇒ `1 < 1` is false ⇒ the `&&` SHORT-CIRCUITS before `nextBool` ⇒ NO draw,
     *    gate is false (proceed to fill the slot);
     *  - `newChiefProb == 0` ⇒ `0 < 1` is true, then `nextBool(0)` short-circuits (`<=0 → false`, NO draw),
     *    `!false` is true ⇒ gate is true (`continue`, skip this chiefLevel).
     * Either way the `:4102` call reads ZERO bytes off the DRBG — a naive port that always invokes `nextBool`
     * here adds a phantom draw and desyncs every later draw. Modelled as a 0-byte-consuming `nextBool` so the
     * gate's draw-stream + cursor assertion stays cursor-for-cursor with PHP.
     *
     * @param newChiefProb the [newChiefProb] result (1 = empty/keep, 0 = occupied that missed the 0.1 roll).
     * @return true to `continue` (skip this chiefLevel) — only when `newChiefProb == 0`.
     */
    fun newChiefProbGateSkips(newChiefProb: Int, rng: RandUtil): Boolean =
        // PHP :4102 — `$newChiefProb < 1 && !nextBool($newChiefProb)`. nextBool(1)/nextBool(0) BOTH short-circuit
        // (>=1→true NO-draw, <=0→false NO-draw): the `&&` only reaches it when newChiefProb=0 (still 0-byte).
        newChiefProb < 1 && !rng.nextBool(newChiefProb.toDouble())

    // ==================================================================================================
    // chooseNonLordPromotion (:3881-3963) — up to 5 choice per EMPTY chief slot (redraw-on-reject).
    // ==================================================================================================

    /**
     * `chooseNonLordPromotion`'s single per-attempt candidate pick (PHP `:3908-3922`):
     * ```php
     * if ($this->npcWarGenerals)        $randGeneral = $this->rng->choice($this->npcWarGenerals);   // :3908-3910
     * else if ($this->npcCivilGenerals) $randGeneral = $this->rng->choice($this->npcCivilGenerals); // :3911-3913
     * else if ($this->userWarGenerals)  $randGeneral = $this->rng->choice($this->userWarGenerals);  // :3914-3916
     * else if ($this->userCivilGenerals)$randGeneral = $this->rng->choice($this->userCivilGenerals);// :3917-3919
     * else break;                                                                                    // :3920-3922
     * ```
     * **First non-empty pool wins** (npcWar → npcCivil → userWar → userCivil). Exactly ONE `choice` draw per
     * attempt; an all-empty pool `break`s with NO draw (returns null here). The outer `Util::range(5)` retry
     * loop (PHP `:3905`) + the officer_level/stat accept-reject (PHP `:3924-3943`) live in the adapter — it
     * calls this once per attempt (up to 5 per EMPTY chief slot, redraw-on-reject), accepts on a `break`
     * candidate, and `continue`s (re-draws) on a reject. ZERO draws happen for an OCCUPIED slot (the slot's
     * own `continue` at PHP `:3890-3898` precedes this loop).
     *
     * @param npcWarGenerals/npcCivilGenerals/userWarGenerals/userCivilGenerals the four pools (PHP insertion
     *  order = the bucket build order, a parity target). Pass the SAME bucket lists `categorizeNationGeneral`
     *  produced; do NOT re-sort.
     * @return the picked candidate id (one `choice` draw), or null when ALL pools are empty (PHP `:3921` break,
     *  ZERO draws).
     */
    fun pickPromotionCandidate(
        npcWarGenerals: List<Int>,
        npcCivilGenerals: List<Int>,
        userWarGenerals: List<Int>,
        userCivilGenerals: List<Int>,
        rng: RandUtil,
    ): Int? = when {
        npcWarGenerals.isNotEmpty() -> rng.choice(npcWarGenerals) // PHP :3908-3910 — 1st: npc war.
        npcCivilGenerals.isNotEmpty() -> rng.choice(npcCivilGenerals) // PHP :3911-3913 — 2nd: npc civil.
        userWarGenerals.isNotEmpty() -> rng.choice(userWarGenerals) // PHP :3914-3916 — 3rd: user war.
        userCivilGenerals.isNotEmpty() -> rng.choice(userCivilGenerals) // PHP :3917-3919 — 4th: user civil.
        else -> null // PHP :3920-3922 — all pools empty ⇒ break, NO draw.
    }
}
