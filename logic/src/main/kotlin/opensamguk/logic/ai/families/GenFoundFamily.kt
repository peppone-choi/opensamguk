package opensamguk.logic.ai.families

import opensamguk.common.constants.GameConst
import opensamguk.common.rng.RandUtil
import opensamguk.logic.ai.ChosenCommand
import opensamguk.logic.ai.ExternalSqlRandBranch
import opensamguk.logic.ai.GeneralAiContext
import opensamguk.logic.ai.bfs.AiDistance
import opensamguk.logic.domain.General
import opensamguk.logic.domain.GetNationColors
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.world.isFoundableCityLevel

/**
 * L-GENFOUND — the founding / nation-selection `do<한글>` command family (거병/해산/건국/선양/국가선택/사망대비/중립).
 *
 * **STATUS (FQ1, F-QUAR):** this file currently holds ONLY the `ORDER BY RAND()` deterministic-substitute
 * helpers (the F-QUAR quarantine). The full `do<한글>` method bodies are ADDED LATER by L-GENFOUND (Wave-1);
 * this is the substitute's home because `do선양` (`:3320`) and `do국가선택`-오랑캐 (`:3334`) live in this family.
 *
 * ---
 *
 * ## The `ORDER BY RAND()` quarantine (decision #6, G4, R-GATE §1) — the ONLY two non-DRBG random picks in the AI
 *
 * frozen historical baseline (ADR-LITE-042; not current product authority) = PHP `GeneralAI.php`:
 * ```php
 * // :3324  do선양     SELECT `no`   FROM general WHERE nation = %i AND npc != 5                ORDER BY RAND() LIMIT 1 → destGeneralID
 * // :3345  do국가선택  SELECT nation FROM general WHERE officer_level=12 AND npc=9 and nation    ORDER BY RAND() limit 1 → rulerNation
 * ```
 * Both are **MySQL/MariaDB-side `RAND()`** — they draw **ZERO bytes** off the `RandUtil(LiteHashDrbg)` DRBG
 * stream, so the AI's per-general rng cursor (`stateIdx`/`bufferIdx`) is **UNAFFECTED** (no downstream desync).
 * The ONLY non-determinism is *which row id is chosen among ties*; PHP itself is non-deterministic here and a
 * captured id would fail the "byte-identical across two runs" install rule.
 *
 * **The faithful substitute (CLAUDE.md parity rule #5: quarantine-with-proof, NEVER fabricate):**
 * a deterministic `min(no)` / `min(nation)` over the SAME WHERE-filtered candidate set. The candidate-set
 * ITERATION order is general.no insertion order (G13), but the PICK is `min(no)` so it is order-independent
 * and reviewer-legible. **These helpers MUST NOT pull a single draw off [rng]** — they take the [RandUtil] only
 * to make the 0-draw contract explicit at the call site (a reviewer must not "fix" this by inserting a draw;
 * the cursor is a parity target). `@ParityQuarantine("G4-order-by-rand")`.
 *
 * **Gate reachability — ZERO at gate start (R-GATE §1, G4 §3):** scenario 1010 has **0 npc==5 and 0 npc==9**
 * generals of 678 (install assigns only npc 2/6). `can선양` requires npc==5 (`AutorunGeneralPolicy.php:98-100`);
 * 오랑캐임관 requires npc==9 (`GeneralAI.php:3343`); the 2 officer_level==12 rulers (하진/장각) are both npc==2.
 * ⇒ NEITHER `ORDER BY RAND()` site fires in the P5 gate — tail paths, not gate paths. DO NOT fabricate an id,
 * weaken a test, or seed npc 5/9 into 1010. Registered in `.context/p5-research/QUARANTINE-REGISTER.md`.
 */
object GenFoundFamily {

    /**
     * `do선양` `ORDER BY RAND()` substitute (`GeneralAI.php:3324`):
     * `SELECT \`no\` FROM general WHERE nation = %i AND npc != 5 ORDER BY RAND() LIMIT 1`.
     *
     * Deterministic substitute = `min(no)` over the same WHERE-filtered set (own [nationId], `npcType != 5`).
     * Returns `null` when the set is empty (PHP `queryFirstField` → null — no fabrication).
     *
     * **0-draw quarantine (G4):** [rng] is accepted only to document the contract at the call site; this method
     * consumes NO draws — the AI's DRBG cursor (stateIdx/bufferIdx) is unaffected, exactly like MySQL `RAND()`.
     */
    // @ParityQuarantine("G4-order-by-rand"): MySQL RAND() is un-replayable; DRBG stream unaffected (0 draws);
    // deterministic min(no) substitute; reachable only for an npc==5 officer_level==12 ruler — absent in 1010.
    @Suppress("UNUSED_PARAMETER")
    fun seonyangDestGeneralId(nationId: Int, candidates: List<General>, rng: RandUtil): Int? =
        candidates
            .filter { it.nationId == nationId && it.npcType != 5 }
            .minByOrNull { it.id }
            ?.id

    /**
     * `do국가선택`-오랑캐 `ORDER BY RAND()` substitute (`GeneralAI.php:3345`):
     * `SELECT nation FROM general WHERE \`officer_level\`=12 AND npc=9 and nation ORDER BY RAND() limit 1`.
     *
     * The SQL projects the `nation` column ordered by RAND(); the deterministic substitute (G4 §4: "min(nation)
     * = the nation of the smallest such `no`") is the `nationId` of the `min(no)` matching row. The trailing
     * `and nation` is the falsy-`nation` guard (`nation != 0`). Returns `null` when no 오랑캐-ruled nation exists
     * (PHP `queryFirstField` → null).
     *
     * **0-draw quarantine (G4):** consumes NO draws off [rng]; the DRBG cursor is unaffected.
     */
    // @ParityQuarantine("G4-order-by-rand"): MySQL RAND() un-replayable; DRBG stream unaffected (0 draws);
    // deterministic nation-of-min(no) substitute; reachable only for an npc==9 오랑캐 general — absent in 1010.
    @Suppress("UNUSED_PARAMETER")
    fun orankaeRulerNation(candidates: List<General>, rng: RandUtil): Int? =
        candidates
            .filter { it.officerLevel == 12 && it.npcType == 9 && it.nationId != 0 }
            .minByOrNull { it.id }
            ?.nationId

    // ==================================================================================================
    // Action constants + meta-KV delta keys (the emitted che_* + the movingTargetCityID delta key).
    // ==================================================================================================

    /** PHP `:3282` `buildGeneralCommandClass('che_거병', …)` — the do거병 emit. */
    const val FOUND_REBELLION_ACTION: String = "che_거병"

    /** PHP `:3292` `buildGeneralCommandClass('che_해산', …)` — the do해산 emit. */
    const val DISBAND_ACTION: String = "che_해산"

    /** PHP `:3306` `buildGeneralCommandClass('che_건국', …)` — the do건국 emit. */
    const val FOUND_NATION_ACTION: String = "che_건국"

    /** PHP `:3323` `buildGeneralCommandClass('che_선양', …)` — the do선양 emit. */
    const val ABDICATE_ACTION: String = "che_선양"

    /** PHP `:3349` `buildGeneralCommandClass('che_임관', …)` — the do국가선택 오랑캐-임관 emit. */
    const val JOIN_NATION_ACTION: String = "che_임관"

    /** PHP `:3382` `buildGeneralCommandClass('che_랜덤임관', …)` — the do국가선택 random-join emit. */
    const val RANDOM_JOIN_ACTION: String = "che_랜덤임관"

    /** PHP `:3393`/`:3458` `buildGeneralCommandClass('che_이동', …)` — the do국가선택 move-instead emit. */
    const val MOVE_ACTION: String = "che_이동"

    /** PHP `:3412`/`:3440`/`:3448` `buildGeneralCommandClass('che_인재탐색', …)` — the 사망대비/중립 search emit. */
    const val TALENT_SEARCH_ACTION: String = "che_인재탐색"

    /** PHP `:3414`/`:3442`/`:3462` `buildGeneralCommandClass('che_견문', …)` — the 사망대비/중립 tour emit. */
    const val TOUR_ACTION: String = "che_견문"

    /** PHP `:3420`/`:3448`/`:3460` `buildGeneralCommandClass('che_물자조달', …)` — the 사망대비/중립 supply emit. */
    const val SUPPLY_ACTION: String = "che_물자조달"

    /** PHP `:3424`/`:3429` `buildGeneralCommandClass('che_헌납', …)` — the doNPC사망대비 in-nation tribute emit. */
    const val TRIBUTE_ACTION: String = "che_헌납"

    /** The general meta-KV key do해산/do건국 clear (PHP `:3297`/`:3315` `setAuxVar('movingTargetCityID', null)`). */
    const val MOVING_TARGET_KEY: String = "movingTargetCityID"

    /** The value do해산/do건국 write to [MOVING_TARGET_KEY] (PHP `:3297`/`:3315` — null, a ChangeRecorder delta). */
    val MOVING_TARGET_CLEARED_VALUE: Int? = null

    // ==================================================================================================
    // do거병 (:3217-3288) — up to 4 draws:
    //   :3232 nextBool(0.5)=nextBit (&& cityLevel∉{5,6}) → :3258 nextBool()=nextBit PER dist-3 candidate (variable)
    //   → :3268 nextFloat1()*(defaultStatNPCMax+chiefStatMin)/2 → :3278 nextBool(0.0075*more).
    // ==================================================================================================

    /**
     * `do거병`'s non-foundable-city 50% skip (PHP `:3232`
     * `if (($currentCityLevel < 5 || 6 < $currentCityLevel) && $this->rng->nextBool(0.5)) return null;`).
     *
     * The `&&` short-circuits: when [cityLevelNotFoundable] is false (the general sits on a level-5/6 city)
     * the `nextBool(0.5)` right operand is NEVER reached (ZERO draws). The prob is exactly `0.5` → RandUtil's
     * `nextBool` takes the **nextBit** path (one bit, NOT a nextFloat1) — a parity target.
     *
     * @param cityLevelNotFoundable the already-computed `$currentCityLevel < 5 || 6 < $currentCityLevel`.
     * @return true to SKIP the rebellion (`return null`): only when on a non-foundable city AND the 0.5 bit is set.
     */
    fun nonFoundableCitySkip(cityLevelNotFoundable: Boolean, rng: RandUtil): Boolean =
        cityLevelNotFoundable && rng.nextBool(0.5) // PHP :3232 — `&&` suppresses the (nextBit) draw on a level-5/6 city.

    /**
     * `do거병`'s per-dist-3-candidate 50% skip (PHP `:3258` `if ($dist == 3 && $this->rng->nextBool()) continue;`).
     *
     * **The variable-count BFS trap.** This is drawn PER dist-3 candidate inside the `foreach (searchDistance(...,3)
     * as $targetCityID => $dist)` loop (`:3250`), in BFS visitation order, until the first KEPT candidate
     * (`:3261-3262` sets `availableNearCity=true; break;`). A dist-1/dist-2 candidate is NOT subject to this draw
     * (the `$dist == 3 &&` guard); only dist-3 candidates that survive the occupied/level filters reach it. The
     * default prob `0.5` → the **nextBit** path. The caller drives the BFS loop (F-BFS name-order) and invokes this
     * once per dist-3 candidate it must roll on; a `true` result `continue`s (skip this candidate), a `false` keeps it.
     *
     * @return the `nextBool()` result (true = skip this dist-3 candidate and keep scanning).
     */
    fun dist3CandidateSkip(rng: RandUtil): Boolean =
        rng.nextBool() // PHP :3258 — per-dist-3-candidate 50% skip (default 0.5 → nextBit; variable BFS-order count).

    /**
     * `do거병`'s rebellion stat-threshold prop (PHP `:3268`
     * `$prop = $this->rng->nextFloat1() * (GameConst::$defaultStatNPCMax + GameConst::$chiefStatMin) / 2;`).
     *
     * One top-level `nextFloat1` scaled by the stat midpoint [statMidpoint] = `(defaultStatNPCMax + chiefStatMin)
     * / 2` (= `70` in 1010; the adapter supplies the effective per-server value — the family never hard-codes a
     * threshold). Drawn ALWAYS, once `availableNearCity` is true. The caller compares `prop >= ratio` (PHP `:3272`,
     * `ratio = (fullLeadership + fullStrength + fullIntel) / 3`) to decide the `return null` — that comparison is
     * NON-RNG (it does not draw).
     *
     * @param statMidpoint `(defaultStatNPCMax + chiefStatMin) / 2` (the adapter's effective value, e.g. 70.0).
     * @return `nextFloat1() * statMidpoint` (the rebellion threshold prop).
     */
    fun rebellionStatProp(statMidpoint: Double, rng: RandUtil): Double =
        rng.nextFloat1() * statMidpoint // PHP :3268 — one nextFloat1, scaled by (defaultStatNPCMax+chiefStatMin)/2.

    /**
     * `do거병`'s final-gate roll (PHP `:3278` `if (!$this->rng->nextBool(0.0075 * $more)) return null;`).
     *
     * `$more = Util::valueFit(3 - year + init_year, 1, 3)` (PHP `:3277`) ∈ {1,2,3} → prob ∈ {0.0075, 0.015,
     * 0.0225}: NEVER `0.5`, NEVER `>= 1`, NEVER `<= 0` → RandUtil's `nextBool` ALWAYS consumes one `nextFloat1`
     * (no short-circuit, no nextBit path). Used as `!nextBool(...)` in PHP (a `false` → `return null`).
     *
     * @param more the valueFit-clamped founding-deadline factor ∈ {1,2,3} (the adapter computes it).
     * @return the `nextBool(0.0075 * more)` result (PHP returns null = abort when this is false).
     */
    fun foundFinalGate(more: Int, rng: RandUtil): Boolean =
        rng.nextBool(0.0075 * more) // PHP :3278 — final gate; 0.0075*more is always a non-boundary float draw.

    // ==================================================================================================
    // do건국 (:3302-3318) — 2 draws, type-THEN-color: :3304 choice(availableNationType 13→nextInt(12))
    //   THEN :3305 choice(GetNationColors keys 33→nextInt(32)). ASSERT GetNationColors().size==33 (m6).
    // ==================================================================================================

    /** The founding selection: the chosen `nationType` action code + the chosen `colorType` palette INDEX. */
    data class FoundingChoice(val nationType: String, val colorIndex: Int)

    /**
     * `do건국`'s type-THEN-color pick (PHP `:3304-3305`):
     * ```php
     * $nationType  = $this->rng->choice(GameConst::$availableNationType);          // :3304 — 13-elem → nextInt(12)
     * $nationColor = $this->rng->choice(array_keys(GetNationColors()));            // :3305 — 33-elem LIST → nextInt(32)
     * ```
     * **m6 — the count IS the parity target.** `GetNationColors()` is a flat LIST of 33 hex strings (keys 0..32);
     * `array_keys(...)` yields `[0,1,…,32]`, so `choice` draws `nextInt(32)` returning an INDEX. The caller MUST
     * pass `GetNationColors().size` (= 33, asserted GREEN in `domain/GetNationColors.kt`); a wrong count shifts the
     * `nextInt` range → desync. The draw ORDER is type THEN color — do NOT swap.
     *
     * `RandUtil.choice` consumes one underlying `nextInt` per pick (the byte cursor is the parity target); the
     * color pick is expressed as a `choice` over the index LIST `0 until colorCount` (identical `nextInt(size-1)`
     * walk to PHP `choice(array_keys(...))`), returning the chosen index.
     *
     * @param availableNationType `GameConst::$availableNationType` (13-elem insertion order, PHP :3304).
     * @param colorCount `count(GetNationColors())` (MUST be 33 — the m6 parity target).
     * @return the [FoundingChoice] (the picked type code + the picked color INDEX).
     */
    fun pickFounding(availableNationType: List<String>, colorCount: Int, rng: RandUtil): FoundingChoice {
        val nationType = rng.choice(availableNationType) // PHP :3304 — type FIRST (13-elem → nextInt(12)).
        val colorIndex = rng.choice((0 until colorCount).toList()) // PHP :3305 — color SECOND (33-elem → nextInt(32)).
        return FoundingChoice(nationType = nationType, colorIndex = colorIndex)
    }

    // ==================================================================================================
    // do국가선택 (:3334-3401) — :3358 nextBool(0.3) → {:3371 early pow(...) OR :3376 nextBit} → :3390 nextBool(0.2)
    //   → :3393 choice($paths). The :3345 오랑캐 ORDER BY RAND substitute is 0-draw (F-QUAR — orankaeRulerNation).
    // ==================================================================================================

    /**
     * `do국가선택`'s 임관-branch entry gate (PHP `:3358` `if ($this->rng->nextBool(0.3)) { …임관… }`). ALWAYS reached
     * (after the 0-draw 오랑캐 `ORDER BY RAND()` branch). A `true` enters the 임관 sub-tree (the early/post abort
     * draws + the che_랜덤임관 emit); a `false` falls through to the [nationChoiceMoveGate].
     *
     * @return the `nextBool(0.3)` result (true → enter 임관, false → try the move-instead branch).
     */
    fun nationChoiceJoinGate(rng: RandUtil): Boolean =
        rng.nextBool(0.3) // PHP :3358 — the 임관-branch entry gate (always reached).

    /**
     * `do국가선택`'s early-임관-period abort (PHP `:3371`
     * `if ($this->rng->nextBool(pow(1 / ($nationCnt + 1) / pow($notFullNationCnt, 3), 1 / 4))) return null;`).
     *
     * Reached ONLY inside the 임관 branch AND when `year < startyear + 3` (the early period) AND both counts are
     * non-zero (PHP `:3367` returns null with NO draw when either is 0). The prob is a **float-exact** parity
     * target: `pow(1 / (nationCnt + 1) / pow(notFullNationCnt, 3), 1 / 4)` — computed identically to PHP (`1/4`
     * exponent is a true Double `0.25`; `Math.pow` matches PHP `pow`).
     *
     * @param nationCnt `SELECT count(nation) FROM nation` (PHP :3365).
     * @param notFullNationCnt `count(nation WHERE gennum < initialNationGenLimit)` (PHP :3366).
     * @return the `nextBool(pow(...))` result (true → abort the 임관, `return null`).
     */
    fun nationChoiceEarlyAbort(nationCnt: Int, notFullNationCnt: Int, rng: RandUtil): Boolean =
        // PHP :3371 — pow(1 / (nationCnt+1) / pow(notFullNationCnt, 3), 1/4). Float-exact prob (Math.pow == PHP pow).
        rng.nextBool(Math.pow(1.0 / (nationCnt + 1) / Math.pow(notFullNationCnt.toDouble(), 3.0), 1.0 / 4.0))

    /**
     * `do국가선택`'s post-임관-period abort (PHP `:3376` `if ($this->rng->nextBool()) return null;`). Reached inside
     * the 임관 branch when NOT in the early period (`year >= startyear + 3`). The default prob `0.5` → the
     * **nextBit** path (the PHP comment "0.3 * 0.5 = 0.15" documents the effective post-period 임관 rate).
     *
     * @return the `nextBool()` result (true → abort the 임관, `return null`).
     */
    fun nationChoicePostPeriodAbort(rng: RandUtil): Boolean =
        rng.nextBool() // PHP :3376 — post-period abort (default 0.5 → nextBit; "0.3 * 0.5 = 0.15").

    /**
     * `do국가선택`'s move-instead sibling gate (PHP `:3390` `if ($this->rng->nextBool(0.2)) { …che_이동… }`). Reached
     * ONLY when the `:3358` [nationChoiceJoinGate] was FALSE (the sibling-or branch). A `true` builds a che_이동 to
     * a random path-neighbor ([pickNationChoiceMove]); a `false` falls through to `return null` (PHP `:3400`).
     *
     * @return the `nextBool(0.2)` result (true → emit che_이동, false → return null).
     */
    fun nationChoiceMoveGate(rng: RandUtil): Boolean =
        rng.nextBool(0.2) // PHP :3390 — the move-instead sibling gate (only when the :3358 0.3 was false).

    /**
     * `do국가선택`'s move target pick (PHP `:3393` `'destCityID' => $this->rng->choice($paths)`). `$paths =
     * array_keys(CityConst::byID($city['city'])->path)` (PHP `:3391`) — the capital's path-neighbor ids in
     * `CityConst.path` NAME order (F-BFS — REUSE the GREEN adjacency, do NOT rebuild). One underlying `nextInt`.
     *
     * @param paths the current city's path-neighbor ids in `CityConst.path` name/insertion order.
     * @return the picked destination city id.
     */
    fun pickNationChoiceMove(paths: List<Int>, rng: RandUtil): Int =
        rng.choice(paths) // PHP :3393 — one choice over the path-neighbor ids (name-order; throws on empty).

    // ==================================================================================================
    // doNPC사망대비 (:3403-3434) — nationID==0 path draws :3413 nextBool()=nextBit via ||-short-circuit.
    // ==================================================================================================

    /**
     * `doNPC사망대비`'s search-vs-tour switch (PHP `:3413`
     * `if (!$cmd->hasFullConditionMet() || $this->rng->nextBool()) { $cmd = che_견문; }`). Reached ONLY on the
     * `nationID == 0` (재야) path (PHP `:3411`).
     *
     * The `||` short-circuits LEFT-to-RIGHT: when the che_인재탐색 gate FAILS ([talentSearchAllowed] is false →
     * `!hasFullConditionMet()` is true) the `nextBool()` right operand is NEVER reached (ZERO draws) and the result
     * is true (switch to che_견문). Only when the gate PASSES ([talentSearchAllowed] is true) does the `nextBool()`
     * draw (default `0.5` → the **nextBit** path) — a `true` then switches to che_견문, a `false` keeps che_인재탐색.
     *
     * @param talentSearchAllowed the che_인재탐색 `hasFullConditionMet()` result (the bridge gate).
     * @return true to switch the emit to che_견문 (PHP overwrites `$cmd`); false to keep che_인재탐색.
     */
    fun deathPrepTourSwitch(talentSearchAllowed: Boolean, rng: RandUtil): Boolean =
        !talentSearchAllowed || rng.nextBool() // PHP :3413 — `||` suppresses the (nextBit) draw when the gate fails.

    // ==================================================================================================
    // do중립 (:3436-3467) — nationID==0 path draws :3441 nextBool(0.8) via ||; else :3458 choice ALWAYS.
    //   do중립 is the TERMINAL fallback (never null).
    // ==================================================================================================

    /**
     * `do중립`'s search-vs-tour switch (PHP `:3441`
     * `if (!$cmd->hasFullConditionMet() || $this->rng->nextBool(0.8)) { $cmd = che_견문; }`). Reached ONLY on the
     * `nationID == 0` (재야) path (PHP `:3439`).
     *
     * The `||` short-circuits LEFT-to-RIGHT exactly like [deathPrepTourSwitch], but the prob here is `0.8` (NOT
     * the default 0.5 — so a `nextFloat1`, not a nextBit, when the gate passes): the che_인재탐색 gate FAILS
     * ([talentSearchAllowed] false) → ZERO draws, result true (che_견문); the gate PASSES → `nextBool(0.8)` draws.
     *
     * @param talentSearchAllowed the che_인재탐색 `hasFullConditionMet()` result (the bridge gate).
     * @return true to switch the emit to che_견문; false to keep che_인재탐색.
     */
    fun neutralTourSwitch(talentSearchAllowed: Boolean, rng: RandUtil): Boolean =
        !talentSearchAllowed || rng.nextBool(0.8) // PHP :3441 — `||` suppresses the 0.8 draw when the gate fails.

    /**
     * `do중립`'s in-nation candidate pick (PHP `:3458`
     * `$cmd = buildGeneralCommandClass($this->rng->choice($candidate), $this->general, $this->env);`). Reached on
     * the in-nation path (`nationID != 0`). `$candidate` is `['che_물자조달', 'che_인재탐색']` (PHP `:3448`), narrowed
     * to `['che_물자조달']` when `nation.gold < reqNationGold` OR `nation.rice < reqNationRice` (PHP `:3450-3455`).
     *
     * **ALWAYS draws** — `choice` invokes one underlying `nextInt` even for a single-element `$candidate` (RandUtil
     * mirrors PHP `nextInt(count-1)` = `nextInt(0)` → returns the sole index 0, still a draw). The caller builds the
     * candidate list (narrowing by the resource thresholds); the family owns only the always-draw pick. The post-pick
     * `hasFullConditionMet` fallback to che_물자조달/che_견문 (PHP `:3459-3464`) is NON-RNG (the family does not draw it).
     *
     * @param candidate the narrowed candidate action codes (PHP append order; 1 or 2 elements).
     * @return the picked action code.
     */
    fun pickNeutralCandidate(candidate: List<String>, rng: RandUtil): String =
        rng.choice(candidate) // PHP :3458 — ALWAYS one choice (even with a single element; throws on empty).

    // ==================================================================================================
    // WORLD-DRIVEN do<한글> BODIES (the candidate-set assembly over the GeneralAiContext → the PURE primitive
    // above → the candidateAllowed gate → the recordGeneralKv delta → ChosenCommand?).
    //
    // Each body reads the [GeneralAiContext] (rng / instance / world / policy / per-general scalars),
    // assembles the exact candidate set in PHP INSERTION ORDER, pulls its draws off the SOLE per-general
    // `"GeneralAI"` [RandUtil] threaded by reference, GATES the emit via `ctx.candidateAllowed(actionCode,
    // args)` (PHP `$cmd->hasFullConditionMet()`) — except the gate-EXEMPT che_견문/che_물자조달/che_헌납 direct
    // emits — routes meta-KV via `ctx.recordGeneralKv` (decision #12), and returns `ChosenCommand?` (null →
    // the dispatcher falls through to the next priority action). READ-ONLY over GAME ENTITIES. The candidate
    // ORDER (the BFS visitation order, the availableNationType-then-color order, the path-neighbor order) IS
    // the parity target.
    // ====================================================================================================

    /**
     * The dispatch-loop founding `do<한글>` body keyed by ACTION-NAME (the bare `do<한글>` name, NOT `che_*`).
     * The [opensamguk.logic.ai.GeneralAiFactory] registers these into the general dispatch; the loop walks
     * [opensamguk.logic.ai.AutorunGeneralPolicy.priority] first-non-null. doNPC사망대비 is the only loop member
     * in this family; 거병/국가선택/건국/해산/선양/중립 are the PRE-LOOP branch builders wired separately below.
     */
    fun bodies(ctx: GeneralAiContext): Map<String, (LastTurn?) -> ChosenCommand?> = linkedMapOf(
        "NPC사망대비" to { _ -> doNPC사망대비Body(ctx) },
    )

    /** The do거병 PRE-LOOP branch builder (GeneralAI.chooseGeneralTurn step 8, `:3778-3783`). */
    fun do거병(ctx: GeneralAiContext): (LastTurn?) -> ChosenCommand? = { _ -> do거병Body(ctx) }

    /** The do국가선택 PRE-LOOP branch builder (GeneralAI.chooseGeneralTurn step 9, `:3786-3791`). */
    fun do국가선택(ctx: GeneralAiContext): (LastTurn?) -> ChosenCommand? = { _ -> do국가선택Body(ctx) }

    /** The do건국 PRE-LOOP branch builder (GeneralAI.chooseGeneralTurn wandering branch, `:3807`). */
    fun do건국(ctx: GeneralAiContext): (LastTurn?) -> ChosenCommand? = { _ -> do건국Body(ctx) }

    /** The do해산 PRE-LOOP branch builder (GeneralAI.chooseGeneralTurn wandering branch, `:3821`). */
    fun do해산(ctx: GeneralAiContext): (LastTurn?) -> ChosenCommand? = { _ -> do해산Body(ctx) }

    /** The do선양 PRE-LOOP branch builder (GeneralAI.chooseGeneralTurn step 4, `:3745-3750`). */
    fun do선양(ctx: GeneralAiContext): (LastTurn?) -> ChosenCommand? = { _ -> do선양Body(ctx) }

    /** The do중립 TERMINAL fallback builder (GeneralAI.chooseGeneralTurn `:3792`/`:3845`; NEVER null). */
    fun do중립(ctx: GeneralAiContext): (LastTurn?) -> ChosenCommand = { _ -> do중립Body(ctx) }

    // --- do거병 (PHP `:3217-3288`) — makelimit/npc/can건국 gates → 0.5 skip → BFS dist-3 → threshold → final gate ---

    private fun do거병Body(ctx: GeneralAiContext): ChosenCommand? {
        val rng = ctx.rng
        // PHP `:3221-3229` — the three non-RNG early-returns BEFORE any draw.
        if (ctx.selfMakeLimit != 0) return null // :3221 makelimit
        if (ctx.selfNpcType > 2) return null // :3224 npcType>2
        if (!ctx.generalPolicy.can건국) return null // :3227 !can건국
        // PHP `:3231-3234` — the `&&` 0.5 skip (nextBit) ONLY on a non-foundable city (level ∉ {5,6}).
        val cityLevelNotFoundable = !isFoundableCityLevel(ctx.selfCityLevel)
        if (nonFoundableCitySkip(cityLevelNotFoundable, rng)) return null // :3232

        // PHP `:3249-3266` — BFS dist-3 scan (dequeue/visitation order) over the foundOccupiedCities-filtered
        // candidates; the FIRST kept foundable city sets availableNearCity; a dist-3 candidate rolls the 0.5 bit.
        var availableNearCity = false
        val distMap = AiDistance.searchDistanceCities(ctx.selfCityId, 3, ctx.cityConst) // :3250 searchDistance(cityID, 3)
        for ((targetCityID, dist) in distMap) {
            if (ctx.foundOccupiedCities.contains(targetCityID)) continue // :3251
            val cityLevel = ctx.cityConst.byId(targetCityID)?.level ?: continue
            if (!isFoundableCityLevel(cityLevel)) continue // :3255
            if (dist == 3 && dist3CandidateSkip(rng)) continue // :3258 per-dist-3-candidate nextBit
            availableNearCity = true // :3261
            break
        }
        if (!availableNearCity) return null // :3264

        // PHP `:3268-3274` — nextFloat1()*midpoint vs the stat ratio.
        val prop = rebellionStatProp(ctx.foundStatMidpoint, rng) // :3268
        val ratio = (ctx.fullLeadership + ctx.fullStrength + ctx.fullIntel) / 3.0 // :3269
        if (prop >= ratio) return null // :3272

        // PHP `:3277-3280` — the final 0.0075*more gate (more is the adapter's valueFit(3-year+init_year,1,3)).
        if (!foundFinalGate(ctx.foundDeadlineMore, rng)) return null // :3278

        if (!ctx.candidateAllowed(FOUND_REBELLION_ACTION, emptyMap())) return null // :3283 hasFullConditionMet
        return ChosenCommand(FOUND_REBELLION_ACTION, emptyMap()) // :3287
    }

    // --- do해산 (PHP `:3290-3300`) — ZERO draws; gate then movingTargetCityID=null delta ---

    private fun do해산Body(ctx: GeneralAiContext): ChosenCommand? {
        if (!ctx.candidateAllowed(DISBAND_ACTION, emptyMap())) return null // :3293 hasFullConditionMet
        // PHP `:3297` — setAuxVar('movingTargetCityID', null) AFTER the gate (decision #12; NOT inline).
        ctx.recordGeneralKv(ctx.selfGeneralId, MOVING_TARGET_KEY, MOVING_TARGET_CLEARED_VALUE)
        return ChosenCommand(DISBAND_ACTION, emptyMap()) // :3299
    }

    // --- do건국 (PHP `:3302-3318`) — type-THEN-color pick; che_건국 + clear delta ---

    private fun do건국Body(ctx: GeneralAiContext): ChosenCommand? {
        // PHP `:3304-3305` — the two picks (type FIRST, color SECOND), over the GREEN 13-elem/33-elem lists.
        val picked = pickFounding(GameConst.availableNationType, GetNationColors().size, ctx.rng)
        // PHP `:3307` — nationName = "㉿" + mb_substr(getName(), 1) (drop the surname's first char).
        val nationName = "㉿" + ctx.selfGeneralName.drop(1)
        val args = linkedMapOf<String, Any?>(
            "nationName" to nationName,
            "nationType" to picked.nationType,
            "colorType" to picked.colorIndex,
        )
        if (!ctx.candidateAllowed(FOUND_NATION_ACTION, args)) return null // :3311 hasFullConditionMet
        // PHP `:3315` — setAuxVar('movingTargetCityID', null) AFTER the gate.
        ctx.recordGeneralKv(ctx.selfGeneralId, MOVING_TARGET_KEY, MOVING_TARGET_CLEARED_VALUE)
        return ChosenCommand(FOUND_NATION_ACTION, args) // :3317
    }

    // --- do선양 (PHP `:3320-3332`) — ZERO draws; che_선양 {destGeneralID = min(no) F-QUAR substitute} ---

    private fun do선양Body(ctx: GeneralAiContext): ChosenCommand? {
        // PHP `:3324` — ORDER BY RAND() destGeneralID; the deterministic min(no) substitute (F-QUAR, 0 draws).
        val eligibleCandidates = ctx.seonyangCandidates
            .filter { it.nationId == ctx.instance.nation.nation && it.npcType != 5 }
        val destGeneralID = if (ctx.externalSqlRandSelector != null) {
            ctx.externalSqlRandSelector.select(
                ExternalSqlRandBranch.SEONYANG_DEST_GENERAL,
                ctx.selfGeneralId,
                ctx.env.year,
                ctx.env.month,
                eligibleCandidates.map { it.id },
            )
        } else {
            seonyangDestGeneralId(ctx.instance.nation.nation, eligibleCandidates, ctx.rng)
        }
        val args = linkedMapOf<String, Any?>("destGeneralID" to destGeneralID)
        if (!ctx.candidateAllowed(ABDICATE_ACTION, args)) return null // :3327 hasFullConditionMet
        return ChosenCommand(ABDICATE_ACTION, args) // :3331
    }

    // --- do국가선택 (PHP `:3334-3401`) — 오랑캐 substitute → 0.3 gate → 임관 / 0.2 move / null ---

    private fun do국가선택Body(ctx: GeneralAiContext): ChosenCommand? {
        val rng = ctx.rng
        // PHP `:3343-3356` — npc==9 오랑캐 → ORDER BY RAND ruler substitute (0-draw) → che_임관.
        if (ctx.selfNpcType == 9) {
            val eligibleCandidates = ctx.orankaeRulerCandidates
                .filter { it.officerLevel == 12 && it.npcType == 9 && it.nationId != 0 }
            val rulerNation = if (ctx.externalSqlRandSelector != null) {
                ctx.externalSqlRandSelector.select(
                    ExternalSqlRandBranch.ORANKAE_RULER_NATION,
                    ctx.selfGeneralId,
                    ctx.env.year,
                    ctx.env.month,
                    eligibleCandidates.map { it.nationId },
                )
            } else {
                orankaeRulerNation(eligibleCandidates, rng)
            }
            if (rulerNation != null && rulerNation != 0) { // :3348 `if ($rulerNation)`
                val args = linkedMapOf<String, Any?>("destNationID" to rulerNation)
                if (!ctx.candidateAllowed(JOIN_NATION_ACTION, args)) return null // :3350
                return ChosenCommand(JOIN_NATION_ACTION, args) // :3354
            }
        }

        // PHP `:3358` — the 임관-branch entry gate (always reached after the 0-draw 오랑캐 branch).
        if (nationChoiceJoinGate(rng)) { // :3358 nextBool(0.3)
            // PHP `:3359-3361` — affinity==999 aborts the 임관 sub-tree with NO further draw.
            if (ctx.selfAffinity == 999) return null // :3361

            if (ctx.env.year < ctx.env.startYear + 3) {
                // PHP `:3363-3373` — the early-임관 period: count-gated, then a float-exact abort draw.
                if (ctx.nationCount == 0 || ctx.notFullNationCount == 0) return null // :3367 NO draw
                if (nationChoiceEarlyAbort(ctx.nationCount, ctx.notFullNationCount, rng)) return null // :3371
            } else {
                // PHP `:3374-3379` — the post-임관 period: a fixed nextBool()=nextBit abort.
                if (nationChoicePostPeriodAbort(rng)) return null // :3376
            }

            // PHP `:3382-3387` — che_랜덤임관 + gate.
            if (!ctx.candidateAllowed(RANDOM_JOIN_ACTION, emptyMap())) return null // :3383
            return ChosenCommand(RANDOM_JOIN_ACTION, emptyMap()) // :3387
        }

        // PHP `:3390-3399` — the sibling move-instead branch (only when the :3358 0.3 was false).
        if (nationChoiceMoveGate(rng)) { // :3390 nextBool(0.2)
            val paths = ctx.cityConst.byId(ctx.selfCityId)?.path?.keys?.toList() ?: emptyList() // :3391 name-order
            val destCityID = pickNationChoiceMove(paths, rng) // :3393 choice($paths)
            val args = linkedMapOf<String, Any?>("destCityID" to destCityID)
            if (!ctx.candidateAllowed(MOVE_ACTION, args)) return null // :3394
            return ChosenCommand(MOVE_ACTION, args) // :3398
        }
        return null // :3400
    }

    // --- doNPC사망대비 (PHP `:3403-3434`) — killturn>5 null; 재야 search/tour; in-nation 물자조달/헌납 ---

    private fun doNPC사망대비Body(ctx: GeneralAiContext): ChosenCommand? {
        // PHP `:3407-3409` — killturn>5 → null (no draw).
        if (ctx.selfKillturn > 5) return null // :3407

        // PHP `:3411-3417` — the 재야 (nationID==0) path.
        if (ctx.instance.nation.nation == 0) {
            // PHP `:3413` — che_인재탐색 gate THEN the `||`-short-circuit nextBool()=nextBit → che_견문.
            val talentSearchAllowed = ctx.candidateAllowed(TALENT_SEARCH_ACTION, emptyMap()) // :3412 hasFullConditionMet
            return if (deathPrepTourSwitch(talentSearchAllowed, ctx.rng)) {
                ChosenCommand(TOUR_ACTION, emptyMap()) // :3414 che_견문 (gate-exempt emit)
            } else {
                ChosenCommand(TALENT_SEARCH_ACTION, emptyMap()) // :3416 che_인재탐색
            }
        }

        // PHP `:3419-3421` — gold + rice == 0 → che_물자조달 (gate-exempt emit, no draw).
        if (ctx.selfGold + ctx.selfRice == 0) {
            return ChosenCommand(SUPPLY_ACTION, emptyMap()) // :3420
        }

        // PHP `:3423-3433` — che_헌납 {isGold, amount=maxResourceActionAmount} (the GameConst, NOT the instance value).
        return if (ctx.selfGold >= ctx.selfRice) {
            ChosenCommand(TRIBUTE_ACTION, linkedMapOf("isGold" to true, "amount" to GameConst.maxResourceActionAmount)) // :3424
        } else {
            ChosenCommand(TRIBUTE_ACTION, linkedMapOf("isGold" to false, "amount" to GameConst.maxResourceActionAmount)) // :3429
        }
    }

    // --- do중립 (PHP `:3436-3467`) — TERMINAL fallback (never null) ---

    private fun do중립Body(ctx: GeneralAiContext): ChosenCommand {
        val rng = ctx.rng
        // PHP `:3439-3445` — the 재야 (nationID==0) path.
        if (ctx.instance.nation.nation == 0) {
            // PHP `:3441` — che_인재탐색 gate THEN the `||`-short-circuit nextBool(0.8) → che_견문.
            val talentSearchAllowed = ctx.candidateAllowed(TALENT_SEARCH_ACTION, emptyMap()) // :3440 hasFullConditionMet
            return if (neutralTourSwitch(talentSearchAllowed, rng)) {
                ChosenCommand(TOUR_ACTION, emptyMap()) // :3442 che_견문 (gate-exempt emit)
            } else {
                ChosenCommand(TALENT_SEARCH_ACTION, emptyMap()) // :3444 che_인재탐색
            }
        }

        // PHP `:3448-3455` — the in-nation candidate list (append order), narrowed by the resource thresholds.
        var candidate = listOf(SUPPLY_ACTION, TALENT_SEARCH_ACTION) // :3448
        if (ctx.instance.nation.gold < ctx.nationPolicy.reqNationGold) candidate = listOf(SUPPLY_ACTION) // :3450-3452
        if (ctx.instance.nation.rice < ctx.nationPolicy.reqNationRice) candidate = listOf(SUPPLY_ACTION) // :3453-3455

        // PHP `:3458` — the ALWAYS-draw choice over the candidate list.
        val picked = pickNeutralCandidate(candidate, rng)
        // PHP `:3459-3464` — the post-pick hasFullConditionMet fallback to che_물자조달 then che_견문 (NON-RNG).
        if (ctx.candidateAllowed(picked, emptyMap())) return ChosenCommand(picked, emptyMap()) // :3458/:3466
        if (ctx.candidateAllowed(SUPPLY_ACTION, emptyMap())) return ChosenCommand(SUPPLY_ACTION, emptyMap()) // :3460
        return ChosenCommand(TOUR_ACTION, emptyMap()) // :3462 che_견문
    }
}
