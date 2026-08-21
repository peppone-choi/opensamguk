package opensamguk.logic.ai.families

import opensamguk.common.constants.GameConst
import opensamguk.common.rng.RandUtil
import opensamguk.logic.ai.AiInstanceState
import opensamguk.logic.ai.ChosenCommand
import opensamguk.logic.ai.GeneralAiContext
import opensamguk.logic.ai.bfs.AiDistance
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.util.valueFit
import opensamguk.logic.world.isFoundableCityLevel
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * L-GENWAR — the general war/movement `do<한글>` command family:
 * 전투준비 / 소집해제 / 출병 / 헌납 / 워프트리오(후방·전방·내정) / 귀환 / 집합 / 방랑군이동.
 *
 * GRAND TRUTH = PHP `legacy/devsam-core/hwe/sammo/GeneralAI.php` (read in full):
 *  - `do전투준비`   (`:2653-2682`, emits che_훈련/che_사기진작): one terminal `choiceUsingWeightPair($cmdList)`
 *    (`:2681`, only when `$cmdList` non-empty; `:2678` `if (!$cmdList) return null;` empty-guard, NO draw).
 *  - `do소집해제`   (`:2684-2703`, emits che_소집해제): `nextBool(0.75)` (`:2695`) **ALWAYS drawn** — there is NO
 *    `&&` guard; it is reached after 3 non-RNG early-returns (attackable / dipState!=평화 / crew==0).
 *  - `do출병`       (`:2706-2775`, emits che_출병): the `nextBool(0.7)` at `:2720` is pulled via the `&&` chain
 *    `($nation['rice'] < $baserice) && $general->getNPCType() >= 2 && $this->rng->nextBool(0.7)` — drawn **BEFORE**
 *    four non-RNG early-returns (`:2729` train / `:2732` atmos / `:2735` crew / `:2739` front==0) — pin the EXACT
 *    statement order (m1): a general failing the train/atmos/crew/front gate STILL consumes the `:2720` draw when
 *    low-rice && npc>=2. Then `choice($attackableCities)` (`:2769`) — `choice([])` THROWS (`nextInt(-1)`); the
 *    `:2765` `count==0` `RuntimeException('출병 불가')` guard makes the empty path unreachable (m3).
 *  - `doNPC헌납`    (`:2785-2863`, emits che_헌납): the `nextBool(($genRes/$reqRes)-0.5)` at `:2841` is drawn
 *    **PER-RESOURCE** inside `foreach ($resourceMap as ...)` (`:2796`) — the map is rice-THEN-gold (`:2790-2792`)
 *    → VARIABLE 0-2 draws (m2), used as the continue-gate `if ($reqRes > 0 && !nextBool(...)) continue;` (the `&&`
 *    suppresses the draw when `reqRes <= 0`). Then ONE terminal `choiceUsingWeightPair($args)` (`:2858`; `:2854`
 *    `if (!$args) return null;` empty-guard, NO draw).
 *  - `do후방워프`   (`:2866-2970`, emits che_NPC능동 optionText='순간이동'): one `choiceUsingWeight($recruitableCityList)`
 *    (`:2960`); the recruitableCityList is `[cityID => pop_ratio]` built backup-then-supply (the build ORDER is a
 *    parity target).
 *  - `do전방워프`   (`:2972-3020`, emits che_NPC능동): one `choiceUsingWeight($candidateCities)` (`:3011`);
 *    `[cityID => 'important']` over supplied frontCities.
 *  - `do내정워프`   (`:3022-3092`, emits che_NPC능동): `nextBool(0.6)` (`:3029`) **ALWAYS** → `nextBool($warpProp)`
 *    (`:3050`, the `$warpProp` product-of-develVals gate, used as `!nextBool(...)`; at `warpProp>=1`/`<=0` the
 *    RandUtil `nextBool` short-circuits and consumes ZERO underlying bytes — the boundary trap) →
 *    `choiceUsingWeight($candidateCities)` (`:3085`).
 *  - `do귀환`       (`:3095-3109`, emits che_귀환): **ZERO RNG draws** — a deterministic supply/nation gate
 *    (`if ($city['nation'] == own && $city['supply']) return null;`).
 *  - `do집합`       (`:3111-3125`, emits che_집합): **GATE-EXEMPT** (no `hasFullConditionMet()`; always returns its
 *    cmd) but NOT draw-free (m4): `getNPCType()==5` draws `nextRangeInt(2,4)` (`:3116`) and writes
 *    `killturn = ($killturn + draw) % 5 + 70` (`:3116-3118`) as a ChangeRecorder delta (decision #12 — NOT inline,
 *    NOT a static Map); a non-npc-5 general makes ZERO draws.
 *  - `do방랑군이동`  (`:3127-3215`, emits che_인재탐색/che_이동): up to 2 `choiceUsingWeightPair` —
 *    `:3180` target pick (only when the cached `movingTargetCityID===null`; `:3177` empty-guard, NO draw) and
 *    `:3208` next-hop pick (`:3203` empty-guard, NO draw). The `choice([])` empty paths are both guarded (m3).
 *
 * This file holds the PURE, draw-order-bearing primitives each `do<한글>` composes (it mirrors the established
 * `GenFoundFamily`/`GenDomesticFamily`/`NationDeployFamily`/`NationRewardFamily`/`NationDiploFamily` pure-helper
 * shape): the candidate-set construction — the backupCities/supplyCities buckets, the BFS dist maps, the
 * resourceMap thresholds, the develRate `$warpProp` product, the cmdList weights, the `hasFullConditionMet`
 * gates — is the foundations'/adapter's job; the family owns the per-method DRAW ORDER + COUNT on the shared
 * `"GeneralAI"` [RandUtil], the emitted RAW arg shape, the meta-KV delta key (`killturn`), and the deterministic
 * killturn arithmetic. F-BRIDGE's `candidateAllowed` gate (the PHP `hasFullConditionMet()` analogue)
 * accepts/rejects the emit — the family does NOT re-implement that gate.
 *
 * `choiceUsingWeight` over Int-keyed maps (the warp dest lists) is expressed via [RandUtil.choiceUsingWeightPair]
 * over insertion-ordered `(cityId, weight)` pairs — the IDENTICAL one-`nextFloat1` walk semantics (the
 * String-keyed `RandUtil.choiceUsingWeight` would require Int→String key juggling); the candidate ORDER is the
 * parity target. `choice($attackableCities)` is expressed via [RandUtil.choice] over the insertion-ordered list
 * (index walk; the empty-throw mirrors PHP `nextInt(-1)`).
 *
 * NO draws happen outside the documented `nextBool`/`nextRangeInt`/`choiceUsingWeight(Pair)`/`choice` sites.
 * PURE `:logic`.
 */
object GenWarMoveFamily {

    /** PHP `:2665` `buildGeneralCommandClass('che_훈련', …)` — a do전투준비 emit. */
    const val TRAIN_ACTION: String = "che_훈련"

    /** PHP `:2672` `buildGeneralCommandClass('che_사기진작', …)` — a do전투준비 emit. */
    const val MORALE_ACTION: String = "che_사기진작"

    /** PHP `:2698` `buildGeneralCommandClass('che_소집해제', …)` — the do소집해제 emit. */
    const val DISBAND_ACTION: String = "che_소집해제"

    /** PHP `:2769` `buildGeneralCommandClass('che_출병', …)` — the do출병 emit. */
    const val SORTIE_ACTION: String = "che_출병"

    /** PHP `:2858` `buildGeneralCommandClass('che_헌납', …)` — the doNPC헌납 emit. */
    const val TRIBUTE_ACTION: String = "che_헌납"

    /** PHP `:2958`/`:3009`/`:3083` `buildGeneralCommandClass('che_NPC능동', …)` — the warp-trio emit. */
    const val WARP_ACTION: String = "che_NPC능동"

    /** PHP `:2959`/`:3010`/`:3084` `'optionText' => '순간이동'` — the warp-trio option text. */
    const val WARP_OPTION_TEXT: String = "순간이동"

    /** PHP `:3103` `buildGeneralCommandClass('che_귀환', …)` — the do귀환 emit. */
    const val RETURN_ACTION: String = "che_귀환"

    /** PHP `:3121` `buildGeneralCommandClass('che_집합', …)` — the do집합 emit. */
    const val ASSEMBLE_ACTION: String = "che_집합"

    /** PHP `:3185` `buildGeneralCommandClass('che_인재탐색', …)` — the do방랑군이동 at-target emit. */
    const val WANDER_SEARCH_ACTION: String = "che_인재탐색"

    /** PHP `:3207` `buildGeneralCommandClass('che_이동', …)` — the do방랑군이동 next-hop emit. */
    const val WANDER_MOVE_ACTION: String = "che_이동"

    /** The general meta-KV key do집합 writes for an npc==5 general (PHP `:3118` `setVar('killturn', …)` delta). */
    const val KILLTURN_KEY: String = "killturn"

    // ==================================================================================================
    // do전투준비 (:2653-2682) — one terminal choiceUsingWeightPair (:2681); empty cmdList → null, NO draw (:2678).
    // ==================================================================================================

    /**
     * `do전투준비`'s terminal pick (PHP `:2678-2681`): returns null with NO draw when `$cmdList` is empty
     * (`:2678` `if (!$cmdList) return null;`), else exactly ONE `choiceUsingWeightPair($cmdList)` (`:2681`).
     * The `(actionCode, weight)` candidate list is built deterministically by the adapter (che_훈련 weighted
     * `maxTrainByCommand / valueFit(train,1)` when `train < properWarTrainAtmos` and `hasFullConditionMet`,
     * che_사기진작 similarly); the build/append order IS a parity target.
     *
     * @param cmdList the `(actionCode, weight)` pairs in PHP append order.
     * @return the picked action code, or null when [cmdList] is empty.
     */
    fun pickBattlePrepCommand(cmdList: List<Pair<String, Double>>, rng: RandUtil): String? {
        if (cmdList.isEmpty()) {
            return null // PHP :2678 `if (!$cmdList) return null;` — the empty guard, BEFORE any draw.
        }
        return rng.choiceUsingWeightPair(cmdList) // PHP :2681 — the ONE terminal draw (one nextFloat1).
    }

    // ==================================================================================================
    // do소집해제 (:2684-2703) — nextBool(0.75) ALWAYS (:2695, no && guard).
    // ==================================================================================================

    /**
     * `do소집해제`'s 75% skip roll (PHP `:2695` `if ($this->rng->nextBool(0.75)) return null;`). There is NO `&&`
     * guard, so this `nextBool(0.75)` ALWAYS draws — it is reached only AFTER the three non-RNG early-returns
     * (`:2686` attackable, `:2689` dipState!=평화, `:2692` crew==0), which the adapter checks before calling.
     *
     * @return true to SKIP disbanding (`return null`): whenever the 0.75 roll succeeds.
     */
    fun disbandSkip(rng: RandUtil): Boolean =
        rng.nextBool(0.75) // PHP :2695 — ALWAYS-drawn 75% skip (no `&&` guard).

    // ==================================================================================================
    // do출병 (:2706-2775) — nextBool(0.7) via && BEFORE four non-RNG early-returns (:2720); choice(cities) (:2769).
    // ==================================================================================================

    /**
     * `do출병`'s 70% sortie-skip roll (PHP `:2720`
     * `if (($nation['rice'] < GameConst::$baserice) && $general->getNPCType() >= 2 && $this->rng->nextBool(0.7))
     *      return null;`).
     *
     * **m1 — the statement-order trap.** This `&&` chain is evaluated BEFORE the four non-RNG early-returns at
     * `:2729` (train), `:2732` (atmos), `:2735` (crew), `:2739` (front==0). Reordering those guards before this
     * draw (a tempting "optimization") would drop/move the draw. The `&&` chain short-circuits LEFT-to-RIGHT:
     * [riceBelowBaserice] must be true to evaluate [npcAtLeast2], which must be true to reach `nextBool(0.7)`.
     *
     * @return true to SKIP the sortie (`return null`): only when low-rice AND npc>=2 AND the 0.7 roll succeeds.
     */
    fun sortieSkip(riceBelowBaserice: Boolean, npcAtLeast2: Boolean, rng: RandUtil): Boolean =
        riceBelowBaserice && npcAtLeast2 && rng.nextBool(0.7) // PHP :2720 — `&&` chain, draw reached only if both left.

    /**
     * `do출병`'s target pick (PHP `:2769` `'destCityID' => $this->rng->choice($attackableCities)`). One underlying
     * `nextInt` over the insertion-ordered `$attackableCities` list (the DB-row order from the `WHERE nation IN ..
     * AND city IN ..` query — itself a parity target). `choice([])` THROWS (`nextInt(-1)`); the PHP `:2765`
     * `count==0` `RuntimeException('출병 불가')` guard makes the empty path unreachable, so the adapter never passes
     * an empty list (m3) — this mirrors that with [RandUtil.choice]'s empty-throw.
     *
     * @param attackableCities the candidate city ids in DB-row insertion order (non-empty per the :2765 guard).
     * @return the picked destination city id.
     */
    fun pickSortieTarget(attackableCities: List<Int>, rng: RandUtil): Int =
        rng.choice(attackableCities) // PHP :2769 — one choice (throws on empty, guarded upstream at :2765, m3).

    // ==================================================================================================
    // doNPC헌납 (:2785-2863) — nextBool((genRes/reqRes)-0.5) PER-RESOURCE rice-then-gold (:2841) → VARIABLE 0-2;
    //   terminal choiceUsingWeightPair($args) (:2858). The :2841 draw is the continue-gate `!nextBool(...)`.
    // ==================================================================================================

    /** A tribute candidate arg (PHP `:2848-2851` `['isGold' => ..., 'amount' => ...]`) — the RAW emitted arg. */
    data class TributeArg(val isGold: Boolean, val amount: Int)

    /**
     * `doNPC헌납`'s per-resource tribute gate (PHP `:2841`
     * `if ($reqRes > 0 && !$this->rng->nextBool(($genRes / $reqRes) - 0.5)) { continue; }`).
     *
     * **m2 — the variable per-resource count.** The PHP `foreach ($resourceMap as ...)` (`:2796`) walks rice
     * THEN gold (`:2790-2792`); each iteration MAY reach this `:2841` draw → a VARIABLE 0-2 draw stream across
     * the two resources (NOT a single draw). The `&&` short-circuits: when [reqResPositive] is false (`reqRes<=0`)
     * the `nextBool` right operand is NEVER reached (ZERO draws for that resource). The prob is `(genRes/reqRes)-0.5`.
     *
     * The caller drives the loop (rice then gold), invoking this once per resource that survives the upstream
     * `$genRes < $reqRes*1.5` / threshold checks; the boolean is used as the continue-gate `!result` in PHP.
     *
     * @return the `nextBool((genRes/reqRes)-0.5)` result for this resource (false when [reqResPositive] is false,
     *  with NO draw consumed — the PHP `&&` left operand decides).
     */
    fun tributeResourceGate(reqResPositive: Boolean, genRes: Double, reqRes: Double, rng: RandUtil): Boolean =
        reqResPositive && rng.nextBool(genRes / reqRes - 0.5) // PHP :2841 — per-resource, suppressed when reqRes<=0.

    /**
     * `doNPC헌납`'s terminal pick (PHP `:2854-2858`): returns null with NO draw when `$args` is empty
     * (`:2854` `if (!$args) return null;`), else exactly ONE `choiceUsingWeightPair($args)` (`:2858`, weight =
     * the resource amount). The `(TributeArg, weight)` candidate list is built by the caller in PHP append order
     * (rice-then-gold, subject to the per-resource thresholds); the build/append order IS a parity target.
     *
     * @param args the `(TributeArg, weight)` pairs in PHP append order.
     * @return the picked tribute arg, or null when [args] is empty.
     */
    fun pickTributeArg(args: List<Pair<TributeArg, Double>>, rng: RandUtil): TributeArg? {
        if (args.isEmpty()) {
            return null // PHP :2854 `if (!$args) return null;` — the empty guard, BEFORE any draw.
        }
        return rng.choiceUsingWeightPair(args) // PHP :2858 — the ONE terminal draw (one nextFloat1).
    }

    // ==================================================================================================
    // 워프 트리오 (:2866-3092) — 후방워프 :2960 ; 전방워프 :3011 ; 내정워프 :3029/:3050/:3085.
    // ==================================================================================================

    /**
     * `do후방워프`'s warp-dest pick (PHP `:2960` `'destCityID' => $this->rng->choiceUsingWeight($recruitableCityList)`).
     * `$recruitableCityList` is `[cityID => pop_ratio]`, built backup-cities-first (`:2910-2926`) then, only if
     * empty, supply-cities (`:2929-2949`, the front-city entries halved); the build ORDER is the parity target.
     * Expressed via [RandUtil.choiceUsingWeightPair] over insertion-ordered `(cityId, weight)` pairs (the IDENTICAL
     * one-`nextFloat1` walk; the candidate ORDER matches PHP `array_keys` insertion order).
     *
     * @param recruitableCityList the `(cityId, pop_ratio)` pairs in PHP backup-then-supply insertion order.
     * @return the picked destination city id.
     */
    fun pickBackupWarpDest(recruitableCityList: Map<Int, Double>, rng: RandUtil): Int =
        rng.choiceUsingWeightPair(recruitableCityList.toList()) // PHP :2960 — one choiceUsingWeight (insertion order).

    /**
     * `do전방워프`'s warp-dest pick (PHP `:3011` `'destCityID' => $this->rng->choiceUsingWeight($candidateCities)`).
     * `$candidateCities` is `[frontCity['city'] => frontCity['important']]` over supplied front cities (`:3002-3007`);
     * the iteration order over `$this->frontCities` is the parity target. Expressed via
     * [RandUtil.choiceUsingWeightPair] over insertion-ordered `(cityId, weight)` pairs.
     *
     * @param candidateCities the `(cityId, important)` pairs in `frontCities` insertion order.
     * @return the picked destination city id.
     */
    fun pickFrontWarpDest(candidateCities: Map<Int, Double>, rng: RandUtil): Int =
        rng.choiceUsingWeightPair(candidateCities.toList()) // PHP :3011 — one choiceUsingWeight (insertion order).

    /**
     * `do내정워프`'s 60% skip roll (PHP `:3029` `if ($this->rng->nextBool(0.6)) return null;`). There is NO `&&`
     * guard, so this `nextBool(0.6)` ALWAYS draws — it is reached after the non-RNG `t통솔장 && 외교상태` early-return
     * (`:3024`), which the adapter checks before calling.
     *
     * @return true to SKIP the internal warp (`return null`): whenever the 0.6 roll succeeds.
     */
    fun internalWarpSkip(rng: RandUtil): Boolean =
        rng.nextBool(0.6) // PHP :3029 — ALWAYS-drawn 60% skip (no `&&` guard).

    /**
     * `do내정워프`'s proceed gate (PHP `:3050` `if (!$this->rng->nextBool($warpProp)) return null;`). `$warpProp`
     * is the product of the general's matching develVals (`:3036-3043`, seeded 1, multiplied per matching develType).
     *
     * **The boundary trap.** [RandUtil.nextBool] short-circuits: `warpProp >= 1` → returns true with ZERO underlying
     * bytes consumed (the cursor is unaffected); `warpProp <= 0` → returns false with ZERO bytes; only `0 < warpProp
     * < 1` consumes one `nextFloat1`. The `nextBool` is still INVOKED in every case (so the call counts), but the
     * underlying byte consumption is conditional — a parity target the gate asserts cursor-for-cursor. Used as
     * `!nextBool(...)` in PHP (the proceed-gate).
     *
     * @return the `nextBool($warpProp)` result (PHP returns null = skip when this is false).
     */
    fun internalWarpProceedGate(warpProp: Double, rng: RandUtil): Boolean =
        rng.nextBool(warpProp) // PHP :3050 — proceed gate; 0-byte at the warpProp>=1/<=0 boundary.

    /**
     * `do내정워프`'s warp-dest pick (PHP `:3085` `'destCityID' => $this->rng->choiceUsingWeight($candidateCities)`).
     * `$candidateCities` is `[candidate['city'] => 1/(realDevelRate*sqrt(gens+1))]` over supplyCities that survive
     * the `realDevelRate >= 0.95` filter (`:3056-3077`); the iteration order over `$this->supplyCities` is the
     * parity target. Expressed via [RandUtil.choiceUsingWeightPair] over insertion-ordered `(cityId, weight)` pairs.
     *
     * @param candidateCities the `(cityId, weight)` pairs in `supplyCities` insertion order.
     * @return the picked destination city id.
     */
    fun pickInternalWarpDest(candidateCities: Map<Int, Double>, rng: RandUtil): Int =
        rng.choiceUsingWeightPair(candidateCities.toList()) // PHP :3085 — one choiceUsingWeight (insertion order).

    // ==================================================================================================
    // do집합 (:3111-3125) — GATE-EXEMPT; npc==5 draws nextRangeInt(2,4) + killturn delta (m4, decision #12).
    // ==================================================================================================

    /**
     * `do집합`'s killturn reroll (PHP `:3116-3118`, npc==5 ONLY):
     * `$newKillTurn = ($general->getVar('killturn') + $this->rng->nextRangeInt(2, 4)) % 5; $newKillTurn += 70;`
     *
     * **m4 — gate-exempt but not draw-free.** `do집합` has no `hasFullConditionMet()` gate (it always returns its
     * cmd), but an npc==5 general draws this ONE `nextRangeInt(2,4)` and writes the resulting `killturn` value as a
     * ChangeRecorder delta (decision #12 — NOT an inline DB write, NOT a static Map). A non-npc-5 general makes ZERO
     * draws and writes nothing (the adapter calls this only for npc==5). The arithmetic is [computeAssembleKillturn].
     *
     * @param currentKillturn the general's pre-turn `killturn` (the read observes the pre-turn snapshot).
     * @return the new `killturn` value (`(currentKillturn + draw) % 5 + 70`), to be queued as a meta-KV delta.
     */
    fun assembleKillturnReroll(currentKillturn: Int, rng: RandUtil): Int =
        computeAssembleKillturn(currentKillturn, rng.nextRangeInt(2, 4)) // PHP :3116 — one nextRangeInt(2,4) draw.

    /**
     * The `do집합` killturn arithmetic (PHP `:3116-3117`, factored out for a draw-free unit assertion):
     * `(currentKillturn + draw) % 5 + 70`. The `% 5` then `+ 70` order matches PHP (the modulo binds to the sum,
     * then 70 is added). Both [currentKillturn] and [draw] are non-negative in PHP (killturn is a turn count,
     * draw ∈ [2,4]), so Kotlin `%` and PHP `%` agree.
     */
    fun computeAssembleKillturn(currentKillturn: Int, draw: Int): Int =
        (currentKillturn + draw) % 5 + 70 // PHP :3116-3117 — (killturn + nextRangeInt(2,4)) % 5, then += 70.

    // ==================================================================================================
    // do방랑군이동 (:3127-3215) — up to 2 choiceUsingWeightPair (:3180 target ; :3208 next-hop); choice([]) guarded.
    // ==================================================================================================

    /**
     * `do방랑군이동`'s wander-target pick (PHP `:3177-3181`): returns null with NO draw when `$candidateCities` is
     * empty (`:3177` `if (!$candidateCities) return null;`, m3 — never `choice([])`), else exactly ONE
     * `choiceUsingWeightPair($candidateCities)` (`:3180`, weight = `1 / pow(2, $dist)` over the level-5/6 cities
     * within searchDistance 4 that are not occupied; the BFS visitation order seeds the candidate list — a parity
     * target). Reached ONLY when the cached `movingTargetCityID === null` (`:3163`).
     *
     * @param candidateCities the `(cityId, 1/2^dist)` pairs in BFS visitation insertion order.
     * @return the picked target city id, or null when [candidateCities] is empty.
     */
    fun pickWanderTarget(candidateCities: List<Pair<Int, Double>>, rng: RandUtil): Int? {
        if (candidateCities.isEmpty()) {
            return null // PHP :3177 `if (!$candidateCities) return null;` — the empty guard, BEFORE any draw (m3).
        }
        return rng.choiceUsingWeightPair(candidateCities) // PHP :3180 — the ONE target draw (one nextFloat1).
    }

    /**
     * `do방랑군이동`'s next-hop pick (PHP `:3203-3208`): returns null with NO draw when `$candidateCities` is empty
     * (`:3203` `if (!$candidateCities) return null;`, m3 — never `choice([])`), else exactly ONE
     * `choiceUsingWeightPair($candidateCities)` (`:3208`, weight 10 for a foundable-adjacent level-5/6 unoccupied
     * neighbor or weight 1 for a toward-target hop; the `array_keys(...->path)` neighbor order is a parity target).
     *
     * @param candidateCities the `(cityId, weight)` pairs in `path` neighbor insertion order.
     * @return the picked next-hop city id, or null when [candidateCities] is empty.
     */
    fun pickWanderNextHop(candidateCities: List<Pair<Int, Double>>, rng: RandUtil): Int? {
        if (candidateCities.isEmpty()) {
            return null // PHP :3203 `if (!$candidateCities) return null;` — the empty guard, BEFORE any draw (m3).
        }
        return rng.choiceUsingWeightPair(candidateCities) // PHP :3208 — the ONE next-hop draw (one nextFloat1).
    }

    // ====================================================================================================
    // WORLD-DRIVEN do<한글> BODIES (PHP `GeneralAI.php:2653-3215`, read in full).
    //
    // Each body reads the [GeneralAiContext] (rng / instance / world / policy / per-general scalars),
    // assembles the exact candidate set / weighted list in PHP INSERTION ORDER, calls the PURE draw
    // primitive above, GATES the emit via `ctx.candidateAllowed(actionCode, args)` (PHP
    // `$cmd->hasFullConditionMet()`) — except the gate-exempt do집합/do방랑군이동-인재탐색 — routes meta-KV via
    // `ctx.recordGeneralKv` (decision #12), and returns the `ChosenCommand` (or null → the dispatcher falls
    // through to the next priority action). READ-ONLY over GAME ENTITIES. The candidate ORDER (the cmdList
    // append order, the backup-then-supply recruitable order, the BFS visitation order) IS the parity target.
    // ====================================================================================================

    /**
     * The 8 dispatch-loop war/move `do<한글>` bodies keyed by ACTION-NAME (the bare `do<한글>` name, NOT `che_*`),
     * in PHP/policy order. The [opensamguk.logic.ai.GeneralAiFactory] registers these into the general dispatch;
     * the loop walks [opensamguk.logic.ai.AutorunGeneralPolicy.priority] first-non-null. do집합/do방랑군이동 are
     * the two PRE-LOOP branch builders ([do집합]/[do방랑군이동]) wired separately by the factory bundle.
     */
    fun bodies(ctx: GeneralAiContext): Map<String, (LastTurn?) -> ChosenCommand?> = linkedMapOf(
        "전투준비" to { _ -> do전투준비(ctx) },
        "소집해제" to { _ -> do소집해제(ctx) },
        "출병" to { _ -> do출병(ctx) },
        "NPC헌납" to { _ -> doNPC헌납(ctx) },
        "후방워프" to { _ -> do후방워프(ctx) },
        "전방워프" to { _ -> do전방워프(ctx) },
        "내정워프" to { _ -> do내정워프(ctx) },
        "귀환" to { _ -> do귀환(ctx) },
    )

    /** The do집합 PRE-LOOP branch builder (GeneralAI.chooseGeneralTurn step; npcType==5 reached at `:3759`). */
    fun do집합(ctx: GeneralAiContext): (LastTurn?) -> ChosenCommand? = { _ -> do집합Body(ctx) }

    /** The do방랑군이동 PRE-LOOP branch builder (GeneralAI.chooseGeneralTurn wandering branch, `:3814`). */
    fun do방랑군이동(ctx: GeneralAiContext): (LastTurn?) -> ChosenCommand? = { _ -> do방랑군이동Body(ctx) }

    private fun has(ctx: GeneralAiContext, flag: Int): Boolean = (ctx.instance.genType and flag) != 0

    private fun isPeaceOrDeclared(dipState: Int): Boolean =
        dipState == AiInstanceState.D_PEACE || dipState == AiInstanceState.D_DECLARED // d평화/d선포.

    // --- do전투준비 (PHP `:2653-2682`) ---

    private fun do전투준비(ctx: GeneralAiContext): ChosenCommand? {
        val rng = ctx.rng
        // PHP `:2655` — d평화/d선포 → null.
        if (isPeaceOrDeclared(ctx.instance.dipState)) return null

        val cmdList = ArrayList<Pair<String, Double>>() // PHP `:2658`.
        val proper = ctx.nationPolicy.properWarTrainAtmos

        // PHP `:2664-2669` — che_훈련 candidate, weight maxTrainByCommand / valueFit(train,1).
        if (ctx.selfTrain < proper) {
            if (ctx.candidateAllowed(TRAIN_ACTION, emptyMap())) {
                cmdList.add(TRAIN_ACTION to GameConst.maxTrainByCommand / valueFit(ctx.selfTrain, 1.0)) // :2667
            }
        }
        // PHP `:2671-2676` — che_사기진작 candidate, weight maxAtmosByCommand / valueFit(atmos,1).
        if (ctx.selfAtmos < proper) {
            if (ctx.candidateAllowed(MORALE_ACTION, emptyMap())) {
                cmdList.add(MORALE_ACTION to GameConst.maxAtmosByCommand / valueFit(ctx.selfAtmos, 1.0)) // :2674
            }
        }

        // PHP `:2678-2681` — empty guard (no draw) then the ONE terminal choiceUsingWeightPair.
        val picked = pickBattlePrepCommand(cmdList, rng) ?: return null
        return ChosenCommand(picked, emptyMap())
    }

    // --- do소집해제 (PHP `:2684-2703`) ---

    private fun do소집해제(ctx: GeneralAiContext): ChosenCommand? {
        val rng = ctx.rng
        // PHP `:2686-2693` — the 3 non-RNG early-returns BEFORE the draw.
        if (ctx.instance.attackable) return null // :2686
        if (ctx.instance.dipState != AiInstanceState.D_PEACE) return null // :2689
        if (ctx.selfCrew == 0) return null // :2692

        // PHP `:2695` — nextBool(0.75) ALWAYS (no `&&` guard).
        if (disbandSkip(rng)) return null

        if (!ctx.candidateAllowed(DISBAND_ACTION, emptyMap())) return null // :2699 hasFullConditionMet.
        return ChosenCommand(DISBAND_ACTION, emptyMap()) // :2702.
    }

    // --- do출병 (PHP `:2706-2775`) — the m1 statement-order trap ---

    private fun do출병(ctx: GeneralAiContext): ChosenCommand? {
        val rng = ctx.rng
        // PHP `:2708-2714` — attackable / d전쟁 gates.
        if (!ctx.instance.attackable) return null // :2708
        if (ctx.instance.dipState != AiInstanceState.D_WAR) return null // :2712

        val city = ctx.selfCity ?: return null // PHP reads $this->city; null (재야/lost) guarded.

        // PHP `:2720` — the `&&` 0.7 draw BEFORE the four non-RNG early-returns (m1).
        if (sortieSkip(
                riceBelowBaserice = ctx.instance.nation.rice < GameConst.baserice,
                npcAtLeast2 = ctx.selfNpcType >= 2,
                rng = rng,
            )
        ) {
            return null // :2721
        }

        // PHP `:2729-2741` — the four non-RNG early-returns (AFTER the :2720 draw).
        val trainFloor = minOf(100.0, ctx.nationPolicy.properWarTrainAtmos.toDouble())
        if (ctx.selfTrain < trainFloor) return null // :2729
        val atmosFloor = minOf(100.0, ctx.nationPolicy.properWarTrainAtmos.toDouble())
        if (ctx.selfAtmos < atmosFloor) return null // :2732
        val crewFloor = minOf((ctx.fullLeadership - 2) * 100, ctx.nationPolicy.minWarCrew.toDouble())
        if (ctx.selfCrew < crewFloor) return null // :2735
        if (city.frontState == 0) return null // :2739
        if (city.frontState == 1) return null // :2743

        // PHP `:2747-2756` — the attackable-nation list (state==1 skipped; [0] sentinel when empty), PHP order.
        val attackableNations = ArrayList<Int>()
        for ((targetNationId, state) in (ctx.instance.warTargetNation ?: emptyMap())) {
            if (state == 1) continue // :2749-2751
            attackableNations.add(targetNationId) // :2752
        }
        if (attackableNations.isEmpty()) attackableNations.add(0) // :2754-2756

        // PHP `:2757` — array_keys(CityConst::byID(cityID)->path) (name-order), then the DB query (:2759-2763).
        val nearCities = ctx.cityConst.byId(city.id)?.path?.keys?.toList() ?: emptyList()
        val attackableCities = ctx.attackableCitiesOf(nearCities, attackableNations)
        // PHP `:2765` — count==0 throws ('출병 불가'); the adapter guards, so an empty list here = no candidate.
        if (attackableCities.isEmpty()) return null

        // PHP `:2769` — choice($attackableCities), one draw, then the gate.
        val destCityId = pickSortieTarget(attackableCities, rng)
        val args = linkedMapOf<String, Any?>("destCityID" to destCityId)
        if (!ctx.candidateAllowed(SORTIE_ACTION, args)) return null // :2770
        return ChosenCommand(SORTIE_ACTION, args) // :2774.
    }

    // --- doNPC헌납 (PHP `:2785-2863`) — per-resource rice-then-gold gate + terminal weighted pick ---

    private fun doNPC헌납(ctx: GeneralAiContext): ChosenCommand? {
        val rng = ctx.rng
        val policy = ctx.nationPolicy
        val isTongsol = has(ctx, AiInstanceState.T_TONGSOLJANG)

        // PHP `:2789-2792` — resourceMap rice THEN gold (the iteration ORDER is the parity target):
        //   ['rice', reqNationRice, reqNPCWarRice, reqNPCDevelRice], ['gold', reqNationGold, reqNPCWarGold, reqNPCDevelGold].
        data class Res(
            val isGold: Boolean,
            val genRes: Int,
            val nationRes: Int,
            val reqNation: Int,
            val reqNPCWar: Int,
            val reqNPCDevel: Int,
        )
        val resourceMap = listOf(
            Res(isGold = false, genRes = ctx.selfRice, nationRes = ctx.instance.nation.rice,
                reqNation = policy.reqNationRice, reqNPCWar = policy.reqNPCWarRice, reqNPCDevel = policy.reqNPCDevelRice),
            Res(isGold = true, genRes = ctx.selfGold, nationRes = ctx.instance.nation.gold,
                reqNation = policy.reqNationGold, reqNPCWar = policy.reqNPCWarGold, reqNPCDevel = policy.reqNPCDevelGold),
        )

        val args = ArrayList<Pair<TributeArg, Double>>() // PHP `:2794`.

        for (res in resourceMap) {
            val genRes = res.genRes // PHP `:2797`.
            // PHP `:2799-2820` — reqRes selection (통솔장 → reqNPCWar; else reqNPCDevel + two early-append paths).
            val reqRes: Int
            if (isTongsol) {
                reqRes = res.reqNPCWar // :2800
            } else {
                reqRes = res.reqNPCDevel // :2802
                if (genRes >= res.reqNPCWar && res.reqNPCWar > res.reqNPCDevel + 1000) { // :2803
                    val amount = genRes - res.reqNPCDevel // :2804
                    args.add(TributeArg(res.isGold, amount) to amount.toDouble()) // :2805-2808
                    continue // :2809
                }
                if (genRes >= res.reqNPCDevel * 5 && genRes >= 5000) { // :2812
                    val amount = genRes - res.reqNPCDevel // :2813
                    args.add(TributeArg(res.isGold, amount) to amount.toDouble()) // :2814-2817
                    continue // :2818
                }
            }

            if (res.nationRes >= res.reqNation) continue // PHP `:2822-2824` — nation already has enough.
            // PHP `:2825-2837` — the rice-only emergency reserve append (minNationalRice/2). minNationalRice=0 → inert.
            if (!res.isGold && res.nationRes <= GameConst.minNationalRice / 2 && genRes >= GameConst.minNationalRice / 2) {
                if (genRes < GameConst.minNationalRice) {
                    args.add(TributeArg(false, genRes) to genRes.toDouble()) // :2827-2830
                } else {
                    args.add(TributeArg(false, genRes / 2) to (genRes / 2).toDouble()) // :2832-2835
                }
            }
            if (genRes < reqRes * 1.5) continue // PHP `:2838`.
            // PHP `:2841` — per-resource `&&` gate, used as `!nextBool(...)` (the continue when the roll fails).
            if (!tributeResourceGate(reqRes > 0, genRes.toDouble(), reqRes.toDouble(), rng)) continue // :2841-2843
            val amount = genRes - reqRes // :2844
            if (amount < policy.minimumResourceActionAmount) continue // :2845-2847
            args.add(TributeArg(res.isGold, amount) to amount.toDouble()) // :2848-2851
        }

        // PHP `:2854-2858` — empty guard (no draw) then the ONE terminal choiceUsingWeightPair.
        val picked = pickTributeArg(args, rng) ?: return null
        val emitArgs = linkedMapOf<String, Any?>("isGold" to picked.isGold, "amount" to picked.amount)
        if (!ctx.candidateAllowed(TRIBUTE_ACTION, emitArgs)) return null // :2859.
        return ChosenCommand(TRIBUTE_ACTION, emitArgs) // :2862.
    }

    // --- do후방워프 (PHP `:2866-2970`) — backup-then-supply recruitableCityList → choiceUsingWeight ---

    private fun do후방워프(ctx: GeneralAiContext): ChosenCommand? {
        val rng = ctx.rng
        val policy = ctx.nationPolicy
        val city = ctx.selfCity ?: return null

        // PHP `:2868-2871` — minRecruitPop seed.
        var minRecruitPop = ctx.fullLeadership * 100 + GameConst.minAvailableRecruitPop
        if (!ctx.generalPolicy.can한계징병) {
            minRecruitPop = maxOf(minRecruitPop, ctx.fullLeadership * 100 + policy.minNPCRecruitCityPopulation)
        }
        // PHP `:2872-2890` — the dipState/can징병/통솔장/crew gates (all non-RNG, LogText only).
        if (isPeaceOrDeclared(ctx.instance.dipState)) return null // :2872
        if (!ctx.generalPolicy.can징병) return null // :2877
        if (!has(ctx, AiInstanceState.T_TONGSOLJANG)) return null // :2882
        if (ctx.selfCrew >= policy.minWarCrew) return null // :2887

        // PHP `:2893-2904` — the self-city pop-충분 gate.
        if (ctx.generalPolicy.can한계징병) {
            if (city.population >= minRecruitPop) return null // :2894
        } else {
            if (city.population.toDouble() / city.populationMax >= policy.safeRecruitCityPopulationRatio) { // :2898
                if (city.population >= policy.minNPCRecruitCityPopulation && city.population >= minRecruitPop) {
                    return null // :2899-2902
                }
            }
        }

        // PHP `:2906-2949` — recruitableCityList: backupCities FIRST, then (only if empty) supplyCities.
        val recruitableCityList = LinkedHashMap<Int, Double>()
        for (candidate in ctx.world.backupCities.values) { // :2910
            val popRatio = candidate.city.population.toDouble() / candidate.city.populationMax
            val cityID = candidate.cityId
            if (cityID == city.id) continue // :2913
            if (popRatio < policy.safeRecruitCityPopulationRatio) continue // :2916
            if (candidate.city.population < policy.minNPCRecruitCityPopulation) continue // :2919
            if (candidate.city.population < minRecruitPop) continue // :2922
            recruitableCityList[cityID] = popRatio // :2925
        }
        if (recruitableCityList.isEmpty()) { // :2928
            for (candidate in ctx.world.supplyCities.values) { // :2929
                val popRatio = candidate.city.population.toDouble() / candidate.city.populationMax
                val cityID = candidate.cityId
                if (cityID == city.id) continue // :2932
                if (candidate.city.population < policy.minNPCRecruitCityPopulation) continue // :2935
                if (candidate.city.population <= minRecruitPop) continue // :2938
                if (popRatio < policy.safeRecruitCityPopulationRatio) continue // :2941
                if (ctx.world.frontCities.containsKey(cityID)) { // :2944
                    recruitableCityList[cityID] = popRatio / 2 // :2945
                } else {
                    recruitableCityList[cityID] = popRatio // :2947
                }
            }
        }
        if (recruitableCityList.isEmpty()) return null // :2952

        // PHP `:2958-2961` — emit (optionText THEN destCityID order), one choiceUsingWeight, then gate.
        val destCityId = pickBackupWarpDest(recruitableCityList, rng) // :2960
        val args = linkedMapOf<String, Any?>("optionText" to WARP_OPTION_TEXT, "destCityID" to destCityId)
        if (!ctx.candidateAllowed(WARP_ACTION, args)) return null // :2964
        return ChosenCommand(WARP_ACTION, args) // :2969.
    }

    // --- do전방워프 (PHP `:2972-3020`) — supplied frontCities candidate → choiceUsingWeight ---

    private fun do전방워프(ctx: GeneralAiContext): ChosenCommand? {
        val rng = ctx.rng
        val city = ctx.selfCity ?: return null
        // PHP `:2974-2991` — attackable / dipState / 통솔장 / crew / self-front gates.
        if (!ctx.instance.attackable) return null // :2974
        if (isPeaceOrDeclared(ctx.instance.dipState)) return null // :2977
        if (!has(ctx, AiInstanceState.T_TONGSOLJANG)) return null // :2981
        if (ctx.selfCrew < ctx.nationPolicy.minWarCrew) return null // :2985
        if (city.frontState != 0) return null // :2989 (truthy front → null).

        // PHP `:2996-3007` — candidateCities = supplied frontCities, keyed by city → important.
        if (ctx.world.frontCities.isEmpty()) return null // :2998-3000 (MustNotBeReached; the adapter guards).
        val candidateCities = LinkedHashMap<Int, Double>()
        for (frontCity in ctx.world.frontCities.values) { // :3002
            if (frontCity.city.supplyState == 0) continue // :3003
            candidateCities[frontCity.cityId] = frontCity.important.toDouble() // :3006
        }
        if (candidateCities.isEmpty()) return null // empty candidate → choiceUsingWeight throws; guard.

        // PHP `:3009-3012` — emit (optionText THEN destCityID), one choiceUsingWeight, then gate.
        val destCityId = pickFrontWarpDest(candidateCities, rng) // :3011
        val args = linkedMapOf<String, Any?>("optionText" to WARP_OPTION_TEXT, "destCityID" to destCityId)
        if (!ctx.candidateAllowed(WARP_ACTION, args)) return null // :3015
        return ChosenCommand(WARP_ACTION, args) // :3019.
    }

    // --- do내정워프 (PHP `:3022-3092`) — nextBool(0.6) ALWAYS → nextBool(warpProp) → choiceUsingWeight ---

    private fun do내정워프(ctx: GeneralAiContext): ChosenCommand? {
        val rng = ctx.rng
        // PHP `:3024` — 통솔장 AND dipState∈{징병,직전,전쟁} → null.
        if (has(ctx, AiInstanceState.T_TONGSOLJANG) &&
            (ctx.instance.dipState == AiInstanceState.D_CONSCRIPT ||
                ctx.instance.dipState == AiInstanceState.D_IMMINENT ||
                ctx.instance.dipState == AiInstanceState.D_WAR)
        ) {
            return null
        }

        val city = ctx.selfCity ?: return null

        // PHP `:3029` — nextBool(0.6) ALWAYS.
        if (internalWarpSkip(rng)) return null

        // PHP `:3033-3043` — warpProp = product of develVals over genType-matching develTypes; count them.
        var availableTypeCnt = 0
        var warpProp = 1.0
        for ((_, develVal, develType) in ctx.cityDevelRateOf(city.id)) { // :3037
            if ((ctx.instance.genType and develType) == 0) continue // :3038
            warpProp *= develVal // :3041
            availableTypeCnt += 1 // :3042
        }
        if (availableTypeCnt == 0) return null // PHP `:3045-3048` — 무능장 → null.

        // PHP `:3050` — `!nextBool($warpProp)` proceed gate.
        if (!internalWarpProceedGate(warpProp, rng)) return null

        // PHP `:3054-3077` — candidateCities over supplyCities with realDevelRate < 0.95.
        val candidateCities = LinkedHashMap<Int, Double>()
        for (candidate in ctx.world.supplyCities.values) { // :3056
            if (candidate.cityId == city.id) continue // :3057
            var realDevelRate = 0.0001 // :3060
            for ((_, develVal, develType) in ctx.cityDevelRateOf(candidate.cityId)) { // :3062
                if ((ctx.instance.genType and develType) == 0) continue // :3063
                realDevelRate += develVal // :3066
            }
            realDevelRate /= availableTypeCnt // :3070
            if (realDevelRate >= 0.95) continue // :3072
            val gens = ctx.cityGeneralCountOf(candidate.cityId)
            candidateCities[candidate.cityId] = 1 / (realDevelRate * sqrt((gens + 1).toDouble())) // :3076
        }
        if (candidateCities.isEmpty()) return null // :3079

        // PHP `:3083-3086` — emit (optionText THEN destCityID), one choiceUsingWeight, then gate.
        val destCityId = pickInternalWarpDest(candidateCities, rng) // :3085
        val args = linkedMapOf<String, Any?>("optionText" to WARP_OPTION_TEXT, "destCityID" to destCityId)
        if (!ctx.candidateAllowed(WARP_ACTION, args)) return null // :3087
        return ChosenCommand(WARP_ACTION, args) // :3091.
    }

    // --- do귀환 (PHP `:3095-3109`) — ZERO draws ---

    private fun do귀환(ctx: GeneralAiContext): ChosenCommand? {
        val city = ctx.selfCity ?: return null
        // PHP `:3099` — in an own-nation supplied city → null (nothing to return to).
        if (city.nationId == ctx.instance.nation.nation && city.supplyState != 0) return null
        if (!ctx.candidateAllowed(RETURN_ACTION, emptyMap())) return null // :3104 hasFullConditionMet.
        return ChosenCommand(RETURN_ACTION, emptyMap()) // :3108.
    }

    // --- do집합 (PHP `:3111-3125`) — GATE-EXEMPT; npc==5 draws nextRangeInt(2,4) + killturn delta ---

    private fun do집합Body(ctx: GeneralAiContext): ChosenCommand {
        // PHP `:3115-3119` — npc==5 killturn reroll + ChangeRecorder delta (decision #12; NOT inline).
        if (ctx.selfNpcType == 5) {
            val newKillTurn = assembleKillturnReroll(ctx.selfKillturn, ctx.rng) // :3116-3117.
            ctx.recordGeneralKv(ctx.selfGeneralId, KILLTURN_KEY, newKillTurn) // :3118.
        }
        // PHP `:3121-3124` — GATE-EXEMPT: always returns che_집합 (no hasFullConditionMet).
        return ChosenCommand(ASSEMBLE_ACTION, emptyMap())
    }

    // --- do방랑군이동 (PHP `:3127-3215`) — target pick → at-target 인재탐색 / next-hop 이동 ---

    private fun do방랑군이동Body(ctx: GeneralAiContext): ChosenCommand? {
        val rng = ctx.rng
        val currCityID = ctx.selfCityId

        // PHP `:3131-3140` — dupLord<=1 AND own city level∈{5,6} → null (already foundable here).
        if (ctx.dupLordAtSelfCity <= 1) {
            if (isFoundableCityLevel(ctx.selfCityLevel)) return null // :3137.
        }

        val occupied = ctx.wanderOccupiedCities // PHP `:3146-3152` occupiedCities key set.

        // PHP `:3154-3161` — movingTargetCityID reconciliation.
        var movingTargetCityID = ctx.movingTargetCityId
        if (movingTargetCityID == currCityID) {
            movingTargetCityID = null // :3157-3158
        } else if (movingTargetCityID != null && occupied.contains(movingTargetCityID)) {
            movingTargetCityID = null // :3159-3161
        }

        // PHP `:3163-3182` — pick a fresh target ONLY when none cached (the :3180 target draw).
        if (movingTargetCityID == null) {
            val candidateCities = ArrayList<Pair<Int, Double>>() // :3165
            val distMap = AiDistance.searchDistanceCities(currCityID, 4, ctx.cityConst) // :3166 searchDistance(curr, 4).
            for ((testCityID, dist) in distMap) { // dequeue (BFS visitation) order.
                if (occupied.contains(testCityID)) continue // :3167
                val cityLevel = ctx.cityConst.byId(testCityID)?.level ?: continue
                if (!isFoundableCityLevel(cityLevel)) continue // :3171
                candidateCities.add(testCityID to 1 / 2.0.pow(dist)) // :3174 weight 1/2^dist.
            }
            val picked = pickWanderTarget(candidateCities, rng) ?: return null // :3177-3180.
            movingTargetCityID = picked
            ctx.recordGeneralKv(ctx.selfGeneralId, "movingTargetCityID", picked) // :3181 aux delta.
        }

        // PHP `:3184-3186` — at the target → che_인재탐색 (GATE-EXEMPT; emitted directly).
        if (movingTargetCityID == currCityID) {
            return ChosenCommand(WANDER_SEARCH_ACTION, emptyMap()) // :3185.
        }

        // PHP `:3188-3208` — next-hop pick toward the target.
        val distMap = AiDistance.searchDistanceCities(movingTargetCityID, 99, ctx.cityConst) // :3188.
        val targetDistance = distMap[currCityID] ?: return null // :3190.
        val candidateCities = ArrayList<Pair<Int, Double>>() // :3191.
        for (nearCityID in (ctx.cityConst.byId(currCityID)?.path?.keys ?: emptyList())) { // :3193 name-order.
            val cityLevel = ctx.cityConst.byId(nearCityID)?.level ?: continue
            if (isFoundableCityLevel(cityLevel) && !occupied.contains(nearCityID)) {
                candidateCities.add(nearCityID to 10.0) // :3197 foundable-adjacent → weight 10.
            }
            if ((distMap[nearCityID] ?: Int.MAX_VALUE) + 1 == targetDistance) {
                candidateCities.add(nearCityID to 1.0) // :3200 toward-target → weight 1.
            }
        }
        val destCityId = pickWanderNextHop(candidateCities, rng) ?: return null // :3203-3208.
        val args = linkedMapOf<String, Any?>("destCityID" to destCityId)
        if (!ctx.candidateAllowed(WANDER_MOVE_ACTION, args)) return null // :3210.
        return ChosenCommand(WANDER_MOVE_ACTION, args) // :3214.
    }
}
