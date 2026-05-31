package opensamguk.logic.ai.families

import opensamguk.common.constants.CityConst
import opensamguk.common.constants.GameConst
import opensamguk.common.rng.RandUtil
import opensamguk.logic.ai.AiGeneralView
import opensamguk.logic.ai.AiInstanceState
import opensamguk.logic.ai.ChosenCommand
import opensamguk.logic.ai.GeneralAiContext
import opensamguk.logic.ai.NationCity
import opensamguk.logic.domain.LastTurn

/**
 * L-DEPLOY — the nation-deploy `do<한글>` command family: the 11 `do발령` methods, all emitting `che_발령`.
 *
 * GRAND TRUTH = PHP `legacy/devsam-core/hwe/sammo/GeneralAI.php` (read in full):
 *  - `do부대전방발령` (`:294-411`), `do부대후방발령` (`:413-499`), `do부대구출발령` (`:501-571`),
 *  - `do부대유저장후방발령` (`:573-685`), `do유저장후방발령` (`:687-789`), `do유저장구출발령` (`:791-848`),
 *  - `do유저장전방발령` (`:850-908`), `do유저장내정발령` (`:910-980`),
 *  - `doNPC후방발령` (`:982-1094`), `doNPC구출발령` (`:1097-1123`), `doNPC전방발령` (`:1125-1186`),
 *  - `doNPC내정발령` (`:1188-1254`).
 *
 * This file holds the PURE, draw-order-bearing primitives each `do발령` composes (it mirrors the
 * established `GenFoundFamily` pure-helper shape: the candidate-set construction — buckets / BFS / cutTurn /
 * policy CombatForce·SupportForce gates — is the foundations'/adapter's job; the family owns the per-method
 * DRAW ORDER on the shared `"GeneralAI"` [RandUtil] + the emitted RAW arg map). The F-BRIDGE
 * `candidateAllowed` gate (argTest then `evaluateConstraints(FULL)`) accepts/rejects the emitted
 * `(che_발령, RAW args)` — the family does NOT re-implement that gate.
 *
 * ## The two load-bearing parity facts (catalog §5.A/B)
 *  1. **General-pick `choice` BEFORE city-pick** — PHP evaluates the
 *     `['destGeneralID' => choice(generals), 'destCityID' => choice(cities)]` array literal LEFT-to-RIGHT,
 *     so the general pick draws FIRST, the city/weight pick SECOND ([pickGeneralThenCity]).
 *  2. **`do부대전방발령` emits the `destGenaralID` TYPO VERBATIM** (`:391`, extra `a`) — the ONLY method with
 *     the typo. Its variable BFS `choice` draws STILL happen ([troopForwardAdvance]); then the final
 *     `choice($troopCandidate)` ([troopForwardFinalPick]); THEN the F-BRIDGE argTest rejects the absent
 *     correct `destGeneralID` → it ALWAYS returns null. Port the latent bug; do NOT fix the typo
 *     (fixing it shifts every downstream draw — R-BRIDGE §4 / M9).
 *  3. **구출발령 draws = #qualifying + 1** — one `choice($cityPool)` per qualifying lostGeneral INSIDE the
 *     loop, then one final `choice($args)` ([rescueDeploy]; PHP `do유저장구출발령` `:791-848` /
 *     `doNPC구출발령` `:1097-1123`).
 *
 * ## cutTurn one-deploy-per-turn gate (do부대전방발령/do부대후방발령, H-HELPERS §2)
 * The per-troopLeader loop skips a leader whose `last발령` falls in the same turn-bucket as the chief:
 * `compYearMonth = yearMonth; if (chiefTurn < leaderTurn) compYearMonth += 1; if (compYearMonth === yearMonth) continue;`
 * — the `cutTurn`/`joinYearMonth` math is the adapter's; [oneDeployPerTurnSkip] is the verbatim boolean.
 *
 * NO draws happen outside the documented `choice`/`choiceUsingWeight` sites. PURE `:logic`.
 */
object NationDeployFamily {

    /**
     * The `destGeneralID`/`destCityID` RAW arg map the 10 CORRECT emitters build (PHP e.g. `:467-470`,
     * `:586-589`, `:864-867`, …). Insertion order = `destGeneralID` THEN `destCityID` (the array-literal
     * key order), a parity target for the canonicalization downstream.
     */
    fun deployArg(generalId: Int, cityId: Int): Map<String, Any?> =
        linkedMapOf("destGeneralID" to generalId, "destCityID" to cityId)

    /**
     * `do부대전방발령`'s LATENT-BUG arg map (PHP `:391`):
     * `['destGenaralID' => $leaderID, 'destCityID' => $targetCityID]` — the `destGenaralID` TYPO (extra `a`),
     * the ONLY method that misspells the key. The correct `destGeneralID` key is therefore ABSENT, so the
     * F-BRIDGE argTest rejects it → `do부대전방발령` ALWAYS returns null. Port VERBATIM; do NOT fix the typo.
     */
    fun troopForwardArg(leaderId: Int, destCityId: Int): Map<String, Any?> =
        linkedMapOf("destGenaralID" to leaderId, "destCityID" to destCityId)

    /**
     * The general-pick-THEN-city-pick draw order shared by the single-pick two-stage emitters
     * (`do부대후방발령`/`do부대구출발령`/`do부대유저장후방발령`/`do유저장전방발령`/`doNPC전방발령` etc.). PHP evaluates the
     * `['destGeneralID' => choice(generals), 'destCityID' => choice(cities)]` array literal LEFT-to-RIGHT:
     * the **general `choice` draws FIRST**, the **city `choice` draws SECOND**.
     *
     * @param generalCandidates the source-general candidate list (insertion = PK-ascending bucket order).
     * @param cityCandidates the dest-city candidate list (insertion = bucket order).
     * @return the `linkedMapOf("destGeneralID" to picked, "destCityID" to picked)` RAW emit.
     */
    fun pickGeneralThenCity(
        generalCandidates: List<Int>,
        cityCandidates: List<Int>,
        rng: RandUtil,
    ): Map<String, Any?> {
        val general = rng.choice(generalCandidates) // draw 1 — general FIRST (array-literal L->R)
        val city = rng.choice(cityCandidates)        // draw 2 — city SECOND
        return deployArg(general, city)
    }

    /**
     * `do부대전방발령`'s advance loop (PHP `:373-383`): walk from the source toward the front, drawing
     * `choice($nextCityCandidate)` ONLY at an ambiguous hop (`count > 1`); a single-candidate hop (`count == 1`)
     * advances with NO draw. The COUNT of draws is data-dependent on the BFS name-order candidate sets
     * ([hops]) — the adapter supplies the per-hop candidate lists in `CityConst.byID(target)->path` name order.
     *
     * @param hops the per-hop next-city candidate lists, in advance order (each non-empty; PHP `:374-377`
     *  throws `MustNotBeReachedException` on an empty hop — that throw is the adapter's responsibility).
     * @return the final target city id (the last hop's pick).
     */
    fun troopForwardAdvance(hops: List<List<Int>>, rng: RandUtil): Int {
        var target = -1
        for (candidates in hops) {
            target = if (candidates.size == 1) {
                candidates[0] // PHP `:378-380` count==1 → no draw, advance.
            } else {
                rng.choice(candidates) // PHP `:381` choice($nextCityCandidate) — only the ambiguous hop draws.
            }
        }
        return target
    }

    /**
     * `do부대전방발령`'s final pick (PHP `:391` `$this->rng->choice($troopCandidate)`): exactly ONE `choice`
     * over the accumulated troop-candidate arg maps. Each candidate already carries the `destGenaralID` TYPO
     * (built via [troopForwardArg] / the lost-route fallbacks `:337/343/349`).
     */
    fun troopForwardFinalPick(troopCandidates: List<Map<String, Any?>>, rng: RandUtil): Map<String, Any?> =
        rng.choice(troopCandidates)

    /**
     * The 구출 (rescue) deploy draw stream (PHP `do유저장구출발령` `:791-848`, `doNPC구출발령` `:1097-1123`):
     * ONE `choice($cityPool)` per QUALIFYING lostGeneral INSIDE the loop (building `$args[]`), THEN one final
     * `choice($args)`. **Total draws = #qualifying + 1.** Even discarded args consume their in-loop draw.
     *
     * @param lostGeneralIds the already-filtered qualifying lostGeneral ids, in `lostGenerals` insertion
     *  (PK-ascending / bucket) order — the adapter applies the per-method qualifying filters (npc gate,
     *  defence/crew gate, troop-escape gate, dipState/frontCities>2 front-vs-supply branch).
     * @param cityPoolFor the per-lostGeneral city-candidate pool (PHP `do유저장구출발령` picks
     *  `frontCities` when at-war with >2 fronts else `supplyCities` `:793/795`; `doNPC구출발령` always
     *  `supplyCities` `:1075`) — the branch is the adapter's, the per-loop `choice` draw is the family's.
     * @return the `linkedMapOf("destGeneralID" to lostGeneral, "destCityID" to cityPick)` RAW emit picked by
     *  the final `choice($args)`, or null when no lostGeneral qualifies (PHP `:803-805` `if(!$args) return null`).
     */
    fun rescueDeploy(
        lostGeneralIds: List<Int>,
        cityPoolFor: (lostGeneralId: Int) -> List<Int>,
        rng: RandUtil,
    ): Map<String, Any?>? {
        val args = ArrayList<Map<String, Any?>>(lostGeneralIds.size)
        for (lostGeneralId in lostGeneralIds) {
            val selCity = rng.choice(cityPoolFor(lostGeneralId)) // per-lostGeneral choice INSIDE the loop
            // PHP `:798-801` / `:1078-1081` — the CORRECT destGeneralID key (only do부대전방발령 has the typo).
            args.add(linkedMapOf("destGeneralID" to lostGeneralId, "destCityID" to selCity))
        }
        if (args.isEmpty()) {
            return null // PHP `:803-805` `if (!$args) return null;`
        }
        return rng.choice(args) // PHP `:807` / `:1085` — the one final choice($args).
    }

    /**
     * The "한턴마다 한번씩만 발령" same-turn-bucket skip (PHP `do부대전방발령` `:323-327`, `do부대후방발령` `:154-160`,
     * H-HELPERS §2 cutTurn). Given the chief's and the troopLeader's `cutTurn`-formatted timestamps and the
     * chief's `joinYearMonth`, returns true when the leader was already deployed this turn-bucket (→ skip):
     * `compYearMonth = yearMonth; if (chiefTurn < leaderTurn) compYearMonth += 1; return compYearMonth === yearMonth;`
     * The `<` is the PHP lexicographic string compare on the `Y-m-d H:i:s.u` cutTurn output (the adapter
     * supplies the cutTurn strings + the joinYearMonth int). Only consulted when `last발령` is set.
     */
    fun oneDeployPerTurnSkip(chiefCutTurn: String, leaderCutTurn: String, yearMonth: Int): Boolean {
        var compYearMonth = yearMonth
        if (chiefCutTurn < leaderCutTurn) {
            compYearMonth += 1
        }
        return compYearMonth == yearMonth
    }

    // ====================================================================================================
    // WORLD-DRIVEN do<한글> BODIES (PHP `GeneralAI.php:294-1254`, read in full).
    //
    // Each body reads the [GeneralAiContext] (rng / instance / world buckets / policy / env), assembles the
    // exact candidate set / weighted city list in PHP insertion order, calls the PURE draw primitive above,
    // GATES the emit via `ctx.candidateAllowed("che_발령", args)` (PHP `$cmd->hasFullConditionMet()`), and
    // returns the `ChosenCommand(che_발령, RAW args)` (or null → the dispatcher falls through to the next
    // priority action). READ-ONLY over GAME ENTITIES; NO meta-KV deltas (발령 queues none in the AI). The
    // candidate ORDER (PK-ascending buckets + name-order BFS) IS the draw-for-draw parity target.
    // ====================================================================================================

    /**
     * The 12 nation-deploy `do<한글>` bodies keyed by ACTION-NAME (the bare `do발령` name, NOT `che_*`), in
     * `AutorunNationPolicy.priority` order. The [GeneralAiFactory] registers these into the nation dispatch;
     * the loop walks the policy order. A body returns null on every early-guard / empty-candidate / gate-deny.
     */
    fun bodies(ctx: GeneralAiContext): Map<String, (LastTurn?) -> ChosenCommand?> = linkedMapOf(
        "부대전방발령" to { lt -> do부대전방발령(ctx, lt) },
        "부대후방발령" to { lt -> do부대후방발령(ctx, lt) },
        "부대구출발령" to { lt -> do부대구출발령(ctx, lt) },
        "부대유저장후방발령" to { lt -> do부대유저장후방발령(ctx, lt) },
        "유저장후방발령" to { lt -> do유저장후방발령(ctx, lt) },
        "유저장구출발령" to { lt -> do유저장구출발령(ctx, lt) },
        "유저장전방발령" to { lt -> do유저장전방발령(ctx, lt) },
        "유저장내정발령" to { lt -> do유저장내정발령(ctx, lt) },
        "NPC후방발령" to { lt -> doNPC후방발령(ctx, lt) },
        "NPC구출발령" to { lt -> doNPC구출발령(ctx, lt) },
        "NPC전방발령" to { lt -> doNPC전방발령(ctx, lt) },
        "NPC내정발령" to { lt -> doNPC내정발령(ctx, lt) },
    )

    // --- shared read helpers (NO draws) ---

    /** `$city['pop'] / $city['pop_max']` (PHP — population ratio, Double float division). */
    private fun popRatio(c: NationCity): Double =
        c.city.population.toDouble() / c.city.populationMax

    /** `che_발령` emit + the `hasFullConditionMet()` gate (PHP `$cmd->hasFullConditionMet()`). */
    private fun gatedDeploy(ctx: GeneralAiContext, args: Map<String, Any?>): ChosenCommand? {
        if (!ctx.candidateAllowed("che_발령", args)) return null
        return ChosenCommand("che_발령", args)
    }

    // --- do부대전방발령 (PHP `:294-397`) — emits the destGenaralID TYPO → argTest reject → null ---

    private fun do부대전방발령(ctx: GeneralAiContext, lastTurn: LastTurn?): ChosenCommand? {
        val rng = ctx.rng
        val world = ctx.world
        if (ctx.instance.nation.capital == 0) return null // :296
        if (world.frontCities.isEmpty()) return null // :299
        // :302 calcWarRoute() — the AiWorldView lazy-once warRoute (the BFS distance maps).
        val warRoute = world.warRoute
        val troopCandidate = ArrayList<Map<String, Any?>>() // :303

        val yearMonth = NationCommandJoinYearMonth(ctx.env.year, ctx.env.month) // :306
        val frontCityVals = world.frontCities.values.toList() // choice($frontCities) value order

        for (troopLeader in world.troopLeaders.values) {
            val leaderID = troopLeader.general.id // :309
            if (!ctx.nationPolicy.combatForce.containsKey(leaderID)) continue // :310-312
            val currentCityID = troopLeader.general.cityId // :314
            if (world.frontCities.containsKey(currentCityID)) continue // :316-318

            // :320-331 — one-deploy-per-turn skip (only when last발령 is set).
            val last발령 = ctx.last발령Of(troopLeader)
            if (last발령 != null && last발령 != 0) {
                val leaderTurn = ctx.turnTimeOf(troopLeader) // :322 cutTurn
                if (oneDeployPerTurnSkip(ctx.chiefTurnTime, leaderTurn, yearMonth)) continue // :323-330
            }

            // :333 [$fromCityID, $toCityID] = CombatForce[$leaderID].
            val (fromCity0, toCity0) = combatForcePair(ctx.nationPolicy.combatForce[leaderID]) ?: continue
            var fromCityID = fromCity0
            var toCityID = toCity0

            // :335-339 — both endpoints off the war route → front anywhere (choice($frontCities)).
            if (!warRoute.containsKey(fromCityID) && !warRoute.containsKey(toCityID)) {
                troopCandidate.add(troopForwardArg(leaderID, rng.choice(frontCityVals).cityId))
                continue
            }
            // :341-345 — to-city unreachable from from-city → front anywhere.
            if (warRoute[fromCityID]?.containsKey(toCityID) != true) {
                troopCandidate.add(troopForwardArg(leaderID, rng.choice(frontCityVals).cityId))
                continue
            }
            // :347-351 — both endpoints owned (점령 완료) → front anywhere.
            if (world.supplyCities.containsKey(fromCityID) && world.supplyCities.containsKey(toCityID)) {
                troopCandidate.add(troopForwardArg(leaderID, rng.choice(frontCityVals).cityId))
                continue
            }
            // :354-358 — from-city not ours → 수도→출발지 (swap).
            if (!world.supplyCities.containsKey(fromCityID)) {
                toCityID = fromCityID
                fromCityID = ctx.instance.nation.capital
            }

            // :360-382 — advance toward the front, drawing only at an ambiguous hop.
            var targetCityID = fromCityID
            while (!world.frontCities.containsKey(targetCityID)) {
                val distance = warRoute[targetCityID]?.get(toCityID) ?: break
                val nextCityCandidate = ArrayList<Int>()
                val path = CityConst.byId(targetCityID)?.path?.keys ?: emptySet() // :365 name-order
                for (nearCityID in path) {
                    val nearRoute = warRoute[nearCityID] ?: continue // :366-368
                    val nearDist = nearRoute[toCityID] ?: continue
                    if (nearDist + 1 > distance) continue // :369-371
                    nextCityCandidate.add(nearCityID) // :372
                }
                if (nextCityCandidate.isEmpty()) {
                    // :374-376 MustNotBeReachedException — a route-calc bug; the adapter's responsibility.
                    return null
                }
                targetCityID = if (nextCityCandidate.size == 1) {
                    nextCityCandidate[0] // :377-380 count==1 → no draw, advance.
                } else {
                    rng.choice(nextCityCandidate) // :381 the ambiguous hop draw.
                }
            }
            troopCandidate.add(troopForwardArg(leaderID, targetCityID)) // :384 (the destGenaralID TYPO).
        }

        if (troopCandidate.isEmpty()) return null // :387-389
        // :391 final choice($troopCandidate) — the draw STILL happens; then the argTest rejects the typo → null.
        return gatedDeploy(ctx, rng.choice(troopCandidate))
    }

    // --- do부대후방발령 (PHP `:399-486`) — troop-then-city, both choice() ---

    private fun do부대후방발령(ctx: GeneralAiContext, lastTurn: LastTurn?): ChosenCommand? {
        val rng = ctx.rng
        val world = ctx.world
        if (ctx.instance.nation.capital == 0) return null // :401
        if (world.frontCities.isEmpty()) return null // :404

        val yearMonth = NationCommandJoinYearMonth(ctx.env.year, ctx.env.month) // :409
        val ratio = ctx.nationPolicy.safeRecruitCityPopulationRatio

        val troopCandidate = LinkedHashMap<Int, AiGeneralView>() // :411 (keyed by leaderID, insertion order)
        for (troopLeader in world.troopLeaders.values) {
            val leaderID = troopLeader.general.id // :413
            if (!supportForceContains(ctx.nationPolicy.supportForce, leaderID)) continue // :414-416
            val currentCityID = troopLeader.general.cityId // :417
            val supplyCity = world.supplyCities[currentCityID]
            if (supplyCity == null) {
                troopCandidate[leaderID] = troopLeader // :418-421 not in a supply city → candidate.
                continue
            }
            // :423-427 — a city with enough population keeps the troop in place.
            if (popRatio(supplyCity) >= ratio) continue

            // :429-440 — one-deploy-per-turn skip.
            val last발령 = ctx.last발령Of(troopLeader)
            if (last발령 != null && last발령 != 0) {
                val leaderTurn = ctx.turnTimeOf(troopLeader) // :431
                if (oneDeployPerTurnSkip(ctx.chiefTurnTime, leaderTurn, yearMonth)) continue // :432-439
            }
            troopCandidate[leaderID] = troopLeader // :443
        }
        if (troopCandidate.isEmpty()) return null // :446-448
        if (world.supplyCities.size == 1) return null // :450-452

        val cityCandidates = backupOrSupplyHighPop(ctx) ?: return null // :454-474

        // :476-479 — array literal L->R: troop choice FIRST then city choice SECOND.
        val destGeneral = rng.choice(troopCandidate.values.toList()).general.id
        val destCity = rng.choice(cityCandidates).cityId
        return gatedDeploy(ctx, deployArg(destGeneral, destCity))
    }

    // --- do부대구출발령 (PHP `:488-539`) — troop-then-city, both choice() ---

    private fun do부대구출발령(ctx: GeneralAiContext, lastTurn: LastTurn?): ChosenCommand? {
        val rng = ctx.rng
        val world = ctx.world
        if (ctx.instance.nation.capital == 0) return null // :490
        if (world.frontCities.isEmpty()) return null // :493

        val troopCandidate = LinkedHashMap<Int, AiGeneralView>() // :497
        for (troopLeader in world.troopLeaders.values) {
            val leaderID = troopLeader.general.id // :499
            if (supportForceContains(ctx.nationPolicy.supportForce, leaderID)) continue // :500-502
            if (ctx.nationPolicy.combatForce.containsKey(leaderID)) continue // :503-505
            val currentCityID = troopLeader.general.cityId // :507
            if (world.supplyCities.containsKey(currentCityID)) continue // :508-510
            troopCandidate[leaderID] = troopLeader // :512
        }
        if (troopCandidate.isEmpty()) return null // :515-517

        val cityCandidates = world.frontCities.values.toList() // :519-523
        if (cityCandidates.isEmpty()) return null // :525-527

        // :529-532 — troop choice FIRST then city choice SECOND.
        val destGeneral = rng.choice(troopCandidate.values.toList()).general.id
        val destCity = rng.choice(cityCandidates).cityId
        return gatedDeploy(ctx, deployArg(destGeneral, destCity))
    }

    // --- do부대유저장후방발령 (PHP `:542-651`) — general-then-city ---

    private fun do부대유저장후방발령(ctx: GeneralAiContext, lastTurn: LastTurn?): ChosenCommand? {
        val rng = ctx.rng
        val world = ctx.world
        if (world.frontCities.isEmpty()) return null // :544
        if (ctx.instance.dipState != AiInstanceState.D_WAR) return null // :547
        val ratio = ctx.nationPolicy.safeRecruitCityPopulationRatio

        val generalCandidates = LinkedHashMap<Int, AiGeneralView>() // :551
        for (userGeneral in world.userWarGenerals.values) {
            val generalID = userGeneral.general.id // :554
            if (generalID == ctx.selfGeneralId) continue // :555-557
            val cityId = userGeneral.general.cityId
            if (!world.frontCities.containsKey(cityId)) continue // :558-560
            val city = world.nationCities[cityId] ?: continue // :561-564
            val troopLeaderID = userGeneral.general.troop // :565
            if (troopLeaderID == 0 || !world.troopLeaders.containsKey(troopLeaderID)) continue // :566-568
            if (troopLeaderID == generalID) continue // :569-571
            val troopLeader = world.nationGenerals.firstOrNull { it.general.id == troopLeaderID } ?: continue // :572
            if (troopLeader.general.cityId != cityId) continue // :573-575
            if (!world.supplyCities.containsKey(troopLeader.general.cityId)) continue // :576-578
            if (popRatio(city) >= ratio) continue // :579-581
            if (userGeneral.general.crew >= ctx.nationPolicy.minWarCrew) continue // :582-584
            if (ctx.recruitPopScoreOf(userGeneral) <= 1) continue // :585-587

            // :589-594 — only when the general's turn is BEFORE the troop leader's.
            if (ctx.turnTimeOf(userGeneral) < ctx.turnTimeOf(troopLeader)) {
                generalCandidates[generalID] = userGeneral
            }
        }
        if (generalCandidates.isEmpty()) return null // :597-599

        // :601-609 — keep only generals whose reserved turn 0 is che_징병.
        val recruiting = LinkedHashMap<Int, AiGeneralView>()
        for ((gid, gv) in generalCandidates) {
            if (ctx.reservedIsRecruitOf(gv)) recruiting[gid] = gv
        }
        if (recruiting.isEmpty()) return null // :611-613
        if (world.supplyCities.size == 1) return null // :615-617

        val cityCandidates = backupOrSupplyHighPop(ctx) ?: return null // :619-639

        // :641-644 — general choice FIRST then city choice SECOND.
        val destGeneral = rng.choice(recruiting.values.toList()).general.id
        val destCity = rng.choice(cityCandidates).cityId
        return gatedDeploy(ctx, deployArg(destGeneral, destCity))
    }

    // --- do유저장후방발령 (PHP `:652-756`) — general FIRST (→ minRecruitPop), choiceUsingWeight city SECOND ---

    private fun do유저장후방발령(ctx: GeneralAiContext, lastTurn: LastTurn?): ChosenCommand? {
        val rng = ctx.rng
        val world = ctx.world
        if (ctx.instance.nation.capital == 0) return null // :654
        if (ctx.instance.dipState != AiInstanceState.D_WAR) return null // :657
        val ratio = ctx.nationPolicy.safeRecruitCityPopulationRatio

        val generalCandidates = LinkedHashMap<Int, AiGeneralView>() // :661
        for (userGeneral in world.userWarGenerals.values) {
            val generalID = userGeneral.general.id // :664
            if (generalID == ctx.selfGeneralId) continue // :665-667
            val cityId = userGeneral.general.cityId
            val city = world.supplyCities[cityId] ?: continue // :668-671
            if (userGeneral.general.troop != 0) continue // :672-674
            if (popRatio(city) >= ratio) continue // :675-677
            if (userGeneral.general.crew >= ctx.nationPolicy.minWarCrew) continue // :678-680
            if (ctx.recruitPopScoreOf(userGeneral) <= 1) continue // :681-683
            generalCandidates[generalID] = userGeneral // :684
        }
        if (generalCandidates.isEmpty()) return null // :687-689
        if (world.supplyCities.size == 1) return null // :691-693

        // :695-696 — general pick FIRST (it sets minRecruitPop).
        val pickedGeneral = rng.choice(generalCandidates.values.toList())
        val pickedLeadership = ctx.leadershipNoInjuryOf(pickedGeneral) // :697 getLeadership(false,…)
        val minRecruitPop = pickedLeadership * 100 + GameConst.minAvailableRecruitPop // :698

        // :700-740 — recruitable city weight list (backup first; fallback to supply, in/out of route).
        val recruitableCityList = ArrayList<Pair<Int, Double>>()
        for (candidateCity in world.backupCities.values) { // :702
            val cityID = candidateCity.cityId
            if (cityID == ctx.selfCityId) continue // :705-707
            if (candidateCity.city.population < minRecruitPop) continue // :708-710
            var popR = popRatio(candidateCity) // :703
            if (popR < ratio) popR /= 4 // :712-714
            recruitableCityList.add(cityID to popR) // :716
        }
        if (recruitableCityList.isEmpty()) { // :719
            for (candidateCity in world.supplyCities.values) { // :720
                val cityID = candidateCity.cityId
                if (cityID == ctx.selfCityId) continue // :723-725
                if (candidateCity.city.population <= minRecruitPop) continue // :726-728
                var popR = popRatio(candidateCity) // :721
                if (popR < ratio) popR /= 2 // :730-732
                // :734-736 — `$pop_ratio / 2;` is a DEAD STATEMENT in PHP (result discarded). Port verbatim: NO-OP.
                recruitableCityList.add(cityID to popR) // :738
            }
        }
        if (recruitableCityList.isEmpty()) return null // :742-744

        // :746-749 — choiceUsingWeight($recruitableCityList) (int-keyed → weighted-pair, insertion order).
        val destCity = rng.choiceUsingWeightPair(recruitableCityList)
        return gatedDeploy(ctx, deployArg(pickedGeneral.general.id, destCity))
    }

    // --- do유저장구출발령 (PHP `:758-815`) — per-lostGeneral choice + final choice ---

    private fun do유저장구출발령(ctx: GeneralAiContext, lastTurn: LastTurn?): ChosenCommand? {
        val rng = ctx.rng
        val world = ctx.world
        if (ctx.instance.nation.capital == 0) return null // :760

        // The per-loop choice pool (front when at d직전/d전쟁 with >2 fronts, else supply) — PHP `:792-796`.
        val atWarManyFronts = (ctx.instance.dipState == AiInstanceState.D_IMMINENT ||
            ctx.instance.dipState == AiInstanceState.D_WAR) && world.frontCities.size > 2
        val cityPool: List<NationCity> =
            if (atWarManyFronts) world.frontCities.values.toList() else world.supplyCities.values.toList()

        // The already-filtered qualifying lostGenerals (PHP `:766-790` qualify ladder).
        val qualifying = ArrayList<Int>()
        for (lostGeneral in world.lostGenerals.values) {
            if (lostGeneral.general.npcType >= 2) continue // :767-769
            // :770-777 — a defensible (trained + high-morale + crew) general is left in place.
            if (lostGeneral.general.crew >= ctx.nationPolicy.minWarCrew &&
                lostGeneral.general.train >= defenceTrain(lostGeneral) &&
                lostGeneral.general.atmos >= defenceTrain(lostGeneral)
            ) continue
            // :779-790 — already aboard an escapable troop.
            val troopID = lostGeneral.general.troop
            if (troopID != 0 && world.troopLeaders.containsKey(troopID)) {
                val troopLeader = world.troopLeaders.getValue(troopID)
                if (world.supplyCities.containsKey(troopLeader.general.cityId) &&
                    ctx.turnTimeOf(troopLeader) < ctx.turnTimeOf(lostGeneral)
                ) continue
            }
            qualifying.add(lostGeneral.general.id)
        }

        // :792-807 — one choice($cityPool) per qualifying lostGeneral INSIDE the loop THEN one final choice($args).
        val arg = rescueDeploy(qualifying, cityPoolFor = { cityPool.map { it.cityId } }, rng = rng) ?: return null
        // :810-813 — on gate deny PHP logs '구출실패' then returns null; the AI emits nothing on deny.
        return gatedDeploy(ctx, arg)
    }

    // --- do유저장전방발령 (PHP `:817-875`) — general choice + choiceUsingWeight(front 'important') ---

    private fun do유저장전방발령(ctx: GeneralAiContext, lastTurn: LastTurn?): ChosenCommand? {
        val rng = ctx.rng
        val world = ctx.world
        if (ctx.instance.nation.capital == 0) return null // :819
        if (world.frontCities.isEmpty()) return null // :822
        val dip = ctx.instance.dipState
        if (dip == AiInstanceState.D_PEACE || dip == AiInstanceState.D_DECLARED) return null // :825-827

        val generalCandidates = LinkedHashMap<Int, AiGeneralView>() // :829
        for (userGeneral in world.userWarGenerals.values) {
            val generalID = userGeneral.general.id // :831
            val cityID = userGeneral.general.cityId // :832
            if (!world.nationCities.containsKey(cityID)) continue // :833-835
            if (world.frontCities.containsKey(cityID)) continue // :836-838
            if (userGeneral.general.crew < ctx.nationPolicy.minWarCrew) continue // :839-841
            if (userGeneral.general.troop != 0) continue // :842-844
            val train = userGeneral.general.train // :846
            val atmos = userGeneral.general.atmos // :847
            if (maxOf(train, atmos) < ctx.nationPolicy.properWarTrainAtmos) continue // :849-851
            generalCandidates[generalID] = userGeneral // :853
        }
        if (generalCandidates.isEmpty()) return null // :856-858

        // :860-863 — choiceUsingWeight($cityCandidates) weight = front 'important' (insertion order).
        val cityCandidates = world.frontCities.values.map { it.cityId to it.important.toDouble() }
        // :865-868 — general choice FIRST then choiceUsingWeight SECOND.
        val destGeneral = rng.choice(generalCandidates.values.toList()).general.id
        val destCity = rng.choiceUsingWeightPair(cityCandidates)
        return gatedDeploy(ctx, deployArg(destGeneral, destCity))
    }

    // --- do유저장내정발령 (PHP `:877-947`) — source-general choice + choiceUsingWeight(dev score) ---

    private fun do유저장내정발령(ctx: GeneralAiContext, lastTurn: LastTurn?): ChosenCommand? =
        domesticDeploy(ctx, useWarGeneralsMerge = true)

    // --- doNPC후방발령 (PHP `:949-1061`) — npc general FIRST, choiceUsingWeight city SECOND ---

    private fun doNPC후방발령(ctx: GeneralAiContext, lastTurn: LastTurn?): ChosenCommand? {
        val rng = ctx.rng
        val world = ctx.world
        if (ctx.instance.nation.capital == 0) return null // :951
        if (world.frontCities.isEmpty()) return null // :954
        if (ctx.instance.dipState != AiInstanceState.D_WAR) return null // :957
        val ratio = ctx.nationPolicy.safeRecruitCityPopulationRatio

        val generalCandidates = LinkedHashMap<Int, AiGeneralView>() // :961
        for (npcGeneral in world.npcWarGenerals.values) {
            val generalID = npcGeneral.general.id // :964
            if (generalID == ctx.selfGeneralId) continue // :965-967
            val cityId = npcGeneral.general.cityId
            val city = world.supplyCities[cityId] ?: continue // :968-971
            if (npcGeneral.general.troop != 0) continue // :972-974
            if (popRatio(city) >= ratio) continue // :975-977
            if (npcGeneral.general.crew >= ctx.nationPolicy.minWarCrew) continue // :978-980
            if (ctx.recruitPopScoreOf(npcGeneral) <= 1) continue // :981-983
            generalCandidates[generalID] = npcGeneral // :984
        }
        if (generalCandidates.isEmpty()) return null // :987-989
        if (world.supplyCities.size == 1) return null // :991-993

        // :995-999 — general pick FIRST; minRecruitPop = max(base, npc floor).
        val pickedGeneral = rng.choice(generalCandidates.values.toList())
        val pickedLeadership = ctx.leadershipNoInjuryOf(pickedGeneral) // :997
        val minNpcPop = ctx.nationPolicy.minNPCRecruitCityPopulation
        val minRecruitPop = maxOf(
            pickedLeadership * 100 + GameConst.minAvailableRecruitPop, // :998
            pickedLeadership * 100 + minNpcPop, // :999
        )

        // :1001-1045 — recruitable city weight list (backup first; fallback supply).
        val recruitableCityList = ArrayList<Pair<Int, Double>>()
        for (candidateCity in world.backupCities.values) { // :1003
            val cityID = candidateCity.cityId
            if (cityID == ctx.selfCityId) continue // :1006-1008
            if (candidateCity.city.population < minNpcPop) continue // :1009-1011
            if (candidateCity.city.population < minRecruitPop) continue // :1012-1014
            val popR = popRatio(candidateCity) // :1004
            if (popR < ratio) continue // :1015-1017
            recruitableCityList.add(cityID to popR) // :1019
        }
        if (recruitableCityList.isEmpty()) { // :1022
            for (candidateCity in world.supplyCities.values) { // :1023
                val cityID = candidateCity.cityId
                if (cityID == ctx.selfCityId) continue // :1026-1028
                if (candidateCity.city.population < minNpcPop) continue // :1029-1031
                if (candidateCity.city.population <= minRecruitPop) continue // :1032-1034
                val popR = popRatio(candidateCity) // :1024
                if (popR < ratio) continue // :1035-1037
                // :1039-1041 — `$pop_ratio / 2;` DEAD STATEMENT (result discarded). NO-OP.
                recruitableCityList.add(cityID to popR) // :1043
            }
        }
        if (recruitableCityList.isEmpty()) return null // :1047-1049

        // :1051-1054 — choiceUsingWeight($recruitableCityList).
        val destCity = rng.choiceUsingWeightPair(recruitableCityList)
        return gatedDeploy(ctx, deployArg(pickedGeneral.general.id, destCity))
    }

    // --- doNPC구출발령 (PHP `:1064-1090`) — per-lostGeneral choice($supplyCities) + final choice ---

    private fun doNPC구출발령(ctx: GeneralAiContext, lastTurn: LastTurn?): ChosenCommand? {
        val rng = ctx.rng
        val world = ctx.world
        if (ctx.instance.nation.capital == 0) return null // :1066

        val supplyVals = world.supplyCities.values.map { it.cityId } // choice($supplyCities) value order
        val qualifying = ArrayList<Int>()
        for (lostGeneral in world.lostGenerals.values) { // :1071
            val npc = lostGeneral.general.npcType
            if (npc < 2 || npc == 5) continue // :1072-1074
            qualifying.add(lostGeneral.general.id) // :1077-1080
        }
        // :1075/1085 — one choice($supplyCities) per qualifying lostGeneral, then one final choice($args).
        val arg = rescueDeploy(qualifying, cityPoolFor = { supplyVals }, rng = rng) ?: return null
        return gatedDeploy(ctx, arg)
    }

    // --- doNPC전방발령 (PHP `:1092-1153`) — general choice + choiceUsingWeight(front 'important') ---

    private fun doNPC전방발령(ctx: GeneralAiContext, lastTurn: LastTurn?): ChosenCommand? {
        val rng = ctx.rng
        val world = ctx.world
        if (ctx.instance.nation.capital == 0) return null // :1097
        if (world.frontCities.isEmpty()) return null // :1100
        val dip = ctx.instance.dipState
        if (dip == AiInstanceState.D_PEACE || dip == AiInstanceState.D_DECLARED) return null // :1103-1105

        val generalCandidates = LinkedHashMap<Int, AiGeneralView>() // :1107
        for (npcGeneral in world.npcWarGenerals.values) {
            val generalID = npcGeneral.general.id // :1109
            val cityID = npcGeneral.general.cityId // :1110
            if (world.frontCities.containsKey(cityID)) continue // :1111-1113
            if (!world.nationCities.containsKey(cityID)) continue // :1114-1116
            if (npcGeneral.general.crew < ctx.nationPolicy.minWarCrew) continue // :1117-1119
            if (npcGeneral.general.troop != 0) continue // :1120-1122
            val train = npcGeneral.general.train // :1124
            val atmos = npcGeneral.general.atmos // :1125
            if (maxOf(train, atmos) < ctx.nationPolicy.properWarTrainAtmos) continue // :1127-1129
            generalCandidates[generalID] = npcGeneral // :1131
        }
        if (generalCandidates.isEmpty()) return null // :1134-1136

        // :1138-1141 — choiceUsingWeight($cityCandidates) weight = front 'important'.
        val cityCandidates = world.frontCities.values.map { it.cityId to it.important.toDouble() }
        // :1143-1146 — general choice FIRST then choiceUsingWeight SECOND.
        val destGeneral = rng.choice(generalCandidates.values.toList()).general.id
        val destCity = rng.choiceUsingWeightPair(cityCandidates)
        return gatedDeploy(ctx, deployArg(destGeneral, destCity))
    }

    // --- doNPC내정발령 (PHP `:1155-1221`) — source-general choice + choiceUsingWeight(dev score) ---

    private fun doNPC내정발령(ctx: GeneralAiContext, lastTurn: LastTurn?): ChosenCommand? =
        domesticDeploy(ctx, useWarGeneralsMerge = false)

    /**
     * The shared 내정발령 body (do유저장내정발령 `:877-947` / doNPC내정발령 `:1155-1221` — byte-identical except
     * the source-general bucket): source-general `choice` FIRST then `choiceUsingWeight(city score)` SECOND,
     * with the `srcCity.dev <= destCity.dev` post-pick null-gate.
     *
     * @param useWarGeneralsMerge `do유저장내정발령` uses `userCivilGenerals` (merged with `userWarGenerals` at
     *  d평화/d선포, `:891-894`); `doNPC내정발령` uses `npcCivilGenerals` (merged with `npcWarGenerals` at
     *  d평화/d선포, `:1169-1172`). The merge order is `[war..., civil...]` (PHP `array_merge(war, civil)`).
     */
    private fun domesticDeploy(ctx: GeneralAiContext, useWarGeneralsMerge: Boolean): ChosenCommand? {
        val rng = ctx.rng
        val world = ctx.world
        if (ctx.instance.nation.capital == 0) return null // :879/1157
        if (world.supplyCities.size == 1) return null // :882/1160

        // :886-889/1164-1167 — avgDev over supplyCities; >= 0.99 → null.
        val supplyVals = world.supplyCities.values.toList()
        val avgDev = supplyVals.sumOf { it.dev } / supplyVals.size
        if (avgDev >= 0.99) return null

        val dip = ctx.instance.dipState
        val peaceOrDeclared = dip == AiInstanceState.D_PEACE || dip == AiInstanceState.D_DECLARED
        // :891-894/1169-1172 — array_merge(war, civil) at d평화/d선포 ELSE civil only. War FIRST in the merge.
        val sourceGenerals: List<AiGeneralView> = if (useWarGeneralsMerge) {
            if (peaceOrDeclared) world.userWarGenerals.values + world.userCivilGenerals.values
            else world.userCivilGenerals.values.toList()
        } else {
            if (peaceOrDeclared) world.npcWarGenerals.values + world.npcCivilGenerals.values
            else world.npcCivilGenerals.values.toList()
        }

        val generalCandidates = ArrayList<AiGeneralView>() // :896/1174
        for (general in sourceGenerals) {
            // :898-900 — the user variant additionally skips troop members (the npc variant does NOT).
            if (useWarGeneralsMerge && general.general.troop != 0) continue
            val cityID = general.general.cityId
            val city = world.supplyCities[cityID] ?: continue // :902-905/1177-1180
            if (city.dev < 0.95) continue // :909/1183
            generalCandidates.add(general) // :912/1186
        }
        if (generalCandidates.isEmpty()) return null // :915/1189

        // :919-926/1193-1200 — choiceUsingWeight($cityCandidiates) weight = (1-min(dev,0.999))^2 / sqrt(#gen+1).
        val cityCandidates = ArrayList<Pair<Int, Double>>()
        for (city in world.supplyCities.values) {
            val dev = minOf(city.dev, 0.999)
            var score = 1 - dev
            score *= score // `**= 2`
            score /= Math.sqrt(city.generals.size + 1.0)
            cityCandidates.add(city.cityId to score)
        }

        // :928-931/1202-1205 — source-general choice FIRST then choiceUsingWeight SECOND.
        val destGeneral = rng.choice(generalCandidates)
        val srcCity = world.supplyCities.getValue(destGeneral.general.cityId) // :930/1204
        val destCityId = rng.choiceUsingWeightPair(cityCandidates) // :931/1205
        val destCity = world.supplyCities.getValue(destCityId)

        if (srcCity.dev <= destCity.dev) return null // :933-935/1207-1209

        return gatedDeploy(ctx, deployArg(destGeneral.general.id, destCity.cityId))
    }

    /**
     * The backup-then-supply high-population recruitable city list shared by 부대후방발령 (`:454-474`) /
     * 부대유저장후방발령 (`:619-639`): backup cities with `popRatio >= ratio` first; when empty, the supply
     * cities with `popRatio >= ratio`. Returns the city value list (insertion order) or null when empty.
     */
    private fun backupOrSupplyHighPop(ctx: GeneralAiContext): List<NationCity>? {
        val ratio = ctx.nationPolicy.safeRecruitCityPopulationRatio
        val out = ArrayList<NationCity>()
        for (city in ctx.world.backupCities.values) { // :456/621
            if (popRatio(city) < ratio) continue // :457/622
            out.add(city) // :460/625
        }
        if (out.isEmpty()) { // :463/628
            for (city in ctx.world.supplyCities.values) { // :464/629
                if (popRatio(city) < ratio) continue // :465/630
                out.add(city) // :468/633
            }
        }
        if (out.isEmpty()) return null // :472/637
        return out
    }

    /**
     * `$general->getVar('defence_train')` (PHP `:772-773`) — the 수비 train/atmos floor; rides the general's
     * `meta['defence_train']`, written by the AI as a meta-KV delta. Default 0 (never set → trivially
     * satisfied, so the defensible-general skip is data-driven by the fixture).
     */
    private fun defenceTrain(gv: AiGeneralView): Double =
        (gv.general.meta["defence_train"] as? Number)?.toDouble() ?: 0.0

    /** `[$fromCityID, $toCityID] = CombatForce[$leaderID]` (PHP `:333`) — a 2-elem [from, to] tuple. */
    @Suppress("UNCHECKED_CAST")
    private fun combatForcePair(value: Any?): Pair<Int, Int>? {
        val list = (value as? List<Any?>) ?: return null
        if (list.size < 2) return null
        val from = (list[0] as? Number)?.toInt() ?: return null
        val to = (list[1] as? Number)?.toInt() ?: return null
        return from to to
    }

    /** `in_array($leaderID, SupportForce)` (PHP `:414/500`) — SupportForce is a flat id LIST (map values). */
    private fun supportForceContains(supportForce: Map<Any?, Any?>, leaderId: Int): Boolean {
        for (v in supportForce.values) {
            if ((v as? Number)?.toInt() == leaderId) return true
        }
        return false
    }

    /**
     * `Util::joinYearMonth(year, month)` = `year*12 + month - 1` (H-HELPERS §1, the `-1` variant) — the
     * cutTurn-compare base (PHP `:306/409`). Inlined to keep the family PURE (the GREEN
     * `NationCommand.joinYearMonth` is the same formula; F-SEAM threads the identical value).
     */
    private fun NationCommandJoinYearMonth(year: Int, month: Int): Int = year * 12 + month - 1
}
