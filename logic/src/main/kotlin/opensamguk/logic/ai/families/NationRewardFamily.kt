package opensamguk.logic.ai.families

import opensamguk.common.rng.RandUtil
import opensamguk.logic.ai.AiUtils

/**
 * L-REWARD — the nation-reward `do<한글>` command family: 포상 / 긴급포상 / 몰수.
 *
 * GRAND TRUTH = PHP `legacy/devsam-core/hwe/sammo/GeneralAI.php` (read in full):
 *  - `do유저장긴급포상` (`:1224-1310`, emits `che_포상`, sets `reqUpdateInstance=true` AFTER the gate),
 *  - `do유저장포상`     (`:1312-1417`, emits `che_포상`, NO reqUpdateInstance),
 *  - `doNPC긴급포상`    (`:1419-1511`, emits `che_포상`, sets `reqUpdateInstance=true` AFTER the gate),
 *  - `doNPC포상`        (`:1513-1632`, emits `che_포상`, NO reqUpdateInstance; weight `max(warCnt,civilCnt)-idx`),
 *  - `doNPC몰수`        (`:1634-1762`, emits `che_몰수`, NO reqUpdateInstance; weight `takeAmount`).
 *
 * This file holds the PURE, draw-order-bearing primitives each reward `do<한글>` composes (it mirrors the
 * established `GenFoundFamily`/`NationDeployFamily` pure-helper shape: the candidate-set construction — the
 * resource-threshold ladder, the killturn/reqMoney gates, the `payAmount`/`takeAmount` geomean+valueFit math —
 * is the foundations'/adapter's job; the family owns the per-method WEIGHT, the SINGLE `choiceUsingWeightPair`
 * draw on the shared `"GeneralAI"` [RandUtil], the emitted RAW arg map, and the reqUpdateInstance-after-gate
 * signal). The F-BRIDGE gate (the PHP `hasFullConditionMet()` analogue, `candidateAllowed`) accepts/rejects the
 * emit — the family does NOT re-implement that gate.
 *
 * ## The load-bearing parity facts (catalog §5.C/§7, decision #2/#9)
 *  1. **ONE `choiceUsingWeightPair($candidateArgs)`** per method ([pickReward]) — the candidate list is built
 *     deterministically (nested `foreach resName` × `foreach generals`, NO draws), then exactly ONE draw picks
 *     the emit. An empty candidate list returns null with ZERO draws (PHP `if (!$candidateArgs) return null;`).
 *  2. **Weight = positional rank AFTER a STABLE `usort` — NO secondary comparator** ([stableSortAsc] /
 *     [stableSortDesc] delegate to [AiUtils.usort]; PHP 8 sorts are unconditionally stable):
 *     - `count($list) - $idx` for the 포상 single-list methods ([rankWeight], PHP `:1288/1382` …),
 *     - `max(count(warGenerals), count(civilGenerals)) - $idx` for `doNPC포상` ([npcRewardWeight], PHP `:1581/1620`),
 *     - the literal seized `$takeAmount` (an Int) for `doNPC몰수` (PHP `:1726/1759`).
 *     A reorder on a tie shifts the `idx` → shifts the weight → shifts the single draw → desyncs everything
 *     downstream. The reward weight is a plain integer rank (`count - idx`), NEVER a half-away round of an
 *     outcome — `getOutcome` half-away (H-HELPERS §3) belongs to the bill-RATE helpers (`:4216/4263`, L-RATES),
 *     NOT here; the `dedicationList` filter `npc != 5` + the dead unused-append are also L-RATES, NOT 포상/몰수.
 *  3. **The two 긴급 methods set `reqUpdateInstance=true` AFTER the gate** (decision #2 dirty trigger, PHP
 *     `:1308/:1509`); `do유저장포상`/`doNPC포상`/`doNPC몰수` do NOT ([RewardMethod.requiresInstanceUpdate]). The
 *     dispatcher calls `AiInstanceState.markDirty()` only when the method's flag is set AND the gate passed.
 *  4. **The arg map insertion order** is `destGeneralID` → `isGold` → `amount` (the array-literal key order, a
 *     canonicalization parity target). `doNPC포상`/긴급포상 use a value-ASC `usort`; `doNPC몰수` uses a
 *     value-DESC `usort` (PHP `- ($lhs <=> $rhs)`), NO secondary comparator in either direction.
 *
 * NO draws happen outside the single documented `choiceUsingWeightPair` site. PURE `:logic`.
 */
object NationRewardFamily {

    /**
     * The five reward `do<한글>` methods, each tagged with its emitted action code and whether it sets
     * `reqUpdateInstance=true` AFTER the `hasFullConditionMet()` gate (PHP `:1308/:1509` for the 긴급 pair;
     * the other three do NOT — `:1417/:1632/:1762`).
     */
    enum class RewardMethod(val actionCode: String, val requiresInstanceUpdate: Boolean) {
        /** PHP `do유저장긴급포상` (`:1224-1310`): che_포상, reqUpdateInstance AFTER gate. */
        유저장긴급포상("che_포상", requiresInstanceUpdate = true),

        /** PHP `do유저장포상` (`:1312-1417`): che_포상, NO reqUpdateInstance. */
        유저장포상("che_포상", requiresInstanceUpdate = false),

        /** PHP `doNPC긴급포상` (`:1419-1511`): che_포상, reqUpdateInstance AFTER gate. */
        NPC긴급포상("che_포상", requiresInstanceUpdate = true),

        /** PHP `doNPC포상` (`:1513-1632`): che_포상, NO reqUpdateInstance; weight `max(warCnt,civilCnt)-idx`. */
        NPC포상("che_포상", requiresInstanceUpdate = false),

        /** PHP `doNPC몰수` (`:1634-1762`): che_몰수, NO reqUpdateInstance; weight `takeAmount`. */
        NPC몰수("che_몰수", requiresInstanceUpdate = false),
    }

    /**
     * The 포상 RAW arg map (PHP e.g. `:1283-1287`/`:1389-1393`/`:1497-…`): the array literal
     * `['destGeneralID' => id, 'isGold' => $resName == 'gold', 'amount' => $payAmount]`. Insertion order
     * `destGeneralID` → `isGold` → `amount` is a canonicalization parity target.
     */
    fun rewardArg(destGeneralId: Int, isGold: Boolean, amount: Int): Map<String, Any?> =
        linkedMapOf("destGeneralID" to destGeneralId, "isGold" to isGold, "amount" to amount)

    /**
     * The 몰수 RAW arg map (PHP `doNPC몰수` `:1721-1725`/`:1755-1759`): the same key shape as [rewardArg]
     * (`destGeneralID` → `isGold` → `amount`), the `amount` carrying the seized `$takeAmount`.
     */
    fun confiscateArg(destGeneralId: Int, isGold: Boolean, amount: Int): Map<String, Any?> =
        linkedMapOf("destGeneralID" to destGeneralId, "isGold" to isGold, "amount" to amount)

    /**
     * The positional rank-weight `count($list) - $idx` (PHP 포상 `:1288/:1382`, etc.): the best candidate
     * (idx 0, first after a STABLE sort) gets the highest weight `count`; the last (idx `count-1`) gets `1`.
     * Delegates to [AiUtils.rankWeight] — biasing the single [pickReward] draw toward the front of the list.
     */
    fun rankWeight(count: Int, idx: Int): Int = AiUtils.rankWeight(count, idx)

    /**
     * `doNPC포상`'s rank-weight `max(count($npcWarGenerals), count($npcCivilGenerals)) - $idx` (PHP `:1581/:1620`):
     * the two NPC buckets share one weight scale anchored on the larger bucket's size. The `$idx` is the
     * positional index WITHIN whichever bucket's loop is appending (each bucket loops separately but both use
     * the SAME `max(...)` anchor — port verbatim).
     */
    fun npcRewardWeight(warCnt: Int, civilCnt: Int, idx: Int): Int = maxOf(warCnt, civilCnt) - idx

    /**
     * The value-ASC `usort` shared by 포상/긴급포상 (PHP `$lhs->getVar($resName) <=> $rhs->getVar($resName)`):
     * stable on ties, NO secondary comparator. Returns a NEW list (the source is never mutated; the AI snapshots
     * `$this->userWarGenerals` etc. into a local before sorting). [resOf] extracts the resource value compared.
     */
    fun <T> stableSortAsc(items: List<T>, resOf: (T) -> Int): List<T> =
        AiUtils.usort(items) { lhs, rhs -> resOf(lhs).compareTo(resOf(rhs)) }

    /**
     * The value-DESC `usort` used by `doNPC몰수` (PHP `- ($lhs->getVar($resName) <=> $rhs->getVar($resName))`):
     * stable on ties, NO secondary comparator. The negation only flips the direction; equal values keep their
     * insertion order (PHP 8 stable). Returns a NEW list; the source is never mutated.
     */
    fun <T> stableSortDesc(items: List<T>, resOf: (T) -> Int): List<T> =
        AiUtils.usort(items) { lhs, rhs -> -resOf(lhs).compareTo(resOf(rhs)) }

    /**
     * The SINGLE `choiceUsingWeightPair($candidateArgs)` draw shared by all five reward methods (PHP
     * `:1302/:1408/:1502/:1626/:1755`). The candidate list is the `[argMap, weight]` pair list built
     * deterministically by the adapter (append order = the `usort`-then-loop order, never re-sorted). Returns
     * null with ZERO draws when the list is empty (PHP `if (!$candidateArgs) return null;` precedes the draw).
     *
     * @param candidateArgs the `(argMap, weight)` pairs in PHP append order (the build order IS a parity target;
     *  `choiceUsingWeightPair` walks it left-to-right consuming exactly ONE `nextFloat1`).
     * @return the picked RAW arg map, or null when [candidateArgs] is empty.
     */
    fun pickReward(
        candidateArgs: List<Pair<Map<String, Any?>, Double>>,
        rng: RandUtil,
    ): Map<String, Any?>? {
        if (candidateArgs.isEmpty()) {
            return null // PHP `if (!$candidateArgs) return null;` — the empty-list guard, BEFORE any draw.
        }
        return rng.choiceUsingWeightPair(candidateArgs) // the ONE reward draw (one nextFloat1).
    }
}
