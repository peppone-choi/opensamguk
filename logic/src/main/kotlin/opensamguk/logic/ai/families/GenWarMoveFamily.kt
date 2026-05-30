package opensamguk.logic.ai.families

import opensamguk.common.rng.RandUtil

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
}
