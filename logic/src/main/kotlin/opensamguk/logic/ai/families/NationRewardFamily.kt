package opensamguk.logic.ai.families

import opensamguk.common.rng.RandUtil
import opensamguk.logic.ai.AiGeneralView
import opensamguk.logic.ai.AiUtils
import opensamguk.logic.ai.ChosenCommand
import opensamguk.logic.ai.GeneralAiContext
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.util.valueFit
import kotlin.math.sqrt

/**
 * L-REWARD — the nation-reward `do<한글>` command family: 포상 / 긴급포상 / 몰수.
 *
 * frozen historical baseline (ADR-LITE-042; not current product authority) = PHP `legacy/devsam-core/hwe/sammo/GeneralAI.php` (read in full):
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
    fun rewardArg(destGeneralId: Int, isGold: Boolean, amount: Number): Map<String, Any?> =
        linkedMapOf("destGeneralID" to destGeneralId, "isGold" to isGold, "amount" to amount)

    /**
     * The 몰수 RAW arg map (PHP `doNPC몰수` `:1721-1725`/`:1755-1759`): the same key shape as [rewardArg]
     * (`destGeneralID` → `isGold` → `amount`), the `amount` carrying the seized `$takeAmount`.
     */
    fun confiscateArg(destGeneralId: Int, isGold: Boolean, amount: Number): Map<String, Any?> =
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
     * @param candidateArgs the `(argMap, weight)` pairs in retained deterministic append order; the frozen historical
     *  PHP comparison is regression evidence only (ADR-LITE-042; not current product authority).
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

    // ====================================================================================================
    // WORLD-DRIVEN do<한글> BODIES (PHP `GeneralAI.php:1224-1762`, read in full).
    //
    // Each body reads the [GeneralAiContext] (rng / instance.nation gold+rice+maxResourceActionAmount /
    // world buckets userWar/userGenerals/npcWar/npcCivil / nationPolicy thresholds), assembles the exact
    // weighted candidate list in PHP insertion order (the `remainResource` gold-THEN-rice loop × the
    // per-general inner loop, each preceded by an in-place STABLE value-`usort` that CARRIES the prior
    // resource iteration's order into the next iteration's tie-break — PHP `usort($local,...)` mutates the
    // running local), calls the PURE [pickReward] primitive (ONE `choiceUsingWeightPair`), GATES the emit
    // via `ctx.candidateAllowed(...)` (PHP `$cmd->hasFullConditionMet()`), and — for the two 긴급 methods —
    // marks the instance dirty AFTER a passed gate (PHP `$this->reqUpdateInstance = true`, `:1308/:1509`).
    // READ-ONLY over GAME ENTITIES; NO meta-KV deltas (the dirty flag is the [AiInstanceState.markDirty]
    // re-derive trigger, not a ChangeRecorder KV). The candidate ORDER + the `count - idx` / `takeAmount`
    // weight IS the draw-for-draw parity target.
    // ====================================================================================================

    /**
     * The 5 nation-reward `do<한글>` bodies keyed by ACTION-NAME (the bare name, NOT `che_*`), in
     * `AutorunNationPolicy.priority` order. The [opensamguk.logic.ai.GeneralAiFactory] registers these into
     * the nation dispatch; the loop walks the policy order. A body returns null on every early-guard /
     * empty-candidate / gate-deny.
     */
    fun bodies(ctx: GeneralAiContext): Map<String, (LastTurn?) -> ChosenCommand?> = linkedMapOf(
        "유저장긴급포상" to { lt -> do유저장긴급포상(ctx, lt) },
        "유저장포상" to { lt -> do유저장포상(ctx, lt) },
        "NPC긴급포상" to { lt -> doNPC긴급포상(ctx, lt) },
        "NPC포상" to { lt -> doNPC포상(ctx, lt) },
        "NPC몰수" to { lt -> doNPC몰수(ctx, lt) },
    )

    // --- shared read helpers (NO draws) ---

    /** PHP `$general->getVar($resName)` for `$resName ∈ {'gold','rice'}` — the resource the loop compares/pays. */
    private fun resOf(gv: AiGeneralView, isGold: Boolean): Int =
        if (isGold) gv.general.gold else gv.general.rice

    /** `che_포상` emit + the `hasFullConditionMet()` gate. */
    private fun gatedReward(
        ctx: GeneralAiContext,
        actionCode: String,
        args: Map<String, Any?>,
    ): ChosenCommand? {
        if (!ctx.candidateAllowed(actionCode, args)) return null
        return ChosenCommand(actionCode, args)
    }

    /** A resource axis in `remainResource` insertion order — gold THEN rice (the foreach key order). */
    private val RESOURCE_AXES = listOf(true, false) // true=gold first, false=rice second.

    // --- do유저장긴급포상 (PHP `:1224-1310`) — userWarGenerals; weight count-idx; reqUpdateInstance AFTER gate ---

    private fun do유저장긴급포상(ctx: GeneralAiContext, lastTurn: LastTurn?): ChosenCommand? {
        val world = ctx.world
        if (world.userWarGenerals.isEmpty()) return null // :1226-1228
        val nation = ctx.instance.nation
        val policy = ctx.nationPolicy
        val candidateArgs = ArrayList<Pair<Map<String, Any?>, Double>>()

        // :1232-1241 — remainResource: gold => [nation.gold, reqHumanWarUrgentGold]; rice => [..., ...Rice].
        var userWarGenerals = world.userWarGenerals.values.toList() // :1244 local snapshot (re-sorted in place).
        for (isGold in RESOURCE_AXES) {
            val resVal = if (isGold) nation.gold else nation.rice
            val reqHumanMinRes = if (isGold) policy.reqHumanWarUrgentGold else policy.reqHumanWarUrgentRice

            // :1247-1249 — usort ASC by getVar(resName), mutating the running local (carries prior order).
            userWarGenerals = stableSortAsc(userWarGenerals) { resOf(it, isGold) }

            for ((idx, target) in userWarGenerals.withIndex()) {
                val targetRes = resOf(target, isGold)
                if (targetRes >= reqHumanMinRes) break // :1252-1254
                if (target.killturn <= 5) continue // :1255-1257

                // :1260 — reqMoney = costWithTech(tech, toInt(leadership(false))) * 100 * 3 * 1.1.
                var reqMoney = ctx.unitCostWithTechOf(target) * 100 * 3 * 1.1
                if (ctx.env.year > ctx.env.startYear + 3) {
                    reqMoney = maxOf(reqMoney, reqHumanMinRes.toDouble()) // :1261-1263 (+3yr user floor)
                }
                val enoughMoney = reqMoney * 1.1 // :1264

                if (targetRes >= reqMoney) continue // :1266-1268
                // :1270-1271 — 국고와 충분한 금액의 기하평균; valueFit(null, enoughMoney - targetRes).
                var payAmount = sqrt((enoughMoney - targetRes) * resVal)
                payAmount = valueFit(payAmount, null, enoughMoney - targetRes)
                if (payAmount < policy.minimumResourceActionAmount) continue // :1272-1274
                if (resVal < payAmount / 2) continue // :1276-1278
                payAmount = valueFit(payAmount, 100.0, ctx.instance.maxResourceActionAmount.toDouble()) // :1280

                candidateArgs.add(
                    rewardArg(target.general.id, isGold, payAmount) to (userWarGenerals.size - idx).toDouble(),
                ) // :1282-1289 (weight count - idx)
            }
        }

        val arg = pickReward(candidateArgs, ctx.rng) ?: return null // :1293-1295 + :1302 the ONE draw.
        val cmd = gatedReward(ctx, "che_포상", arg) ?: return null // :1304-1306
        ctx.instance.markDirty() // :1308 reqUpdateInstance = true (AFTER the gate).
        return cmd
    }

    // --- do유저장포상 (PHP `:1312-1417`) — userGenerals; war/non-war enoughMoney split; NO reqUpdateInstance ---

    private fun do유저장포상(ctx: GeneralAiContext, lastTurn: LastTurn?): ChosenCommand? {
        val world = ctx.world
        if (world.userGenerals.isEmpty()) return null // :1314-1317
        val nation = ctx.instance.nation
        val policy = ctx.nationPolicy
        val candidateArgs = ArrayList<Pair<Map<String, Any?>, Double>>()

        var userGenerals = world.userGenerals.values.toList() // :1337 local snapshot.
        for (isGold in RESOURCE_AXES) {
            // :1321-1334 — remainResource: [reqNationRes, resVal=nation[res], reqHumanMinWarRes, reqHumanMinDevelRes].
            val reqNationRes = if (isGold) policy.reqNationGold else policy.reqNationRice
            val resVal = if (isGold) nation.gold else nation.rice
            val reqHumanMinWarRes = if (isGold) policy.reqHumanWarRecommandGold else policy.reqHumanWarRecommandRice
            val reqHumanMinDevelRes = if (isGold) policy.reqHumanDevelGold else policy.reqHumanDevelRice

            if (resVal < reqNationRes) continue // :1341-1343

            userGenerals = stableSortAsc(userGenerals) { resOf(it, isGold) } // :1344-1346

            for ((idx, target) in userGenerals.withIndex()) {
                val targetRes = resOf(target, isGold)
                if (targetRes >= reqHumanMinWarRes) break // :1349-1351
                if (target.killturn <= 5) continue // :1352-1354

                // :1356-1360 — isWarGeneral = key_exists(id, userWarGenerals).
                val isWarGeneral = world.userWarGenerals.containsKey(target.general.id)

                val enoughMoney: Double
                if (isWarGeneral) {
                    // :1363-1368 — war: reqMoney = costWithTech * 100 * 6 * 1.1; +3yr floor; enoughMoney *1.2.
                    var reqMoney = ctx.unitCostWithTechOf(target) * 100 * 6 * 1.1
                    if (ctx.env.year > ctx.env.startYear + 3) {
                        reqMoney = maxOf(reqMoney, reqHumanMinWarRes.toDouble())
                    }
                    enoughMoney = reqMoney * 1.2
                } else {
                    enoughMoney = reqHumanMinDevelRes * 1.2 // :1370
                }

                if (targetRes >= enoughMoney) continue // :1373-1375
                // :1377-1378 — geomean; valueFit(resVal - reqNationRes, enoughMoney - targetRes).
                var payAmount = sqrt((enoughMoney - targetRes) * resVal)
                payAmount = valueFit(payAmount, (resVal - reqNationRes).toDouble(), enoughMoney - targetRes)
                if (payAmount < policy.minimumResourceActionAmount) continue // :1380-1382
                if (resVal < payAmount / 2) continue // :1384-1386
                payAmount = valueFit(payAmount, 100.0, ctx.instance.maxResourceActionAmount.toDouble()) // :1388

                candidateArgs.add(
                    rewardArg(target.general.id, isGold, payAmount) to (userGenerals.size - idx).toDouble(),
                ) // :1390-1397 (weight count - idx)
            }
        }

        val arg = pickReward(candidateArgs, ctx.rng) ?: return null // :1401-1403 + :1410 the ONE draw.
        return gatedReward(ctx, "che_포상", arg) // :1412-1414 (NO reqUpdateInstance, :1417).
    }

    // --- doNPC긴급포상 (PHP `:1419-1511`) — npcWarGenerals; reqNPCWarRes/2 cap; reqUpdateInstance AFTER gate ---

    private fun doNPC긴급포상(ctx: GeneralAiContext, lastTurn: LastTurn?): ChosenCommand? {
        val world = ctx.world
        if (world.npcWarGenerals.isEmpty()) return null // :1421-1423
        val nation = ctx.instance.nation
        val policy = ctx.nationPolicy
        val candidateArgs = ArrayList<Pair<Map<String, Any?>, Double>>()

        var npcWarGenerals = world.npcWarGenerals.values.toList() // :1441 local snapshot.
        for (isGold in RESOURCE_AXES) {
            // :1427-1438 — remainResource: [reqNationRes, resVal=nation[res], reqNPCWarRes / 2].
            val reqNationRes = if (isGold) policy.reqNationGold else policy.reqNationRice
            val resVal = if (isGold) nation.gold else nation.rice
            // NOTE PHP `/ 2` on an int → integer-division-ish? PHP `/` is float; the comparison is float-safe.
            val reqNPCMinWarRes = (if (isGold) policy.reqNPCWarGold else policy.reqNPCWarRice) / 2.0

            if (resVal < reqNationRes) continue // :1444-1446

            npcWarGenerals = stableSortAsc(npcWarGenerals) { resOf(it, isGold) } // :1447-1449

            for ((idx, target) in npcWarGenerals.withIndex()) {
                val targetRes = resOf(target, isGold)
                if (targetRes >= reqNPCMinWarRes) break // :1452-1454
                if (target.killturn <= 5) continue // :1455-1457

                // :1460 — reqMoney = costWithTech * 100 * 1.5; +5yr floor; enoughMoney *1.2.
                var reqMoney = ctx.unitCostWithTechOf(target) * 100 * 1.5
                if (ctx.env.year > ctx.env.startYear + 5) {
                    reqMoney = maxOf(reqMoney, reqNPCMinWarRes) // :1461-1463 (+5yr NPC floor)
                }
                val enoughMoney = reqMoney * 1.2 // :1464

                if (targetRes >= reqMoney) continue // :1466-1468
                // :1470-1471 — geomean; valueFit(resVal - reqNationRes*0.9, enoughMoney - targetRes).
                var payAmount = sqrt((enoughMoney - targetRes) * resVal)
                payAmount = valueFit(payAmount, resVal - reqNationRes * 0.9, enoughMoney - targetRes)
                if (payAmount < policy.minimumResourceActionAmount) continue // :1473-1475
                if (resVal < payAmount / 2) continue // :1477-1479
                payAmount = valueFit(payAmount, 100.0, ctx.instance.maxResourceActionAmount.toDouble()) // :1481

                candidateArgs.add(
                    rewardArg(target.general.id, isGold, payAmount) to (npcWarGenerals.size - idx).toDouble(),
                ) // :1483-1490 (weight count - idx)
            }
        }

        val arg = pickReward(candidateArgs, ctx.rng) ?: return null // :1494-1496 + :1503 the ONE draw.
        val cmd = gatedReward(ctx, "che_포상", arg) ?: return null // :1505-1507
        ctx.instance.markDirty() // :1509 reqUpdateInstance = true (AFTER the gate).
        return cmd
    }

    // --- doNPC포상 (PHP `:1513-1632`) — npcWar THEN npcCivil per res; weight max(warCnt,civilCnt)-idx ---

    private fun doNPC포상(ctx: GeneralAiContext, lastTurn: LastTurn?): ChosenCommand? {
        val world = ctx.world
        if (world.npcWarGenerals.isEmpty() && world.npcCivilGenerals.isEmpty()) return null // :1515-1517
        val nation = ctx.instance.nation
        val policy = ctx.nationPolicy
        val candidateArgs = ArrayList<Pair<Map<String, Any?>, Double>>()

        var npcWarGenerals = world.npcWarGenerals.values.toList() // :1537 local snapshots (re-sorted in place).
        var npcCivilGenerals = world.npcCivilGenerals.values.toList() // :1538

        for (isGold in RESOURCE_AXES) {
            // :1521-1534 — remainResource: [reqNationRes, resVal=nation[res], reqNPCWarRes, reqNPCDevelRes].
            val reqNationRes = if (isGold) policy.reqNationGold else policy.reqNationRice
            val resVal = if (isGold) nation.gold else nation.rice
            val reqNPCMinWarRes = if (isGold) policy.reqNPCWarGold else policy.reqNPCWarRice
            val reqNPCMinDevelRes = if (isGold) policy.reqNPCDevelGold else policy.reqNPCDevelRice

            if (resVal < reqNationRes) continue // :1541-1543

            // --- npcWar loop FIRST (:1544-1582) ---
            npcWarGenerals = stableSortAsc(npcWarGenerals) { resOf(it, isGold) } // :1544-1546
            for ((idx, target) in npcWarGenerals.withIndex()) {
                val targetRes = resOf(target, isGold)
                if (targetRes >= reqNPCMinWarRes) break // :1549-1551
                if (target.killturn <= 5) continue // :1552-1554

                var reqMoney = ctx.unitCostWithTechOf(target) * 100 * 3 * 1.1 // :1557
                if (ctx.env.year > ctx.env.startYear + 5) {
                    reqMoney = maxOf(reqMoney, reqNPCMinWarRes.toDouble()) // :1558-1560 (+5yr)
                }
                val enoughMoney = reqMoney * 1.5 // :1561

                if (targetRes >= reqMoney) continue // :1563-1565
                var payAmount = sqrt((enoughMoney - targetRes) * resVal) // :1567
                payAmount = valueFit(payAmount, (resVal - reqNationRes).toDouble(), enoughMoney - targetRes) // :1568
                if (resVal < payAmount / 2) continue // :1570-1572

                candidateArgs.add(
                    // :1574-1581 — amount = valueFit(payAmount, 100, max); weight max(warCnt,civilCnt)-idx.
                    rewardArg(
                        target.general.id, isGold,
                        valueFit(payAmount, 100.0, ctx.instance.maxResourceActionAmount.toDouble()),
                    ) to npcRewardWeight(npcWarGenerals.size, npcCivilGenerals.size, idx).toDouble(),
                )
            }

            // --- npcCivil loop SECOND (:1584-1613) ---
            npcCivilGenerals = stableSortAsc(npcCivilGenerals) { resOf(it, isGold) } // :1584-1586
            for ((idx, target) in npcCivilGenerals.withIndex()) {
                val targetRes = resOf(target, isGold)
                if (targetRes >= reqNPCMinDevelRes) break // :1589-1591
                if (target.killturn <= 5) continue // :1592-1594

                val enoughMoney = reqNPCMinDevelRes * 1.5 // :1596
                var payAmount = (enoughMoney - targetRes).toDouble() // :1598
                if (payAmount < policy.minimumResourceActionAmount) continue // :1599-1601
                payAmount = valueFit(payAmount, 100.0, ctx.instance.maxResourceActionAmount.toDouble()) // :1603

                candidateArgs.add(
                    rewardArg(target.general.id, isGold, payAmount) to
                        npcRewardWeight(npcWarGenerals.size, npcCivilGenerals.size, idx).toDouble(),
                ) // :1605-1612
            }
        }

        val arg = pickReward(candidateArgs, ctx.rng) ?: return null // :1616-1618 + :1625 the ONE draw.
        return gatedReward(ctx, "che_포상", arg) // :1627-1629 (NO reqUpdateInstance, :1632).
    }

    // --- doNPC몰수 (PHP `:1634-1762`) — civil DESC then war DESC; weight = takeAmount; che_몰수 ---

    private fun doNPC몰수(ctx: GeneralAiContext, lastTurn: LastTurn?): ChosenCommand? {
        val world = ctx.world
        if (world.npcWarGenerals.isEmpty() && world.npcCivilGenerals.isEmpty()) return null // :1636-1638
        val nation = ctx.instance.nation
        val policy = ctx.nationPolicy
        val candidateArgs = ArrayList<Pair<Map<String, Any?>, Double>>()

        var npcWarGenerals = world.npcWarGenerals.values.toList() // :1658 local snapshots.
        var npcCivilGenerals = world.npcCivilGenerals.values.toList() // :1659

        for (isGold in RESOURCE_AXES) {
            // :1643-1656 — remainResource: [resVal=nation[res], reqNationResVal, reqNPCWarRes, reqNPCDevelRes].
            val resVal = if (isGold) nation.gold else nation.rice
            val reqNationResVal = if (isGold) policy.reqNationGold else policy.reqNationRice
            val reqNPCMinWarRes = if (isGold) policy.reqNPCWarGold else policy.reqNPCWarRice
            val reqNPCMinDevelRes = if (isGold) policy.reqNPCDevelGold else policy.reqNPCDevelRice

            // --- civil DESC loop FIRST (:1663-1686) ---
            npcCivilGenerals = stableSortDesc(npcCivilGenerals) { resOf(it, isGold) } // :1663-1665
            for (target in npcCivilGenerals) {
                val targetRes = resOf(target, isGold)
                if (targetRes <= reqNPCMinDevelRes * 1.5) break // :1668-1670
                var takeAmount = targetRes - reqNPCMinDevelRes * 1.2 // :1672
                takeAmount = valueFit(takeAmount, 100.0, ctx.instance.maxResourceActionAmount.toDouble()) // :1673
                if (takeAmount < policy.minimumResourceActionAmount) break // :1674-1676

                candidateArgs.add(
                    confiscateArg(target.general.id, isGold, takeAmount) to takeAmount, // :1678-1685 (weight = takeAmount)
                )
            }

            // :1690-1692 — reqResValDelta = reqNationResVal*1.5 - resVal; if < 0 continue (skip war loop).
            val reqResValDelta = reqNationResVal * 1.5 - resVal
            if (reqResValDelta < 0) continue
            // :1695-1699 — willTakeSmallAmount = resVal >= reqNationResVal.
            val willTakeSmallAmount = resVal >= reqNationResVal

            // --- war DESC loop SECOND (:1702-1743) ---
            npcWarGenerals = stableSortDesc(npcWarGenerals) { resOf(it, isGold) } // :1702-1704
            for (target in npcWarGenerals) {
                val targetRes = resOf(target, isGold)
                // :1709-1715 — break threshold differs by willTakeSmallAmount.
                if (willTakeSmallAmount) {
                    if (targetRes <= reqNPCMinWarRes * 2) break
                } else if (targetRes <= reqNPCMinWarRes) {
                    break
                }

                // :1717-1724 — takeAmount = geomean(remaining * reqResValDelta), clamped.
                val takeAmount0: Double = if (!willTakeSmallAmount) {
                    val rem = (targetRes - reqNPCMinWarRes).toDouble()
                    valueFit(sqrt(rem * reqResValDelta), 0.0, rem)
                } else {
                    val maxTakeAmount = (targetRes - reqNPCMinWarRes).toDouble()
                    val minTakeAmount = (targetRes - reqNPCMinWarRes * 2).toDouble()
                    valueFit(sqrt(minTakeAmount * reqResValDelta), 0.0, maxTakeAmount)
                }

                if (takeAmount0 < 100) break // :1726-1728
                if (takeAmount0 < policy.minimumResourceActionAmount) break // :1730-1732
                val takeAmount = valueFit(takeAmount0, 100.0, ctx.instance.maxResourceActionAmount.toDouble()) // :1734

                candidateArgs.add(
                    confiscateArg(target.general.id, isGold, takeAmount) to takeAmount, // :1735-1742 (weight = takeAmount)
                )
            }
        }

        val arg = pickReward(candidateArgs, ctx.rng) ?: return null // :1746-1748 + :1755 the ONE draw.
        return gatedReward(ctx, "che_몰수", arg) // :1757-1759 (NO reqUpdateInstance, :1762).
    }
}
