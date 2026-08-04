package opensamguk.logic.ai.families

import opensamguk.common.rng.RandUtil
import opensamguk.logic.actions.nation.NationCommand
import opensamguk.logic.ai.AiInstanceState
import opensamguk.logic.ai.AiKvRecorder
import opensamguk.logic.ai.AiUtils
import opensamguk.logic.ai.ChosenCommand
import opensamguk.logic.ai.GeneralAiContext
import opensamguk.logic.domain.LastTurn
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

    /**
     * The selected non-aggression target: the destination nation id + the negotiated `diplomatMonth`. PHP
     * `:1819` `$diplomatMonth = 24 * $amount / $income;` is FLOAT division (NOT integer-truncating); the
     * truncation happens ONLY at the `Util::parseYearMonth(int $yearMonth)` boundary (the `int` param coerces
     * `$yearMonth + $diplomatMonth` toward zero). So this carries the un-truncated float — the caller truncates
     * the SUM, not the term.
     */
    data class NonAggressionTarget(val destNationId: Int, val diplomatMonth: Double)

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
     * (PHP `:1819`) is FLOAT division — the truncation happens later at `parseYearMonth(int)` on the SUM, NOT
     * here (a premature `Int` division here truncated diplomatMonth and shifted the target year — fixed). Returns
     * null when no candidate qualifies (PHP `:1822`) or the list is empty (PHP `:1799-1801`).
     *
     * @param candidateList the `recv_assist` candidate map (candNationID → amount), already cooldown-filtered.
     * @param income the `goldIncome + riceIncome + wallIncome` float total (PHP `:1810-1812`, the adapter computes
     *   it WITH the nation-type module + officersCnt — NOT truncated to int before this).
     * @return the picked [NonAggressionTarget], or null.
     */
    @Suppress("UNUSED_PARAMETER")
    fun pickNonAggressionTarget(
        candidateList: Map<Int, Int>,
        income: Double,
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
            // PHP :1818-1819 — first qualifying candidate wins; diplomatMonth is FLOAT (truncated at parseYearMonth).
            val diplomatMonth = 24.0 * amount / income
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

    // ====================================================================================================
    // WORLD-DRIVEN do<한글> BODIES (PHP `GeneralAI.php:1765-2115`, read in full).
    //
    // Each body reads the [GeneralAiContext] (rng / instance.dipState+attackable+nation.capital /
    // world.frontCities) PLUS the family-local diplo input ([DiploInput]/[WarInput]/[RelocateInput], which
    // the engine adapter materialises from the diplo-specific world reads that are NOT plain logic columns
    // — recv_assist KV / income / isNeighbor / getAllNationStaticInfo / devRate / TechLimit / the
    // capital-connected BFS distance maps), assembles the exact candidate set in PHP insertion order, calls
    // the PURE primitive (the arsort/income-walk / the A+B+C draws / the persistence-or-arsort + dist>1
    // choice), GATES the emit via `ctx.candidateAllowed(...)` (PHP `$cmd->hasFullConditionMet()`), and routes
    // the meta-KV delta (resp_assist_try / last천도Trial) through the supplied recordNationKv sink (decision
    // #12 / M4) — NEVER inline, NEVER a static Map. READ-ONLY over GAME ENTITIES. **SELECTION + boolean gate
    // + draw stream ONLY (decision #11 / m10); the `che_*` state-mutation/logs are P6.** The candidate ORDER
    // (arsort DESC / getAllNationStaticInfo insertion order / capital-path BFS name order) IS the
    // draw-for-draw parity target.
    // ====================================================================================================

    /**
     * The family-local `do불가침제의` input the adapter materialises (PHP `:1773-1812` — the nation_env KV +
     * the income estimate that are NOT plain logic columns).
     *
     * @param nationId the acting nation id (PHP `$nation['nation']`) — the [recordNationKv] target.
     * @param officerLevel the acting general's `officer_level` (PHP `:1769` — `< 12` → null).
     * @param yearMonth `joinYearMonth(env.year, env.month)` (PHP `:1781`, H-HELPERS §1, the `-1` variant).
     * @param recvAssist the `recv_assist` KV as `(candNationID, amount)` pairs in PHP `foreach` insertion
     *  order (PHP `:1777/1784`) — the candidate-build order BEFORE the `arsort` DESC.
     * @param respAssist the `resp_assist` KV (`"n{cand}" → [cand, alreadyResponded]`), PHP `:1778/1785`.
     * @param respAssistTry the `resp_assist_try` KV (`"n{cand}" → [cand, lastTryYearMonth]`), PHP `:1779/1792`;
     *  also the base map the [respAssistTryDelta] write merges into (PHP `:1841-1842` writes the FULL map).
     * @param warTargetNationKeys `array_keys($this->warTargetNation)` (PHP `:1789`) — candidates at war skip.
     * @param income `goldIncome + riceIncome + wallIncome` at taxRate 15 (PHP `:1808-1811`, H-HELPERS §3),
     *  or `null` when `supplyCities` is empty (PHP `:1804-1806` → null). The adapter computes it.
     * @param recordNationKv the nation_env meta-KV delta sink (decision #12 / M4).
     */
    data class DiploInput(
        val nationId: Int,
        val officerLevel: Int,
        val yearMonth: Int,
        val recvAssist: List<Pair<Int, Int>>,
        val respAssist: Map<String, List<Int>>,
        val respAssistTry: Map<String, List<Int>>,
        val warTargetNationKeys: Set<Int>,
        val income: Double?,
        val recordNationKv: AiKvRecorder,
    )

    /**
     * The family-local `do선전포고` input the adapter materialises (PHP `:1913-1951` — the avg-resource
     * trialProp, the `lowTargetNations` set, and the `getAllNationStaticInfo()` list, none plain columns).
     *
     * @param trialPropPow6 the ALREADY `** 6`-raised trial probability (PHP `:1913-1921`) — the (A) draw
     *  `nextBool` short-circuits on it (`>=1` true no-draw / `<=0` false no-draw / `===0.5` bit / else float),
     *  so the adapter must compute it bit-identically to PHP (the avg gold/rice + devRate fold).
     * @param lowTargetNations `convertArrayToSetLike(SELECT DISTINCT me FROM diplomacy WHERE me != nationID
     *  AND state IN (0,1))` (PHP `:1928-1932`) — the "already at war with someone" set; membership routes a
     *  neighbor into `$warNations` instead of `$nations` (PHP `:1946-1950`), and `count` feeds the (B) prob.
     * @param allNationStaticInfo `getAllNationStaticInfo()` as `(destNationID, level, power)` triples in PHP
     *  insertion order (PHP `:1936`) — the candidate-build order; `level == 0` rows skip (PHP `:1937`).
     * @param isNeighbor `isNeighbor(nationID, destNationID)` (PHP `:1942`) — non-neighbors skip (no partition).
     */
    data class WarInput(
        val trialPropPow6: Double,
        val lowTargetNations: Set<Int>,
        val allNationStaticInfo: List<Triple<Int, Int, Int>>,
        val isNeighbor: (destNationId: Int) -> Boolean,
    )

    /**
     * The family-local `do천도` input the adapter materialises (PHP `:1982-2073` — the nation_env cooldown,
     * the capital-connected BFS city set + the Floyd distance maps + the per-city score, none plain columns).
     *
     * @param capital the acting nation's `capital` city id (PHP `$this->nation['capital']`).
     * @param nationCityIds the capital-connected nation city ids in BFS dequeue order (PHP `:2017-2053`); the
     *  body's only structural read is `count <= 1` → null (PHP `:2024/2056`).
     * @param distanceList the Floyd `searchAllDistanceByCityList(cityList)` map `cityID → (cityID → dist)`
     *  (PHP `:2060`) — the per-city score divisor + the `[capital][finalCity]` hop distance.
     * @param cityScores the per-city `pop * maxDistance / sum(dist) * sqrt(dev)` score (PHP `:2068-2073`) in
     *  the [nationCityIds] (BFS) insertion order — the `arsort` DESC + the top-quartile + `array_key_first`.
     * @param capitalPathNeighbors the capital's one-step-closer path-neighbor ids (PHP `:2092-2099`, the
     *  `CityConst.byID(capital).path` name-order subset with `distance[stop][final] + 1 === dist`) — the
     *  `choice` candidate list, consulted ONLY when the capital→final hop distance `> 1`.
     * @param lastTrialBlocks the `last천도Trial` cooldown boolean (PHP `:1995-2006`: a stored trial within
     *  `turnTerm * 30` seconds AND the same officer_level → still on cooldown → null). The adapter computes
     *  the DateInterval; the body branches on the boolean.
     * @param recordNationKv the nation_env meta-KV delta sink (decision #12 / M4).
     * @param nationId / officerLevel / turnTime the [last천도TrialDelta] payload (PHP `:1989/2112`).
     */
    data class RelocateInput(
        val capital: Int,
        val nationCityIds: List<Int>,
        val distanceList: Map<Int, Map<Int, Int>>,
        val cityScores: Map<Int, Double>,
        val capitalPathNeighbors: List<Int>,
        val lastTrialBlocks: Boolean,
        val recordNationKv: AiKvRecorder,
        val nationId: Int,
        val officerLevel: Int,
        val turnTime: String,
    )

    /**
     * The 3 nation-diplomacy `do<한글>` bodies keyed by ACTION-NAME (the bare name, NOT `che_*`), in
     * [opensamguk.logic.ai.AutorunNationPolicy.priority] order. The [opensamguk.logic.ai.GeneralAiFactory]
     * registers these into the nation dispatch; the loop walks the policy order. A body returns null on every
     * early-guard / empty-candidate / gate-deny / absent-input — so a missing family-local input (the
     * single-input overloads) makes its body a null no-op (the catalog-sanctioned dispatch skip).
     *
     * @param diplo the `do불가침제의` input (null → that body is a null no-op).
     * @param war the `do선전포고` input (null → that body is a null no-op).
     * @param reloc the `do천도` input (null → that body is a null no-op).
     */
    fun bodies(
        ctx: GeneralAiContext,
        diplo: DiploInput? = null,
        war: WarInput? = null,
        reloc: RelocateInput? = null,
    ): Map<String, (LastTurn?) -> ChosenCommand?> = linkedMapOf(
        "불가침제의" to { lt -> diplo?.let { do불가침제의(ctx, it, lt) } },
        "선전포고" to { lt -> war?.let { do선전포고(ctx, it, lt) } },
        "천도" to { lt -> reloc?.let { do천도(ctx, it, lt) } },
    )

    /** Single-input overload — `do불가침제의` only ([bodies] with [DiploInput] positional, the L-DIPLO test). */
    fun bodies(ctx: GeneralAiContext, diplo: DiploInput): Map<String, (LastTurn?) -> ChosenCommand?> =
        bodies(ctx, diplo = diplo, war = null, reloc = null)

    /** Single-input overload — `do선전포고` only ([bodies] with [WarInput] positional/named `war`). */
    fun bodies(ctx: GeneralAiContext, war: WarInput): Map<String, (LastTurn?) -> ChosenCommand?> =
        bodies(ctx, diplo = null, war = war, reloc = null)

    /** Single-input overload — `do천도` only ([bodies] with [RelocateInput] positional). */
    fun bodies(ctx: GeneralAiContext, reloc: RelocateInput): Map<String, (LastTurn?) -> ChosenCommand?> =
        bodies(ctx, diplo = null, war = null, reloc = reloc)

    // --- do불가침제의 (PHP `:1765-1845`) — ZERO draws; recv/cooldown filter + arsort + income-walk + delta ---

    private fun do불가침제의(ctx: GeneralAiContext, input: DiploInput, lastTurn: LastTurn?): ChosenCommand? {
        // :1769 — officer_level < 12 → null (ruler-only).
        if (input.officerLevel < 12) return null

        // :1804-1806 — supplyCities empty → null (the adapter signals it via a null income).
        val income = input.income ?: return null

        // :1783-1796 — build the candidate map (recv_assist minus resp_assist, war-target skip, cooldown skip),
        // in recv_assist foreach INSERTION order (the order BEFORE the arsort DESC).
        val candidateList = LinkedHashMap<Int, Int>()
        for ((candNationId, rawAmount) in input.recvAssist) {
            val responded = input.respAssist["n$candNationId"]?.getOrNull(1) ?: 0
            val amount = rawAmount - responded // :1785
            if (amount <= 0) continue // :1786-1788
            if (candNationId in input.warTargetNationKeys) continue // :1789-1791
            val lastTry = input.respAssistTry["n$candNationId"]?.getOrNull(1) ?: 0
            if (nonAggressionOnCooldown(lastTry, input.yearMonth)) continue // :1792-1794 (8-month cooldown)
            candidateList[candNationId] = amount // :1795
        }

        // :1814-1823 — arsort DESC + the income-quarter walk (ZERO draws); null when nothing qualifies.
        val target = pickNonAggressionTarget(candidateList, income, ctx.rng) ?: return null // :1798-1800/1825-1826

        // :1830 — parseYearMonth(yearMonth + diplomatMonth). PHP `Util::parseYearMonth(int $yearMonth)` coerces
        // the float SUM to int by TRUNCATION toward zero (NOT rounding) — so truncate `yearMonth + diplomatMonth`
        // here (diplomatMonth is the un-truncated float from pickNonAggressionTarget).
        val sumYearMonth = (input.yearMonth + target.diplomatMonth).toInt() // PHP int-coerce = trunc-toward-zero.
        val (targetYear, targetMonth) = NationCommand.parseYearMonth(sumYearMonth)
        val args = linkedMapOf<String, Any?>(
            "destNationID" to target.destNationId,
            "year" to targetYear,
            "month" to targetMonth,
        ) // :1832-1836 (insertion order destNationID → year → month)

        // :1837-1839 — the hasFullConditionMet gate; deny → null (NO delta written below a denied gate).
        if (!ctx.candidateAllowed(NON_AGGRESSION_ACTION, args)) return null

        // :1841-1842 — resp_assist_try["n{dest}"] = [dest, yearMonth]. The PHP `setValue` writes the full map,
        // but the ChangeRecorder DELTA (decision #12 / M4) records exactly the changed sub-entry — the single
        // `{"n{dest}": [dest, yearMonth]}` key (the downstream P6 KV-merge applies it onto the pre-turn map);
        // recording the changed key (not the merged map) mirrors the `last_attackable` single-scalar delta.
        val (key, value) = respAssistTryDelta(target.destNationId, input.yearMonth)
        input.recordNationKv.recordNationKv(input.nationId, RESP_ASSIST_TRY_KEY, mapOf(key to value))

        return ChosenCommand(NON_AGGRESSION_ACTION, args)
    }

    // --- do선전포고 (PHP `:1848-1973`) — up to 3 draws A+B+C; neighbor partition; reqUpdateInstance ---

    private fun do선전포고(ctx: GeneralAiContext, input: WarInput, lastTurn: LastTurn?): ChosenCommand? {
        val instance = ctx.instance
        if (ctx.selfOfficerLevel < 12) return null
        // :1856 — dipState != d평화 → null (BEFORE any draw).
        if (instance.dipState != AiInstanceState.D_PEACE) return null
        // :1860 — attackable → null.
        if (instance.attackable) return null
        // :1864 — !capital → null.
        if (instance.nation.capital == 0) return null
        // :1868 — frontCities non-empty → null.
        if (ctx.world.frontCities.isNotEmpty()) return null
        if (!ctx.techLimitedNextGrade) return null

        // :1923 (A) — nextBool(trialProp**6); false → null (the draw is consumed even on the abort path).
        if (!warTrialGate(input.trialPropPow6, ctx.rng)) return null

        // :1934-1951 — partition the neighbor nations into $nations / $warNations in getAllNationStaticInfo
        // insertion order (the candidate-build order; level==0 + non-neighbor skip with NO partition).
        val nations = LinkedHashMap<Int, Double>()
        val warNations = LinkedHashMap<Int, Double>()
        for ((destNationId, level, power) in input.allNationStaticInfo) {
            if (level == 0) continue // :1937-1939
            if (!input.isNeighbor(destNationId)) continue // :1942-1944
            val weight = warTargetWeight(power) // :1947/1949 — 1/sqrt(power+1) (favors weak).
            if (destNationId !in input.lowTargetNations) {
                nations[destNationId] = weight // :1947
            } else {
                warNations[destNationId] = weight // :1949
            }
        }

        // :1952-1963 — the empty-$nations fallback (PHP WINS the TS 2-draw shape, M3).
        val finalNations: LinkedHashMap<Int, Double> = if (nations.isEmpty()) {
            when {
                warNations.isEmpty() -> return null // :1953-1955 (no draw)
                input.lowTargetNations.isEmpty() -> return null // :1956-1958 (no draw)
                // :1959 (B) — nextBool(1/count(lowTargetNations)); abort consumes the draw.
                warRetargetAbort(input.lowTargetNations.size, ctx.rng) -> return null
                else -> warNations // :1962 — promote $warNations into $nations.
            }
        } else {
            nations
        }

        // :1966 (C) — choiceUsingWeight($nations) → the target nation id (insertion-order weighted walk).
        val destNationId = pickWarTarget(finalNations.map { it.key to it.value }, ctx.rng)
        val args = linkedMapOf<String, Any?>("destNationID" to destNationId) // :1965-1967

        // :1968-1970 — the hasFullConditionMet gate; deny → null.
        if (!ctx.candidateAllowed(WAR_DECLARATION_ACTION, args)) return null

        // :1972 — reqUpdateInstance = true (AFTER the gate).
        instance.markDirty()
        return ChosenCommand(WAR_DECLARATION_ACTION, args)
    }

    // --- do천도 (PHP `:1976-2114`) — persistence-or-arsort + top-quartile + dist>1 choice; last천도Trial ---

    private fun do천도(ctx: GeneralAiContext, input: RelocateInput, lastTurn: LastTurn?): ChosenCommand? {
        // :1986-1992 — sticky persistence: re-emit the SAME 천도 toward a non-capital target (NO draw).
        val lastArgDest = (lastTurn?.arg?.get("destCityID") as? Number)?.toInt()
        if (lastTurn != null && lastArgDest != null &&
            relocatePersistenceApplies(lastTurn.command, lastArgDest, input.capital)
        ) {
            val args = linkedMapOf<String, Any?>("destCityID" to lastArgDest) // :1987 reuse the prior arg.
            if (ctx.candidateAllowed(RELOCATE_ACTION, args)) { // :1988
                queueLast천도Trial(input) // :1989 (below the gate)
                ctx.instance.markDirty() // :1990 reqUpdateInstance = true (persistence path too)
                return ChosenCommand(RELOCATE_ACTION, args)
            }
        }

        // :1995-2006 — the cooldown gate (a recent trial at the same officer_level → null, NO draw/delta).
        if (input.lastTrialBlocks) return null

        // :2024/2056 — fewer than two capital-connected cities → null (NO draw).
        if (input.nationCityIds.size <= 1) return null

        // :2075 — arsort DESC over the per-city score; the capital's rank gates the top-quartile abort.
        val sorted = sortCityScores(input.cityScores) // :2075 (NO draw)
        val orderedIds = sorted.keys.toList()
        val capitalRankIdx = orderedIds.indexOf(input.capital)
        // :2077-2085 — top-quartile "already good enough": capital within enoughLimit → null (NO draw/delta).
        if (capitalRankIdx >= 0 && relocateCapitalIsGoodEnough(capitalRankIdx, sorted.size)) return null

        // :2087-2101 — finalCity = array_key_first; the dist>1 intermediate-stop choice (the ONLY draw).
        val finalCityId = orderedIds.first()
        val dist = input.distanceList[input.capital]?.get(finalCityId) ?: 0
        val targetCityId = relocateNextHop(dist, finalCityId, input.capitalPathNeighbors, ctx.rng)
        val args = linkedMapOf<String, Any?>("destCityID" to targetCityId) // :2103-2105

        // :2107-2109 — the hasFullConditionMet gate; deny → null.
        if (!ctx.candidateAllowed(RELOCATE_ACTION, args)) return null

        // :2112-2113 — last천도Trial = [officer_level, turnTime] (below the gate); reqUpdateInstance = true.
        queueLast천도Trial(input)
        ctx.instance.markDirty()
        return ChosenCommand(RELOCATE_ACTION, args)
    }

    /** Queue the `last천도Trial` meta-KV delta (PHP `:1989/2112`, decision #12 — the FULL `[level, turnTime]`). */
    private fun queueLast천도Trial(input: RelocateInput) {
        input.recordNationKv.recordNationKv(
            input.nationId,
            LAST_RELOCATE_TRIAL_KEY,
            last천도TrialDelta(input.officerLevel, input.turnTime),
        )
    }
}
