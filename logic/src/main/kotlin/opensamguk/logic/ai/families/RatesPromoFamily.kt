package opensamguk.logic.ai.families

import opensamguk.common.constants.GameConst
import opensamguk.common.rng.RandUtil
import opensamguk.logic.util.clamp
import opensamguk.logic.util.phpRound
import opensamguk.logic.util.phpToInt
import opensamguk.logic.util.valueFit

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

    // ====================================================================================================
    // WORLD-DRIVEN nation-pass hooks (PHP `GeneralAI.php:3881-4292`, read in full).
    //
    // The 5 rate/promotion methods are `chooseNationTurn` npcType>=2 SIDE-EFFECT hooks (PHP `:3633-3646`),
    // NOT priority-loop `do<한글>` command bodies — they return no ChosenCommand; they mutate the nation /
    // chief generals directly. Since the AI is READ-ONLY over GAME ENTITIES (decision #12 / M4), every
    // mutation routes through the [RatesPromoDeltaSink] ChangeRecorder delta seam (officer_level /
    // officer_city / chief_set / war / rate / bill), NOT inline. The promotion hooks MAY draw (the
    // choosePromotion `nextBool(0.1)` per OCCUPIED slot; the chooseNonLordPromotion up-to-5 `choice` per
    // EMPTY slot); the three rate hooks make ZERO draws. The candidate ORDER — the chief-slot scan
    // (Util::range(minChiefLevel,12) / range(11,minChiefLevel-1,-1)) + the pool priority + the develRate
    // keys — IS the draw-for-draw parity target.
    //
    // The body reads the [RatesPromoContext] (rng / world buckets / per-general scalars / income+devRate
    // inputs / delta sink), assembles the exact candidate set in PHP insertion order, calls the PURE draw
    // primitive above, and queues the deltas. The Assemble step wires [bodies] into [NationPassHooks].
    // ====================================================================================================

    /**
     * One promotion candidate as the chief-pick loops read it (PHP `$general`/`$randGeneral`/`$chief`):
     * the id + the chief-gate scalars (`officer_level` / strength|intel raw stat-gate flavor `(F,F,F,F)` /
     * `getNPCType()` / `killturn` / `belong` + the penalty flags). The strength/intel are the
     * `getStrength(false,false,false,false)` / `getIntel(false,false,false,false)` promotion stat-gate
     * flavor (PHP `:3934/3938/4123/4125`, decision #7 / G8 — already truncated by the adapter; NOT re-rounded).
     *
     * @param generalId PHP `$general->getID()` — the officer_level/officer_city delta target.
     * @param officerLevel PHP `getVar('officer_level')` — the `!= 1` non-lord accept gate (`:3924`) + the
     *   `> 4` chief-pool exclusion (`:4052/4109`).
     * @param strength the promotion stat-gate strength (`:3934/4123`, chiefLevel even gate).
     * @param intel the promotion stat-gate intel (`:3938/4125`, chiefLevel odd gate).
     * @param npcType PHP `getNPCType()` — the user/npc killturn split (`:4112/4115`) + userChiefCnt (`:4129`).
     * @param killturn PHP `getVar('killturn')` — the user/npc min-killturn gates (`:4112/4115`).
     * @param belong PHP `getVar('belong')` — the `>= minBelong` newChief filter (`:4016`, unused by NonLord).
     * @param hasNoChief PHP `hasPenalty(NoChief)` — excludes from the newChief pool (`:4019/4118`).
     * @param hasNoAmbassador PHP `hasPenalty(NoAmbassador)` — the ambassador-permission delta gate (`:4143`).
     */
    data class PromotionCandidate(
        val generalId: Int,
        val officerLevel: Int,
        val strength: Double,
        val intel: Double,
        val npcType: Int,
        val killturn: Int,
        val belong: Int,
        val hasNoChief: Boolean = false,
        val hasNoAmbassador: Boolean = false,
        /** PHP `$general['dedication']` — the bill-estimate input (`getBill` H-HELPERS §3). Default 0. */
        val dedication: Double = 0.0,
    )

    /**
     * The ChangeRecorder delta seam (decision #12 / M4) the rate/promotion hooks route their mutations
     * through — the AI is READ-ONLY over GAME ENTITIES, so officer_level/officer_city/chief_set/war/rate/bill
     * writes queue as AI-side deltas, NOT inline and NOT a module-static Map. The engine adapter wires a real
     * ChangeRecorder-backed implementation; the logic layer only sees this interface.
     */
    interface RatesPromoDeltaSink {
        /** Queue a general row KV write (officer_level / officer_city). */
        fun recordGeneralKv(generalId: Int, key: String, value: Any?)

        /** Queue a nation row KV write (chief_set / war / rate / bill). */
        fun recordNationKv(nationId: Int, key: String, value: Any?)
    }

    /**
     * The per-nation-pass AI INPUT the 5 rate/promotion hooks read. Faithful Kotlin stand-in for the PHP
     * `GeneralAI` per-instance nation/chief state (`GeneralAI.php`, GRAND TRUTH): the SOLE `"GeneralAI"`
     * [rng] threaded by reference; the nation row scalars; the chief-slot scan inputs ([chiefSet] /
     * [chiefGeneralLevels] / [selfOfficerLevel]); the 4 promotion candidate pools (categorizeNationGeneral
     * buckets, PK-ascending) + the [chiefGeneralOf] / [userGenerals] / [nationGenerals] accessors; the
     * develRate / income inputs for the rate hooks; and the [deltaSink]. Tests build it directly over a
     * deterministic fixture; the engine adapter materialises it over the live world.
     *
     * @param nationId the nation row delta target.
     * @param nationLevel PHP `$nation['level']` — `getNationChiefLevel(level)` = the minChiefLevel scan floor.
     * @param chiefSet the set of occupied chief levels (PHP `$nation['chief_set']` bitfield, decoded via
     *   `isOfficerSet`) — a level IN this set is skipped by both scans (`:3890/4082`).
     * @param chiefGeneralLevels the chiefGenerals KEY set (PHP `$this->chiefGenerals`, keyed by officer_level)
     *   — `key_exists($chiefLevel, $this->chiefGenerals)` (`:3893/4089/4096`). For choosePromotion an OCCUPIED
     *   level draws the `nextBool(0.1)`; for chooseNonLordPromotion an in-chiefGenerals level is skipped.
     * @param chiefGeneralOf the chiefGenerals VALUE accessor (PHP `$this->chiefGenerals[$chiefLevel]`) — the
     *   old chief at a level (for the demote delta + the keep-old-chief gate `:4091`). null when empty.
     * @param selfOfficerLevel PHP `$this->general->getVar('officer_level')` — the self-skip (`:3896/4085`).
     * @param chiefStatMin `GameConst::$chiefStatMin` (the chief strength/intel gate `:3934/4123`). Threaded.
     * @param killturnEnv PHP `$this->env['killturn']` — the `minUserKillturn = killturn - toInt(240/turnterm)`
     *   base (choosePromotion `:3988`). [turnTerm] supplies the divisor.
     * @param npcWarGenerals/npcCivilGenerals/userWarGenerals/userCivilGenerals the 4 chooseNonLordPromotion
     *   pools (PHP insertion order = the categorizeNationGeneral bucket order, a parity target). NOT re-sorted.
     * @param userGenerals PHP `$this->userGenerals` (the choosePromotion userChief availability pass `:4012`).
     * @param nationGenerals PHP `$this->nationGenerals` (the choosePromotion newChief stat-desc `uasort` pool
     *   `:4068`; the adapter supplies them PK-ascending — the deterministic `uasort` is NO draw).
     * @param supplyCities the nation supply cities (PHP `$this->supplyCities`) — the rate-hook develRate base.
     * @param nationGold/nationRice PHP `$nation['gold']`/`$nation['rice']` — the bill moreBill-surplus gate.
     * @param goldIncome/warGoldIncome/riceIncome/wallRiceIncome the bill income inputs (the adapter computes
     *   them via the H-HELPERS §3 income helpers; the hook sums them — gold = goldIncome+warGoldIncome,
     *   rice = riceIncome+wallRiceIncome).
     * @param reqNationGold/reqNationRice PHP `$this->nationPolicy->reqNationGold`/`reqNationRice` — the bill
     *   moreBill `reqNationRes * 2` surplus threshold.
     * @param deltaSink the meta-KV delta seam (decision #12).
     */
    data class RatesPromoContext(
        val rng: RandUtil,
        val nationId: Int,
        val nationLevel: Int,
        val chiefSet: Set<Int> = emptySet(),
        val chiefGeneralLevels: Set<Int> = emptySet(),
        val chiefGeneralOf: (chiefLevel: Int) -> PromotionCandidate? = { null },
        val selfOfficerLevel: Int = 0,
        val chiefStatMin: Double = 65.0,
        val killturnEnv: Int = 0,
        val turnTerm: Int = 1,
        val npcWarGenerals: List<PromotionCandidate> = emptyList(),
        val npcCivilGenerals: List<PromotionCandidate> = emptyList(),
        val userWarGenerals: List<PromotionCandidate> = emptyList(),
        val userCivilGenerals: List<PromotionCandidate> = emptyList(),
        val userGenerals: List<PromotionCandidate> = emptyList(),
        val nationGenerals: List<PromotionCandidate> = emptyList(),
        val supplyCities: List<CityDevelInput> = emptyList(),
        val nationGold: Int = 0,
        val nationRice: Int = 0,
        val goldIncome: Int = 0,
        val warGoldIncome: Int = 0,
        val riceIncome: Int = 0,
        val wallRiceIncome: Int = 0,
        val reqNationGold: Int = 10000,
        val reqNationRice: Int = 12000,
        val deltaSink: RatesPromoDeltaSink,
    )

    /**
     * The 5 nation-pass side-effect hooks the [opensamguk.logic.ai.GeneralAiFactory] wires into
     * [opensamguk.logic.ai.NationPassHooks] (the Assemble step). Each is a `() -> ...` closure over ONE
     * [RatesPromoContext]; the promotion hooks return Unit (pure side-effect) and MAY draw, the three rate
     * hooks return the chosen Int rate/bill (PHP `chooseTexRate(): int` etc.) and make ZERO draws.
     */
    class RatesPromoHooks(private val ctx: RatesPromoContext) {
        fun chooseNonLordPromotion() = chooseNonLordPromotion(ctx)
        fun choosePromotion() = choosePromotion(ctx)
        fun chooseTexRate(): Int = chooseTexRate(ctx)
        fun chooseGoldBillRate(): Int = chooseGoldBillRate(ctx)
        fun chooseRiceBillRate(): Int = chooseRiceBillRate(ctx)
    }

    /** Build the 5 nation-pass hooks over the per-nation-pass [ctx]. */
    fun bodies(ctx: RatesPromoContext): RatesPromoHooks = RatesPromoHooks(ctx)

    // --- chooseNonLordPromotion (PHP `:3881-3963`) ---

    private fun chooseNonLordPromotion(ctx: RatesPromoContext) {
        val minChiefLevel = GameConst.getNationChiefLevel(ctx.nationLevel) // PHP :3887.

        // PHP `:3889` — foreach (Util::range(minChiefLevel, 12) as $chiefLevel). Ascending scan over EMPTY slots.
        for (chiefLevel in minChiefLevel..12) {
            if (chiefLevel in ctx.chiefSet) continue // PHP :3890-3892 — isOfficerSet → occupied, skip.
            if (chiefLevel in ctx.chiefGeneralLevels) continue // PHP :3893-3895 — already chosen this pass, skip.
            if (ctx.selfOfficerLevel == chiefLevel) continue // PHP :3896-3898 — self holds this level, skip.

            var picked: PromotionCandidate? = null
            // PHP `:3905` — foreach (Util::range(5) as $idx) — up to 5 attempts, redraw-on-reject.
            for (attempt in 0 until 5) {
                val candId = pickPromotionCandidate(
                    npcWarGenerals = ctx.npcWarGenerals.map { it.generalId },
                    npcCivilGenerals = ctx.npcCivilGenerals.map { it.generalId },
                    userWarGenerals = ctx.userWarGenerals.map { it.generalId },
                    userCivilGenerals = ctx.userCivilGenerals.map { it.generalId },
                    rng = ctx.rng,
                ) ?: break // PHP :3920-3922 — all pools empty ⇒ break, NO draw.
                val cand = candidateById(ctx, candId)!!

                if (cand.officerLevel != 1) continue // PHP :3924-3926 — non-lord only.
                if (chiefLevel == 11) { // PHP :3928-3931 — 군주 candidate bypasses the stat gate.
                    picked = cand
                    break
                }
                // PHP `:3933-3941` — even chiefLevel gates strength, odd gates intel, both vs chiefStatMin.
                if (chiefLevel % 2 == 0) {
                    if (cand.strength < ctx.chiefStatMin) continue // PHP :3934-3936.
                } else {
                    if (cand.intel < ctx.chiefStatMin) continue // PHP :3938-3940.
                }
                picked = cand // PHP :3942-3943.
                break
            }

            val winner = picked ?: continue // PHP :3946-3948 — !picked → no delta for this slot.
            // PHP `:3950-3955` — set the picked general's officer_level/officer_city; add the chief_set bit.
            ctx.deltaSink.recordGeneralKv(winner.generalId, "officer_level", chiefLevel) // PHP :3950.
            ctx.deltaSink.recordGeneralKv(winner.generalId, "officer_city", 0) // PHP :3951.
            ctx.deltaSink.recordNationKv(ctx.nationId, "chief_set", chiefLevel) // PHP :3953/3958-3961 chief_set |= bit.
        }
    }

    // --- choosePromotion (PHP `:3978-4170`) ---

    private fun choosePromotion(ctx: RatesPromoContext) {
        val minChiefLevel = GameConst.getNationChiefLevel(ctx.nationLevel) // PHP :3984.

        // PHP `:4081` — foreach (Util::range(11, minChiefLevel - 1, -1) as $chiefLevel). DESC demote/promote scan.
        var chiefLevel = 11
        while (chiefLevel > minChiefLevel - 1) {
            if (chiefLevel in ctx.chiefSet) { chiefLevel -= 1; continue } // PHP :4082-4084 — already set, skip.
            if (ctx.selfOfficerLevel == chiefLevel) { chiefLevel -= 1; continue } // PHP :4085-4087 — self, skip.

            // PHP `:4089-4094` — an occupied non-near-deletion user chief keeps the slot (no churn).
            val oldChief = if (chiefLevel in ctx.chiefGeneralLevels) ctx.chiefGeneralOf(chiefLevel) else null
            if (oldChief != null && oldChief.npcType < 2 && oldChief.killturn >= minChiefLevel) {
                chiefLevel -= 1; continue
            }

            // PHP `:4096-4100` — EMPTY slot → newChiefProb=1 (NO draw); OCCUPIED → ONE nextBool(0.1).
            val slotOccupied = chiefLevel in ctx.chiefGeneralLevels // (nextChiefs is fresh this pass → empty)
            val newChiefProb = newChiefProb(slotOccupied, ctx.rng)

            // PHP `:4102-4104` — the phantom gate (newChiefProb ∈ {0,1} → ZERO draw); `continue` only at 0.
            if (newChiefProbGateSkips(newChiefProb, ctx.rng)) { chiefLevel -= 1; continue }

            // PHP `:4107-4135` — scan the stat-desc nationGenerals for the first eligible newChief (NO draw).
            val newChief = findNewChief(ctx, chiefLevel, minChiefLevel)
            if (newChief == null) { chiefLevel -= 1; continue } // PHP :4137-4139.

            // PHP `:4148-4152` — promote the newChief; queue the deltas (the demote of the old chief is :4155-4161).
            if (oldChief != null) {
                ctx.deltaSink.recordGeneralKv(oldChief.generalId, "officer_level", 1) // PHP :4158.
                ctx.deltaSink.recordGeneralKv(oldChief.generalId, "officer_city", 0) // PHP :4159.
            }
            ctx.deltaSink.recordGeneralKv(newChief.generalId, "officer_level", chiefLevel) // PHP :4149.
            ctx.deltaSink.recordGeneralKv(newChief.generalId, "officer_city", 0) // PHP :4150.
            ctx.deltaSink.recordNationKv(ctx.nationId, "chief_set", chiefLevel) // PHP :4151 chief_set |= bit.
            chiefLevel -= 1
        }
    }

    /**
     * `choosePromotion`'s newChief scan (PHP `:4107-4135`): the FIRST nationGenerals entry (already stat-desc
     * `uasort`-ordered by the adapter, `:4068-4077`) passing the officer/killturn/penalty/stat gates. NO draw.
     */
    private fun findNewChief(ctx: RatesPromoContext, chiefLevel: Int, minChiefLevel: Int): PromotionCandidate? {
        val minUserKillturn = ctx.killturnEnv - phpToInt(240.0 / ctx.turnTerm) // PHP :3988.
        val minNpcKillturn = 36 // PHP :3989.
        for (g in ctx.nationGenerals) {
            if (g.officerLevel > 4) continue // PHP :4109-4111.
            if (g.npcType < 2 && g.killturn < minUserKillturn) continue // PHP :4112-4114.
            if (g.npcType >= 2 && g.killturn < minNpcKillturn) continue // PHP :4115-4117.
            if (g.hasNoChief) continue // PHP :4118-4120.
            // PHP `:4122-4127` — chiefLevel 11 bypasses; even gates strength, odd gates intel.
            if (chiefLevel != 11) {
                if (chiefLevel % 2 == 0 && g.strength < ctx.chiefStatMin) continue // PHP :4123.
                if (chiefLevel % 2 == 1 && g.intel < ctx.chiefStatMin) continue // PHP :4125.
            }
            return g // PHP :4133-4134.
        }
        return null
    }

    /** Resolve a candidate id to its [PromotionCandidate] across the 4 chooseNonLordPromotion pools. */
    private fun candidateById(ctx: RatesPromoContext, id: Int): PromotionCandidate? =
        ctx.npcWarGenerals.firstOrNull { it.generalId == id }
            ?: ctx.npcCivilGenerals.firstOrNull { it.generalId == id }
            ?: ctx.userWarGenerals.firstOrNull { it.generalId == id }
            ?: ctx.userCivilGenerals.firstOrNull { it.generalId == id }

    // --- chooseTexRate (PHP `:4172-4199`) — ZERO draws ---

    private fun chooseTexRate(ctx: RatesPromoContext): Int {
        // PHP `:4180-4191` — default 15; if supply cities, the develRate avg ladder.
        val rate = if (ctx.supplyCities.isEmpty()) {
            15 // PHP :4180/:4182 — no supply cities → default.
        } else {
            val devRate = calcNationDevelopedRate(ctx.supplyCities, ctx.rng) // PHP :4183.
            val avg = (devRate.getValue("pop") + devRate.getValue("all")) / 2 // PHP :4185.
            texRateForAvg(avg, ctx.rng) // PHP :4187-4190.
        }
        // PHP `:4194-4197` — db update ['war'=>0, 'rate'=>$rate]; queued as deltas in array-literal order.
        ctx.deltaSink.recordNationKv(ctx.nationId, "war", 0)
        ctx.deltaSink.recordNationKv(ctx.nationId, "rate", rate)
        return rate
    }

    // --- chooseGoldBillRate (PHP `:4201-4246`) / chooseRiceBillRate (PHP `:4248-4292`) — ZERO draws ---

    private fun chooseGoldBillRate(ctx: RatesPromoContext): Int {
        if (ctx.supplyCities.isEmpty()) return 20 // PHP :4211 — no supply cities, NO db update.
        val income = ctx.goldIncome + ctx.warGoldIncome // PHP :4225-4227.
        val outcome = billOutcome(ctx) // PHP :4229 valueFit(getOutcome(100, dedicationList), 1).
        val bill = billRate( // PHP :4231-4239.
            income = income, outcome = outcome,
            currentRes = ctx.nationGold, reqNationRes = ctx.reqNationGold,
            hasSupplyCities = true, rng = ctx.rng,
        )
        ctx.deltaSink.recordNationKv(ctx.nationId, "bill", bill) // PHP :4241.
        return bill
    }

    private fun chooseRiceBillRate(ctx: RatesPromoContext): Int {
        if (ctx.supplyCities.isEmpty()) return 20 // PHP :4258 — no supply cities, NO db update.
        val income = ctx.riceIncome + ctx.wallRiceIncome // PHP :4271-4273.
        val outcome = billOutcome(ctx) // PHP :4275 valueFit(getOutcome(100, dedicationList), 1).
        val bill = billRate( // PHP :4277-4285.
            income = income, outcome = outcome,
            currentRes = ctx.nationRice, reqNationRes = ctx.reqNationRice,
            hasSupplyCities = true, rng = ctx.rng,
        )
        ctx.deltaSink.recordNationKv(ctx.nationId, "bill", bill) // PHP :4287.
        return bill
    }

    /**
     * The bill `outcome = Util::valueFit(getOutcome(100, $dedicationList), 1)` (PHP `:4229`/`:4275`). The
     * `dedicationList` is the npc!=5-filtered nationGenerals (the dead self-append + the npc==5 exclusion,
     * H-HELPERS §3); the adapter supplies the already-filtered candidates, so the dedication sum is over
     * [RatesPromoContext.nationGenerals] (the npc==5 filter is the adapter's bucket build — port verbatim).
     */
    private fun billOutcome(ctx: RatesPromoContext): Int {
        var billSum = 0
        for (g in ctx.nationGenerals) {
            if (g.npcType == 5) continue // H-HELPERS §3 — dedicationList filters npc != 5.
            billSum += getBill(g) // PHP getOutcome :242-244 — Σ getBill(dedication).
        }
        return outcomeFloorMin1(billSum, 100) // PHP :4229/:4275 — valueFit(getOutcome(100,...), 1).
    }

    /**
     * `getBill(int $dedication)` (H-HELPERS §3, func_converter.php:664-670): `getBillByLevel(getDedLevel(ded))`
     * = `getDedLevel(ded)*200 + 400`. Reads `general['dedication']` ([PromotionCandidate.dedication]).
     */
    private fun getBill(g: PromotionCandidate): Int = getBillByLevel(getDedLevel(g.dedication))

    /**
     * `getDedLevel = Util::valueFit(ceil(sqrt(ded)/10), 0, maxDedLevel)` (H-HELPERS §3, func_converter.php:643;
     * `ceil` DISTINCT from round; clamp `[0, 30]`).
     */
    private fun getDedLevel(dedication: Double): Int =
        valueFit(kotlin.math.ceil(kotlin.math.sqrt(dedication) / 10.0), 0.0, GameConst.maxDedLevel.toDouble()).toInt()

    /** `getBillByLevel = dedLevel*200 + 400` (H-HELPERS §3, func_converter.php:668). */
    private fun getBillByLevel(dedLevel: Int): Int = dedLevel * 200 + 400
}
