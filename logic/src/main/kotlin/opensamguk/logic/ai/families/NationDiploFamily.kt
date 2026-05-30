package opensamguk.logic.ai.families

import opensamguk.common.rng.RandUtil
import opensamguk.logic.ai.AiUtils
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * L-DIPLO — the nation-diplomacy `do<한글>` command family: 불가침제의 / 선전포고 / 천도.
 *
 * **SELECTION + the boolean gate + the draw stream ONLY (decision #11); the `che_*` state-mutation/logs
 * (the diplomacy/relocation RESULT internals) are P6. The G-GATE downstream-delta/log assertion EXCLUDES
 * these three families (m10).**
 *
 * GRAND TRUTH = PHP `legacy/devsam-core/hwe/sammo/GeneralAI.php` (read in full):
 *  - `do불가침제의` (`:1765-1846`, emits `che_불가침제의`): **ZERO RNG draws** — pure deterministic selection
 *    over `recv_assist` candidates filtered by the 8-month cooldown (`joinYearMonth` gate, H-HELPERS §1), ONE
 *    stable `arsort` DESC, then the income-quarter walk; writes `resp_assist_try` (ChangeRecorder delta,
 *    decision #12) BELOW the `hasFullConditionMet()` gate.
 *  - `do선전포고`   (`:1848-1973`, emits `che_선전포고`): **up to 3 draws A+B+C** (PHP WINS the TS 2-draw shape,
 *    M3); sets `reqUpdateInstance=true` (`:1972`).
 *  - `do천도`       (`:1976-2113`, emits `che_천도`): persistence-or-`arsort` path; `choice` next-hop ONLY if
 *    `dist>1` (TS DROPS the cooldown/sticky/BFS-restriction — PHP WINS); writes `last천도Trial` (delta).
 *
 * This file holds the PURE, draw-order-bearing primitives each `do<한글>` composes (mirrors the established
 * `GenFoundFamily`/`NationDeployFamily`/`NationRewardFamily` pure-helper shape): the candidate-set
 * construction — the `recv_assist`/income filter, the `isNeighbor` partition into `$nations`/`$warNations`,
 * the BFS distance maps + the city-score formula — is the foundations'/adapter's job; the family owns the
 * per-method DRAW ORDER + COUNT on the shared `"GeneralAI"` [RandUtil], the emitted RAW arg shape, the
 * reqUpdateInstance signal, and the meta-KV delta keys. F-BRIDGE's `candidateAllowed` gate (the PHP
 * `hasFullConditionMet()` analogue) accepts/rejects the emit — the family does NOT re-implement that gate.
 *
 * NO draws happen outside the documented `nextBool`/`choiceUsingWeight`/`choice` sites. PURE `:logic`.
 */
object NationDiploFamily {

    /** PHP `:1834` `buildNationCommandClass('che_불가침제의', …)` — the do불가침제의 emit. */
    const val NON_AGGRESSION_ACTION: String = "che_불가침제의"

    /** PHP `:1964` `buildNationCommandClass('che_선전포고', …)` — the do선전포고 emit. */
    const val WAR_DECLARATION_ACTION: String = "che_선전포고"

    /** PHP `:2103`/`:1982` `buildNationCommandClass('che_천도', …)` — the do천도 emit. */
    const val RELOCATE_ACTION: String = "che_천도"

    /** PHP `:1972` `$this->reqUpdateInstance = true;` — do선전포고 marks the instance dirty after the gate. */
    const val WAR_DECLARATION_REQUIRES_INSTANCE_UPDATE: Boolean = true

    /** The `nation_env` meta-KV key do불가침제의 writes BELOW the gate (PHP `:1843`, decision #12 delta). */
    const val RESP_ASSIST_TRY_KEY: String = "resp_assist_try"

    /** The `nation_env` meta-KV key do천도 writes BELOW the gate (PHP `:2111`/`:1985`, decision #12 delta). */
    const val LAST_RELOCATE_TRIAL_KEY: String = "last천도Trial"

    // ==================================================================================================
    // do불가침제의 (:1765-1846) — ZERO RNG draws. arsort DESC + income-quarter walk + 8-month cooldown.
    // ==================================================================================================

    /** The selected non-aggression target: the destination nation id + the negotiated `diplomatMonth`. */
    data class NonAggressionTarget(val destNationId: Int, val diplomatMonth: Int)

    /**
     * `do불가침제의`'s 8-month cooldown gate (PHP `:1791`):
     * `($respAssistTry["n{cand}"][1] ?? 0) >= $yearMonth - 8` → still on cooldown → skip the candidate.
     *
     * [currentYearMonth] is the joined `joinYearMonth(year, month) = year*12 + month - 1` (H-HELPERS §1,
     * REUSE the GREEN formula — do NOT re-derive); [lastTryYearMonth] is the stored `resp_assist_try` stamp
     * (defaulting to `0` when absent, which is always `< yearMonth - 8` → not on cooldown).
     *
     * @return true when the candidate is STILL within the 8-month cooldown (must be skipped).
     */
    fun nonAggressionOnCooldown(lastTryYearMonth: Int, currentYearMonth: Int): Boolean =
        lastTryYearMonth >= currentYearMonth - 8

    /**
     * The `arsort($candidateList)` DESC over the `recv_assist` candidate map (PHP `:1814`): value-DESC,
     * STABLE on ties (PHP 8 — NO secondary comparator). Keyed by `candNationID`, valued by the running
     * `amount` (the received-assist total minus the already-responded amount). Returns a NEW insertion-ordered
     * [LinkedHashMap] in DESC order; the source is never mutated. Delegates to [AiUtils.arsort].
     */
    fun sortNonAggressionCandidates(candidateList: Map<Int, Int>): LinkedHashMap<Int, Int> =
        AiUtils.arsort(candidateList)

    /**
     * `do불가침제의`'s deterministic selection (PHP `:1814-1844`) — **ZERO RNG draws** ([rng] is accepted only
     * to document the 0-draw contract at the call site, matching the GenFoundFamily quarantine convention;
     * a reviewer must not "fix" this by inserting a draw — the cursor is a parity target).
     *
     * After `arsort` DESC, walk the candidates and pick the FIRST whose `amount * 4 >= income` (PHP `:1816`
     * `if ($amount * 4 < $income) break;` — the walk BREAKS, not continues, on the first failure: the list is
     * value-DESC, so once one fails all later ones fail). The negotiated `diplomatMonth = 24 * amount / income`
     * (PHP `:1819`, integer-truncating `intval` — `Int` arithmetic). Returns null when no candidate qualifies
     * (PHP `:1822` `if ($destNationID === null) return null;`) or the list is empty (PHP `:1799-1801`).
     *
     * @param candidateList the `recv_assist` candidate map (candNationID → amount), already cooldown-filtered.
     * @param income the `goldIncome + riceIncome + wallIncome` total (PHP `:1810-1812`, the adapter computes it).
     * @return the picked [NonAggressionTarget], or null.
     */
    @Suppress("UNUSED_PARAMETER")
    fun pickNonAggressionTarget(
        candidateList: Map<Int, Int>,
        income: Int,
        rng: RandUtil,
    ): NonAggressionTarget? {
        if (candidateList.isEmpty()) {
            return null // PHP :1799-1801 `if (!$candidateList) return null;`
        }
        val sorted = sortNonAggressionCandidates(candidateList) // PHP :1814 arsort DESC (NO draw)
        for ((candNationId, amount) in sorted) {
            if (amount * 4 < income) {
                break // PHP :1816 — value-DESC walk BREAKS on the first sub-threshold candidate.
            }
            // PHP :1818-1819 — first qualifying candidate wins (the loop body always `break`s after this).
            val diplomatMonth = 24 * amount / income // intval-trunc (Int division), PHP :1819.
            return NonAggressionTarget(destNationId = candNationId, diplomatMonth = diplomatMonth)
        }
        return null // PHP :1822 `if ($destNationID === null) return null;`
    }

    /**
     * The `resp_assist_try["n{destNationID}"] = [destNationID, yearMonth]` delta (PHP `:1843`), written BELOW
     * the `hasFullConditionMet()` gate (decision #12 — a ChangeRecorder meta-KV delta, NOT an inline write).
     * Returns the `(key, value)` pair the adapter queues into [RESP_ASSIST_TRY_KEY].
     */
    fun respAssistTryDelta(destNationId: Int, yearMonth: Int): Pair<String, List<Any>> =
        "n$destNationId" to listOf(destNationId, yearMonth)

    // ==================================================================================================
    // do선전포고 (:1848-1973) — up to 3 draws A+B+C. PHP WINS the TS 2-draw shape (M3).
    // ==================================================================================================

    /**
     * **(A)** `do선전포고`'s trial gate (PHP `:1923` `nextBool($trialProp)`). [trialPropPow6] is the ALREADY
     * `** 6`-raised trial probability (PHP `:1922` `$trialProp = $trialProp ** 6;`). The `nextBool`
     * short-circuit (RandUtil): `>=1` → true NO-draw, `<=0` → false NO-draw, `===0.5` → nextBit, else
     * nextFloat1 — the adapter must compute [trialPropPow6] bit-identically to PHP to decide whether A draws.
     *
     * @return true to proceed to the target-pick (PHP continues), false to abort `do선전포고` (return null).
     */
    fun warTrialGate(trialPropPow6: Double, rng: RandUtil): Boolean =
        rng.nextBool(trialPropPow6) // (A) PHP :1923 — one nextBool(trialProp**6).

    /**
     * **(B)** `do선전포고`'s re-target abort in the EMPTY-`$nations` fallback (PHP `:1959`
     * `if ($this->rng->nextBool(1 / count($lowTargetNations))) return null;`). **TS DROPS THIS DRAW — PHP
     * WINS (3 draws vs 2, M3).** Reached only when `$nations` is empty but `$warNations` AND `$lowTargetNations`
     * are non-empty (PHP `:1949-1958` guards: the two empties each `return null` BEFORE this draw, with NO draw).
     *
     * @param lowTargetNationsCount `count($lowTargetNations)` (PHP `:1959`) — the prob is `1 / count`.
     * @return true to ABORT (`return null` — the draw is consumed even on the abort path), false to promote
     *  `$warNations` into `$nations` and proceed to the (C) target pick.
     */
    fun warRetargetAbort(lowTargetNationsCount: Int, rng: RandUtil): Boolean =
        rng.nextBool(1.0 / lowTargetNationsCount) // (B) PHP :1959 — the empty-nations fallback draw.

    /**
     * `do선전포고`'s per-candidate target weight (PHP `:1955`/`:1957` `1 / sqrt($destNationPower + 1)`):
     * favors WEAK nations (lower power → higher weight). FLOAT, no rounding (the weight feeds the (C) walk).
     */
    fun warTargetWeight(power: Int): Double = 1.0 / sqrt(power + 1.0)

    /**
     * **(C)** `do선전포고`'s target pick (PHP `:1966` `choiceUsingWeight($nations)`): exactly ONE `nextFloat1`
     * draw over the insertion-ordered `(destNationID, 1/sqrt(power+1))` candidate list — the candidate ORDER
     * (insertion = `getAllNationStaticInfo()` order, PHP `:1934`, the adapter preserves it) is the parity
     * target. PHP `choiceUsingWeight` returns the KEY (the nation id); the Kotlin [RandUtil.choiceUsingWeight]
     * is String-keyed, so the family uses [RandUtil.choiceUsingWeightPair] over `(nationId, weight)` pairs
     * (identical walk semantics: one `nextFloat1`, insertion-order walk, returns the item) and returns the id.
     *
     * @param nations the `(destNationID, weight)` pairs in `getAllNationStaticInfo()` insertion order.
     * @return the picked destination nation id (the war-declaration target).
     */
    fun pickWarTarget(nations: List<Pair<Int, Double>>, rng: RandUtil): Int =
        rng.choiceUsingWeightPair(nations) // (C) PHP :1966 — one choiceUsingWeight draw.

    // ==================================================================================================
    // do천도 (:1976-2113) — arsort DESC + top-quartile gate + choice next-hop ONLY if dist>1.
    // ==================================================================================================

    /**
     * `do천도`'s sticky-persistence guard (PHP `:1981`):
     * `$lastTurn->getCommand() === '천도' && $lastTurn->getArg()['destCityID'] != $this->nation['capital']`.
     * When true the AI re-emits the SAME `che_천도` with NO draw (keep relocating toward the same target). TS
     * DROPS this sticky persistence — PHP WINS.
     *
     * @return true when the last turn was a 천도 to a non-capital city (re-emit, no draw).
     */
    fun relocatePersistenceApplies(lastCommand: String?, lastDestCityId: Int, capitalCityId: Int): Boolean =
        lastCommand == "천도" && lastDestCityId != capitalCityId

    /**
     * The `arsort($cityScoreList)` DESC over the per-city relocation score (PHP `:2059`): value-DESC, STABLE on
     * ties (PHP 8 — NO secondary comparator). `array_key_first` of the result is the best target city (PHP
     * `:2067`). The score formula `pop * maxDistance / sum(dist) * sqrt(dev)` (PHP `:2053`) is the adapter's;
     * the family owns only the stable DESC ordering. Delegates to [AiUtils.arsort].
     */
    fun sortCityScores(cityScoreList: Map<Int, Double>): LinkedHashMap<Int, Double> =
        AiUtils.arsort(cityScoreList)

    /**
     * `do천도`'s top-quartile "already good enough" gate (PHP `:2061-2065`):
     * `$enoughLimit = ceil(count($cityScoreList) * 0.25);` then walking the arsort-DESC keys,
     * `if ($idx > $enoughLimit) break; if ($cityID === $capital) return null;` — if the CAPITAL ranks at or
     * within `enoughLimit` it is already among the best quartile → abort the relocation. `ceil` is DISTINCT
     * from round (H-HELPERS §0/§5).
     *
     * @param capitalRankIdx the capital's positional index in the arsort-DESC score list (0-based).
     * @param cityCount `count($cityScoreList)`.
     * @return true when the capital is within `enoughLimit` (abort — relocation not worth it).
     */
    fun relocateCapitalIsGoodEnough(capitalRankIdx: Int, cityCount: Int): Boolean {
        val enoughLimit = ceil(cityCount * 0.25).toInt() // PHP :2061 ceil (NOT round).
        return capitalRankIdx <= enoughLimit // PHP :2062-2064 `if ($idx > $enoughLimit) break;` else capital→null.
    }

    /**
     * `do천도`'s next-hop pick (PHP `:2068-2101`): the relocation target is the `finalCityID` (the top-score
     * city, `array_key_first`) UNLESS the BFS distance from the capital is `> 1`, in which case the AI takes an
     * INTERMEDIATE stop — ONE `choice($candidates)` over the capital's path-neighbors that lie exactly one step
     * closer to the final city (PHP `:2088-2100` `if ($distanceList[$stopID][$finalCityID] + 1 === $dist)`).
     * **The `choice` draws ONLY when `dist > 1`** (TS DROPS this BFS-restriction — PHP WINS).
     *
     * @param dist `$distanceList[$capital][$finalCityID]` (PHP `:2069`) — the capital→final BFS distance.
     * @param finalCityId the top-score target city (used directly when [dist] <= 1).
     * @param candidates the capital's one-step-closer path-neighbor ids (PHP `:2098`, BFS name-order; only
     *  consulted when [dist] > 1) — the adapter builds them in `CityConst.byID(capital).path` name order.
     * @return the relocation destination city id (the intermediate stop when [dist] > 1, else [finalCityId]).
     */
    fun relocateNextHop(dist: Int, finalCityId: Int, candidates: List<Int>, rng: RandUtil): Int {
        if (dist > 1) {
            return rng.choice(candidates) // PHP :2100 — the ONLY draw, gated on dist>1.
        }
        return finalCityId // PHP :2070 `$targetCityID = $finalCityID;` (adjacent — no intermediate stop).
    }

    /**
     * The `last천도Trial = [$general->getVar('officer_level'), $general->getTurnTime()]` delta (PHP `:2111`,
     * also the persistence path `:1985`), written BELOW the `hasFullConditionMet()` gate (decision #12 — a
     * ChangeRecorder meta-KV delta, NOT an inline write). Returns the value the adapter queues into
     * [LAST_RELOCATE_TRIAL_KEY].
     */
    fun last천도TrialDelta(officerLevel: Int, turnTime: String): List<Any> =
        listOf(officerLevel, turnTime)
}
