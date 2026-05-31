package opensamguk.engine.turn

import opensamguk.common.constants.CityConst
import opensamguk.common.constants.GameConst
import opensamguk.common.constants.GameUnitConst
import opensamguk.common.constants.GameUnitDetail
import opensamguk.common.constants.UnitConstraint
import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.actions.military.RecruitAlgorithm
import opensamguk.logic.actions.military.UnitSetTable
import opensamguk.logic.ai.AiDiplomacyRow
import opensamguk.logic.ai.AiEnv
import opensamguk.logic.ai.AiGeneralView
import opensamguk.logic.ai.AiInstanceState
import opensamguk.logic.ai.AiKvRecorder
import opensamguk.logic.ai.AiNationRow
import opensamguk.logic.ai.AiSeed
import opensamguk.logic.ai.AiWorldView
import opensamguk.logic.ai.AutorunGeneralPolicy
import opensamguk.logic.ai.AutorunNationPolicy
import opensamguk.logic.ai.ChosenCommand
import opensamguk.logic.ai.GeneralAiContext
import opensamguk.logic.ai.GeneralAiDoBodies
import opensamguk.logic.ai.GeneralAiFactory
import opensamguk.logic.ai.GeneralAiInput
import opensamguk.logic.ai.KvDelta
import opensamguk.logic.ai.NationAiInput
import opensamguk.logic.ai.NationPassHooks
import opensamguk.logic.ai.candidateAllowed
import opensamguk.logic.ai.families.GenDomesticFamily
import opensamguk.logic.ai.families.NationDiploFamily
import opensamguk.logic.ai.families.RatesPromoFamily
import opensamguk.logic.ai.bfs.AiDistance
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.ConstraintMode
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.domestic.getGoldIncome
import opensamguk.logic.domestic.getRiceIncome
import opensamguk.logic.domestic.getWallIncome
import opensamguk.logic.domestic.techLimit
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.stats.StatCalc
import opensamguk.logic.statview.WorldEnvBuilder
import java.time.Duration

/**
 * P5 F-SEAM (Task FM1 + MATERIALIZE) — the SINGLE seam owner that bridges the daemon's in-memory world to
 * the PURE `:logic` [GeneralAI] decision spine.
 *
 * For ONE due general it:
 *  1. builds the SOLE per-general-per-decision `"GeneralAI"` [opensamguk.common.rng.RandUtil] via
 *     [AiSeed.rng] (F-SEED; 5-component `serializeSeed(hidden,"GeneralAI",year,month,generalId)`), built ONCE
 *     and threaded BY REFERENCE through the whole decision — NEVER re-seeded (decision #1);
 *  2. constructs the read-only [AiInstanceState] (`updateInstance`/`calcGenType` FIRST-draw) + [AiWorldView]
 *     (the categorize/derive facade) over the live [InMemoryTurnWorld] — READ-ONLY over GAME ENTITIES;
 *  3. wires the `candidateAllowed` F-BRIDGE gate over a [WorldStateViewAdapter] (FULL mode);
 *  4. routes the AI's meta-KV side-effects through the [AiKvRecorder] delta seam ([kvDeltas]);
 *  5. runs [GeneralAI.chooseGeneralTurn]/`chooseNationTurn` and returns the chosen [ChosenCommand].
 *
 * **MATERIALIZE wave:** every stubbed/defaulted context input is now sourced from the live world:
 *  - S1 — [AiWorldView] is fed the REAL nation generals (PK-ascending `sortedBy { it.id }`, self-excluded)
 *    + the FULL city table (PK-ascending), so the 9 general buckets + the 4 city buckets are real.
 *  - S2 — `reservedCommandName` per general via the optional [reservedCommandNameOf] turn-loop lookup.
 *  - S3 — per-city `cityDevelRateOf` triples + the acting-city `cityDevelRate` map (REUSE
 *    [RatesPromoFamily.calcCityDevelRate], ZERO decision-rng draws).
 *  - S4-S12 — nationTech, attackable cities, cityGeneralCount, the wander/found occupied sets, the
 *    self-city level / dupLord / makelimit / affinity scalars, the nation counts, the seonyang/오랑캐 pools.
 *  - S15-S16 — `unitCostWithTechOf` (crew-cost table × nationTech) + `recruitPopScoreOf`/`leadershipNoInjuryOf`/
 *    `reservedIsRecruitOf`.
 *  - S18 — the [NationPassHooks] (categorize / choosePromotion / chooseNonLordPromotion[MAY draw] /
 *    chooseTexRate / chooseGoldBillRate / chooseRiceBillRate[NO draw]) wired over the live nation buckets.
 *
 * **The AI is READ-ONLY over GAME ENTITIES** — this adapter never mutates a general/city/nation row; the
 * chosen command's mutation runs the EXISTING [ReservedTurnHandler] resolve → [ChangeRecorder] delta path.
 * The only AI-side writes are the queued [kvDeltas] (visible NEXT turn). NO JPA `EntityManager` (the daemon
 * write-path rule, enforced by `DaemonNoEntityManagerTest`).
 *
 * @param world the live in-memory turn world (read-only here).
 * @param registry the command registry (the F-BRIDGE gate resolves a `che_*` to its def).
 * @param hiddenSeed the per-game `UniqueConst::$hiddenSeed` — seed component 1 of the `"GeneralAI"` lineage.
 * @param startYear the scenario start year — `develCost = (year-startYear+10)*2` (the AI env).
 * @param turnTerm the env `turnterm` (minutes-per-turn).
 * @param pipeline the GREEN stat pipeline (the per-decision [StatCalc] shares it with the resolve).
 * @param reservedCommandNameOf the per-general `turn_idx 0` reserved command name lookup (S2/S16) threaded by
 *   the turn loop (which holds the reserved-turn map); default `{ null }` = 휴식/none — the daemon wires the
 *   real map. NEVER a JPA/DB read here (the adapter stays decoupled from persistence).
 */
class AiTurnAdapter(
    private val world: InMemoryTurnWorld,
    private val registry: CommandRegistry,
    private val hiddenSeed: String,
    private val startYear: Int,
    private val turnTerm: Int = 1,
    private val pipeline: GeneralActionPipeline = GeneralActionPipeline(),
    private val reservedCommandNameOf: (generalId: Int) -> String? = { null },
) {

    /**
     * The queued meta-KV deltas this decision produced (decision #12 / M4). The AI is read-only over GAME
     * ENTITIES; its only writes are these deltas. Insertion order = write order.
     */
    val kvDeltas: List<KvDelta> get() = _kvDeltas.toList()
    private val _kvDeltas = ArrayList<KvDelta>()

    /** Whether [generalId] is AI-controlled (`npc >= 2`, PHP `$general->isNPC()`) — the hook gate. */
    fun isAiControlled(generalId: Int): Boolean =
        world.getGeneralById(generalId)?.let { ReservedTurnHandler.isAiControlled(it) } ?: false

    /**
     * Choose the AI command for [generalId], replacing the [reserved] one (R-SEAM §2 `:332`). Runs the
     * whole decision on the SOLE per-general `"GeneralAI"` rng (threaded by reference).
     */
    fun chooseGeneralTurn(generalId: Int, reserved: ReservedTurn): ChosenCommand {
        val general = world.getGeneralById(generalId)
            ?: error("AiTurnAdapter: general $generalId not in world")
        val state = world.getState()
        val year = state.currentYear
        val month = state.currentMonth
        val nationId = general.nationId
        val npcType = general.npcState

        // (1) the SOLE per-general-per-decision rng (F-SEED) — built ONCE, threaded by reference.
        val rng = AiSeed.rng(hiddenSeed, year, month, generalId)

        val nationTech = nationTechOf(nationId)
        val generalPolicy = AutorunGeneralPolicy(npcType = npcType, nationId = nationId)
        val develCost = WorldEnvBuilder.envMap(year, startYear)["develCost"] as Int
        val nationPolicy = AutorunNationPolicy(npcType = npcType, tech = nationTech, develcost = develCost)

        // (2) the read-only instance + world view over the live world (READ-ONLY over GAME ENTITIES).
        val aiEnv = AiEnv(year = year, month = month, startYear = startYear, develCost = develCost)
        val kvRecorder = object : AiKvRecorder {
            override fun recordNationKv(nationId: Int, key: String, value: Any?) {
                _kvDeltas.add(KvDelta(nationId, key, value))
            }
        }
        val instance = AiInstanceState(
            generalNationId = nationId,
            env = aiEnv,
            nationPolicy = nationPolicy,
            nationRowLookup = { world.getNationById(nationId)?.let { toAiNationRow(it) } },
            nationStor = nationEnvSnapshot(nationId),
            diplomacyOf = { diplomacyRowsFor(nationId) },
            frontMaxOf = { frontMaxFor(nationId) },
            kvRecorder = kvRecorder,
        )

        // (3) the F-BRIDGE candidate gate over the FULL-mode WorldStateViewAdapter.
        val envMap = WorldEnvBuilder.envMap(year, startYear)
        val candidateAllowedHook = candidateAllowedHook(generalId, general.cityId, nationId, envMap)

        val recordGeneralKv: (Int, String, Any?) -> Unit = { gid, key, value ->
            _kvDeltas.add(KvDelta(gid, key, value))
        }

        // Eagerly run updateInstance() (NO draws) so instance.dipState is available below; the guard makes
        // the hook's second updateInstance() a no-op so calcGenType still fires exactly once on the rng.
        instance.updateInstance()

        val statCalc = StatCalc(PerTurnOverlay.toLogicGeneral(general), pipeline)
        val updateInstanceHook: (opensamguk.common.rng.RandUtil) -> Unit = { r ->
            instance.updateInstance()
            instance.calcGenType(r, statCalc)
        }

        // F-FACADE — fed the REAL self-excluded PK-ascending nation generals + the FULL PK-ascending city table.
        val worldView = buildWorldView(nationId, generalId, instance, nationPolicy)

        val logicCity = world.getCityById(general.cityId)?.let { PerTurnOverlay.toLogicCity(it) }
        val ctx = buildGeneralContext(
            general = general,
            rng = rng,
            instance = instance,
            worldView = worldView,
            generalPolicy = generalPolicy,
            nationPolicy = nationPolicy,
            aiEnv = aiEnv,
            statCalc = statCalc,
            nationTech = nationTech,
            logicCity = logicCity,
            candidateAllowedHook = candidateAllowedHook,
            recordGeneralKv = recordGeneralKv,
            year = year,
            month = month,
        )

        val bodies = GeneralAiDoBodies.fromFamilies(ctx)
        val ai = GeneralAiFactory.build(
            generalPolicy = generalPolicy,
            bodies = bodies,
            nationPolicy = nationPolicy,
            updateInstance = updateInstanceHook,
        )

        val input = buildGeneralAiInput(general, generalPolicy, year, month, rng)
        return ai.chooseGeneralTurn(
            reservedCommand = ChosenCommand(reserved.actionCode, ReservedTurnHandler.decodeArgs(reserved.argJson)),
            input = input,
        )
    }

    /**
     * Choose the AI NATION command for the chief [generalId] (officer_level>=5), replacing the [reserved]
     * one (R-SEAM §2 `:305-308`). Materialises the live nation buckets + the [NationPassHooks].
     */
    fun chooseNationTurn(generalId: Int, reserved: ReservedTurn, lastTurn: LastTurn): ChosenCommand {
        val general = world.getGeneralById(generalId)
            ?: error("AiTurnAdapter: general $generalId not in world")
        val state = world.getState()
        val year = state.currentYear
        val month = state.currentMonth
        val nationId = general.nationId
        val npcType = general.npcState

        val rng = AiSeed.rng(hiddenSeed, year, month, generalId)
        val nationTech = nationTechOf(nationId)
        val generalPolicy = AutorunGeneralPolicy(npcType = npcType, nationId = nationId)
        val develCost = WorldEnvBuilder.envMap(year, startYear)["develCost"] as Int
        val nationPolicy = AutorunNationPolicy(npcType = npcType, tech = nationTech, develcost = develCost)

        val aiEnv = AiEnv(year = year, month = month, startYear = startYear, develCost = develCost)
        val kvRecorder = object : AiKvRecorder {
            override fun recordNationKv(nationId: Int, key: String, value: Any?) {
                _kvDeltas.add(KvDelta(nationId, key, value))
            }
        }
        val instance = AiInstanceState(
            generalNationId = nationId,
            env = aiEnv,
            nationPolicy = nationPolicy,
            nationRowLookup = { world.getNationById(nationId)?.let { toAiNationRow(it) } },
            nationStor = nationEnvSnapshot(nationId),
            diplomacyOf = { diplomacyRowsFor(nationId) },
            frontMaxOf = { frontMaxFor(nationId) },
            kvRecorder = kvRecorder,
        )
        instance.updateInstance()

        val envMap = WorldEnvBuilder.envMap(year, startYear)
        val candidateAllowedHook = candidateAllowedHook(generalId, general.cityId, nationId, envMap)
        val recordGeneralKv: (Int, String, Any?) -> Unit = { gid, key, value ->
            _kvDeltas.add(KvDelta(gid, key, value))
        }

        val statCalc = StatCalc(PerTurnOverlay.toLogicGeneral(general), pipeline)
        val worldView = buildWorldView(nationId, generalId, instance, nationPolicy)

        val logicCity = world.getCityById(general.cityId)?.let { PerTurnOverlay.toLogicCity(it) }
        val ctx = buildGeneralContext(
            general = general,
            rng = rng,
            instance = instance,
            worldView = worldView,
            generalPolicy = generalPolicy,
            nationPolicy = nationPolicy,
            aiEnv = aiEnv,
            statCalc = statCalc,
            nationTech = nationTech,
            logicCity = logicCity,
            candidateAllowedHook = candidateAllowedHook,
            recordGeneralKv = recordGeneralKv,
            year = year,
            month = month,
        )

        // The diplo SELECTION inputs (decision #11 / m10 — SELECTION + boolean + draw IS P5 scope; the che_*
        // RESULT internals are P6). Materialised from the live world: recv_assist KV + income (불가침제의), the
        // neighbor partition (선전포고), the capital-connected BFS + Floyd (천도). All read-only world reads.
        val diploInput = buildDiploInput(general, nationId, instance, worldView, kvRecorder, year, month)
        val warInput = buildWarInput(nationId, worldView, instance, nationTech, nationPolicy, year)
        val relocInput = buildRelocateInput(general, nationId, worldView, kvRecorder, year, month)

        val bodies = GeneralAiDoBodies.fromFamilies(
            ctx, diplo = diploInput, war = warInput, reloc = relocInput,
        ).copy(
            hasFullConditionMet = { cmd -> candidateAllowedHook(cmd.actionCode, cmd.args) },
        )

        // S18 — the nation-pass hooks over the live world (categorize + the rate/promotion side-effects).
        val nationHooks = buildNationPassHooks(
            general = general,
            nationId = nationId,
            nationTech = nationTech,
            nationPolicy = nationPolicy,
            worldView = worldView,
            instance = instance,
            rng = rng,
            statCalc = statCalc,
            recordGeneralKv = recordGeneralKv,
            kvRecorder = kvRecorder,
            year = year,
            month = month,
        )

        val ai = GeneralAiFactory.build(
            generalPolicy = generalPolicy,
            bodies = bodies,
            nationPolicy = nationPolicy,
            nationHooks = nationHooks,
        )

        val input = NationAiInput(npcType = npcType, officerLevel = general.officerLevel, month = month, rng = rng)
        return ai.chooseNationTurn(
            reservedCommand = ChosenCommand(reserved.actionCode, ReservedTurnHandler.decodeArgs(reserved.argJson)),
            lastTurn = lastTurn,
            input = input,
        )
    }

    // ====================================================================================================
    // MATERIALIZE — the live-world → context wiring (S1-S18). READ-ONLY over GAME ENTITIES.
    // ====================================================================================================

    /**
     * The F-BRIDGE gate hook (argTest THEN evaluateConstraints(FULL)), IDENTICAL to the resolve gate.
     *
     * The AI emits RAW `destGeneralID`/`destCityID`/`destNationID` args; the dest-* constraints
     * ([existsDestGeneral]/[occupiedDestCity]/[suppliedDestCity]/[friendlyDestGeneral]/…) read the
     * [ConstraintContext.destGeneralId]/[ConstraintContext.destCityId]/[ConstraintContext.destNationId]
     * fields, so the adapter DERIVES them from the emitted args (the bridge's `ctx.copy(args=canonical)`
     * only carries the arg map). This is the adapter's canonicalization — the bridge stays unchanged.
     */
    private fun candidateAllowedHook(
        generalId: Int,
        cityId: Int,
        nationId: Int,
        envMap: Map<String, Any?>,
    ): (String, Map<String, Any?>) -> Boolean {
        val overlay = PerTurnOverlay(world)
        val view = WorldStateViewAdapter(overlay, env = envMap)
        return { actionCode, rawArgs ->
            val ctx = ConstraintContext(
                actorId = generalId,
                cityId = cityId,
                nationId = nationId,
                destGeneralId = (rawArgs["destGeneralID"] as? Number)?.toInt(),
                destCityId = (rawArgs["destCityID"] as? Number)?.toInt(),
                destNationId = (rawArgs["destNationID"] as? Number)?.toInt(),
                env = envMap,
                mode = ConstraintMode.FULL,
            )
            candidateAllowed(actionCode, rawArgs, ctx, view) { code -> resolveDef(code) }
        }
    }

    /**
     * S1 — build the [AiWorldView] fed the REAL nation generals (PK-ascending `sortedBy { it.id }`,
     * self-excluded — PHP `:3516` `... AND no != %i`, no ORDER BY → clustered PK) + the FULL city table
     * (PK-ascending; the facade filters by `row.nationId == ownNationId` itself, and needs the non-nation
     * rows for the BFS/attackable scans). The categorize bucketing is the GREEN [AiWorldView]'s job.
     */
    private fun buildWorldView(
        nationId: Int,
        selfGeneralId: Int,
        instance: AiInstanceState,
        nationPolicy: AutorunNationPolicy,
    ): AiWorldView {
        val generals = world.listGenerals()
            .filter { it.nationId == nationId && it.id != selfGeneralId }
            .sortedBy { it.id }
            .map { toAiGeneralView(it) }
        val cityRows = world.listCities()
            .sortedBy { it.id }
            .map { PerTurnOverlay.toLogicCity(it) }
        return AiWorldView(
            ownNationId = nationId,
            cityRows = cityRows,
            warTargetNation = instance.warTargetNation,
            ownGeneralId = selfGeneralId,
            generals = generals,
            dipState = instance.dipState,
            minWarCrew = nationPolicy.minWarCrew,
            minNpcWarLeadership = nationPolicy.minNPCWarLeadership,
            turnTerm = turnTerm,
        )
    }

    /**
     * Convert an engine [TurnGeneral] → the logic [AiGeneralView] the categorize buckets read (S1):
     *  - `recentWarSeconds` = DateInterval(recent_war, turntime) seconds, null when recent_war is falsy
     *    (PHP `General.php:286` → the 12000 sentinel in `calcRecentWarTurn`);
     *  - `reservedCommandName` = the `turn_idx 0` reserved command name (S2, via [reservedCommandNameOf]);
     *  - `fullLeadership` = `getLeadership(false)` (G8 no-injury, the SAME site `calcGenType` uses).
     */
    private fun toAiGeneralView(tg: TurnGeneral): AiGeneralView {
        val recentWarSeconds = tg.recentWarTime?.let { Duration.between(it, tg.turnTime).seconds }
        val statCalc = StatCalc(PerTurnOverlay.toLogicGeneral(tg), pipeline)
        return AiGeneralView(
            general = PerTurnOverlay.toLogicGeneral(tg),
            recentWarSeconds = recentWarSeconds,
            reservedCommandName = reservedCommandNameOf(tg.id),
            fullLeadership = AiSeed.genTypeLeadership(statCalc),
        )
    }

    /** S4 — `$nation['tech']` rides `Nation.meta["tech"]` (no tech column); default 0. */
    private fun nationTechOf(nationId: Int): Int =
        (world.getNationById(nationId)?.meta?.get("tech") as? Number)?.toInt() ?: 0

    /**
     * Build the per-general [GeneralAiContext] with every derived scalar sourced from the live world
     * (S3-S17). The decision logic lives in the bodies + [AiWorldView]; this only FEEDS data.
     */
    private fun buildGeneralContext(
        general: TurnGeneral,
        rng: opensamguk.common.rng.RandUtil,
        instance: AiInstanceState,
        worldView: AiWorldView,
        generalPolicy: AutorunGeneralPolicy,
        nationPolicy: AutorunNationPolicy,
        aiEnv: AiEnv,
        statCalc: StatCalc,
        nationTech: Int,
        logicCity: opensamguk.logic.domain.City?,
        candidateAllowedHook: (String, Map<String, Any?>) -> Boolean,
        recordGeneralKv: (Int, String, Any?) -> Unit,
        year: Int,
        month: Int,
    ): GeneralAiContext {
        val nationId = general.nationId
        val occupiedCities = occupiedCitiesSet() // S8 — lord/nation cities (shared by wander + found).
        val selfCity = world.getCityById(general.cityId)
        return GeneralAiContext(
            rng = rng,
            instance = instance,
            world = worldView,
            generalPolicy = generalPolicy,
            nationPolicy = nationPolicy,
            env = aiEnv,
            turnTerm = turnTerm,
            selfGeneralId = general.id,
            selfCityId = general.cityId,
            candidateAllowed = candidateAllowedHook,
            recordGeneralKv = recordGeneralKv,
            // S17 — cutTurn one-deploy-per-turn lambdas (engine wall-clock; the turn loop owns the formatted stamp).
            chiefTurnTime = cutTurn(general.turnTime),
            turnTimeOf = { gv -> turnTimeOf(gv) },
            last발령Of = { gv -> (gv.general.meta["last발령"] as? Number)?.toInt() },
            // S16 — recruitPopScore / leadershipNoInjury / reservedIsRecruit per general.
            recruitPopScoreOf = { gv -> recruitPopScoreOf(gv) },
            leadershipNoInjuryOf = { gv -> gv.fullLeadership },
            reservedIsRecruitOf = { gv -> reservedCommandNameOf(gv.general.id) == "che_징병" },
            // S15 — the per-general unit cost (crew-cost table × nationTech), the 포상 reqMoney base.
            unitCostWithTechOf = { gv -> unitCostWithTechOf(gv, nationTech) },
            // --- L-GENDOM scalars (the acting general + acting city) ---
            leadershipWithInjury = statCalc.getStatValue("leadership"),
            strengthWithInjury = statCalc.getStatValue("strength"),
            intelWithInjury = statCalc.getStatValue("intel"),
            fullLeadership = AiSeed.genTypeLeadership(statCalc),
            fullStrength = AiSeed.genTypeStrength(statCalc),
            fullIntel = AiSeed.genTypeIntel(statCalc),
            nationTech = nationTech,
            selfCrew = general.crew,
            selfCity = logicCity,
            cityDevelRate = cityDevelRateMap(logicCity), // S3 — acting-city ratios (index-0 of the triples).
            techLimited = techLimit(startYear, year, nationTech.toDouble()),        // S5
            techLimitedNextGrade = techLimit(startYear, year, (nationTech + 1000).toDouble()), // S5
            // S13 — the do금쌀구매 deterministic buy/sell ladder over the live market/resources (ZERO draws).
            tradeDecision = { tradeDecision(general, logicCity, generalPolicy, instance, statCalc, nationTech) },
            // S14 — the do징병 recruit ladders (preset/dex-weighted armType + isValid pickScore + cost ladder).
            recruitArmType = recruitArmType(general, instance.genType),
            recruitArmTypeWeights = recruitArmTypeWeights(general, instance.genType, statCalc),
            recruitCrewScoresFor = { armType -> recruitCrewScoresFor(general, nationId, armType, nationTech, year) },
            recruitFinalize = { crewTypeId ->
                recruitFinalize(general, crewTypeId, generalPolicy, statCalc, nationTech, candidateAllowedHook)
            },
            // --- L-GENWAR scalars + world lambdas ---
            selfTrain = general.train.toDouble(),
            selfAtmos = general.atmos.toDouble(),
            selfGold = general.gold,
            selfRice = general.rice,
            selfNpcType = general.npcState,
            selfKillturn = ReservedTurnHandler.metaInt(general, "killturn", 0),
            attackableCitiesOf = { nearCityIds, attackableNations -> // S6
                attackableCitiesOf(nearCityIds, attackableNations)
            },
            cityDevelRateOf = { cityId -> cityDevelRateTriples(cityId) }, // S3
            cityGeneralCountOf = { cityId -> world.listGenerals().count { it.cityId == cityId } }, // S7
            wanderOccupiedCities = occupiedCities, // S8
            movingTargetCityId = (general.meta["movingTargetCityID"] as? Number)?.toInt(), // S9
            dupLordAtSelfCity = world.listGenerals().count { it.officerLevel == 12 && it.cityId == general.cityId }, // S9
            selfCityLevel = selfCity?.level ?: 0, // S9
            // --- L-GENFOUND scalars + world inputs ---
            selfMakeLimit = (general.meta["makelimit"] as? Number)?.toInt() ?: 0, // S10
            selfGeneralName = general.name, // S10
            selfAffinity = (general.meta["affinity"] as? Number)?.toInt() ?: 0, // S10
            foundOccupiedCities = occupiedCities, // S8 (identical occupied-set query)
            chiefStatMin = CHIEF_STAT_MIN, // PHP GameConst::$chiefStatMin (d_setting/GameConst.php:11 = 65).
            foundStatMidpoint = (DEFAULT_STAT_NPC_MAX + CHIEF_STAT_MIN) / 2.0, // PHP :3268 (75+65)/2 = 70.
            foundDeadlineMore = foundDeadlineMore(nationId, year), // PHP :3277
            nationCount = world.listNations().size, // S11
            notFullNationCount = notFullNationCount(), // S11
            seonyangCandidates = world.listGenerals() // S12
                .filter { it.nationId == nationId }
                .sortedBy { it.id }
                .map { PerTurnOverlay.toLogicGeneral(it) },
            orankaeRulerCandidates = world.listGenerals() // S12
                .filter { it.officerLevel == 12 && it.npcState == 9 && it.nationId != 0 }
                .sortedBy { it.id }
                .map { PerTurnOverlay.toLogicGeneral(it) },
        )
    }

    /**
     * S18 — the nation-pass hooks: categorize (idempotent), the three rate hooks (NO draw), and the two
     * promotion hooks (MAY draw — L-RATES). All route their KV mutations through the recorder delta seam.
     */
    private fun buildNationPassHooks(
        general: TurnGeneral,
        nationId: Int,
        nationTech: Int,
        nationPolicy: AutorunNationPolicy,
        worldView: AiWorldView,
        instance: AiInstanceState,
        rng: opensamguk.common.rng.RandUtil,
        statCalc: StatCalc,
        recordGeneralKv: (Int, String, Any?) -> Unit,
        kvRecorder: AiKvRecorder,
        year: Int,
        month: Int,
    ): NationPassHooks {
        val nationRow = world.getNationById(nationId)
        val deltaSink = object : RatesPromoFamily.RatesPromoDeltaSink {
            override fun recordGeneralKv(generalId: Int, key: String, value: Any?) {
                _kvDeltas.add(KvDelta(generalId, key, value))
            }

            override fun recordNationKv(nationId: Int, key: String, value: Any?) {
                _kvDeltas.add(KvDelta(nationId, key, value))
            }
        }

        // The rates/promotion context is materialised lazily so categorize has already run (the worldView
        // buckets are lazy-once; reading them here forces categorizeNationGeneral/Cities).
        val ratesCtx: () -> RatesPromoFamily.RatesPromoContext = {
            buildRatesPromoContext(
                nationId = nationId,
                nationTech = nationTech,
                nationPolicy = nationPolicy,
                worldView = worldView,
                rng = rng,
                selfOfficerLevel = general.officerLevel,
                nationRow = nationRow,
                deltaSink = deltaSink,
                year = year,
            )
        }

        return NationPassHooks(
            updateNationInstance = { instance.updateInstance() },
            categorizeNationGeneral = { worldView.categorizeNationGeneral() },
            categorizeNationCities = { worldView.categorizeNationCities() },
            choosePromotion = { RatesPromoFamily.bodies(ratesCtx()).choosePromotion() },
            chooseNonLordPromotion = { RatesPromoFamily.bodies(ratesCtx()).chooseNonLordPromotion() },
            chooseTexRate = { RatesPromoFamily.bodies(ratesCtx()).chooseTexRate() },
            chooseGoldBillRate = { RatesPromoFamily.bodies(ratesCtx()).chooseGoldBillRate() },
            chooseRiceBillRate = { RatesPromoFamily.bodies(ratesCtx()).chooseRiceBillRate() },
            nationGeneralId = general.id,
            useAutoNationTurn = (general.meta["use_auto_nation_turn"] as? Number)?.toInt()?.let { it != 0 } ?: true,
            nationTurnTimeHm = turnTimeHm(general.turnTime),
        )
    }

    /**
     * Build the [RatesPromoFamily.RatesPromoContext] over the live nation buckets (categorize already run).
     * The promotion candidate pools are the PK-ascending categorize buckets (a parity target, never re-sorted).
     * The rate-hook income inputs are left at their derived defaults (the bill hooks only run for an
     * officer_level==12 ruler in months 6/12 and make ZERO draws; the income half-away helpers are wired in a
     * follow-up — the develRate ladder over the live supplyCities already drives chooseTexRate's draw-free path).
     */
    private fun buildRatesPromoContext(
        nationId: Int,
        nationTech: Int,
        nationPolicy: AutorunNationPolicy,
        worldView: AiWorldView,
        rng: opensamguk.common.rng.RandUtil,
        selfOfficerLevel: Int,
        nationRow: Nation?,
        deltaSink: RatesPromoFamily.RatesPromoDeltaSink,
        year: Int,
    ): RatesPromoFamily.RatesPromoContext {
        val nationLevel = nationRow?.level ?: 0
        val chiefSet = decodeChiefSet(nationRow) // PHP nation['chief_set'] bitfield → occupied chief levels.
        val chiefGenerals = worldView.chiefGenerals // keyed by officer_level (PK-asc overwrite-last).
        val chiefGeneralLevels = chiefGenerals.keys.toSet()
        return RatesPromoFamily.RatesPromoContext(
            rng = rng,
            nationId = nationId,
            nationLevel = nationLevel,
            chiefSet = chiefSet,
            chiefGeneralLevels = chiefGeneralLevels,
            chiefGeneralOf = { level -> chiefGenerals[level]?.let { toPromotionCandidate(it, nationTech) } },
            selfOfficerLevel = selfOfficerLevel,
            chiefStatMin = CHIEF_STAT_MIN.toDouble(),
            killturnEnv = killturnEnv(),
            turnTerm = turnTerm,
            npcWarGenerals = worldView.npcWarGenerals.values.map { toPromotionCandidate(it, nationTech) },
            npcCivilGenerals = worldView.npcCivilGenerals.values.map { toPromotionCandidate(it, nationTech) },
            userWarGenerals = worldView.userWarGenerals.values.map { toPromotionCandidate(it, nationTech) },
            userCivilGenerals = worldView.userCivilGenerals.values.map { toPromotionCandidate(it, nationTech) },
            userGenerals = worldView.userGenerals.values.map { toPromotionCandidate(it, nationTech) },
            nationGenerals = worldView.nationGenerals.map { toPromotionCandidate(it, nationTech) },
            supplyCities = worldView.supplyCities.values.map { cityDevelInput(it.city) },
            nationGold = nationRow?.gold ?: 0,
            nationRice = nationRow?.rice ?: 0,
            reqNationGold = nationPolicy.reqNationGold,
            reqNationRice = nationPolicy.reqNationRice,
            deltaSink = deltaSink,
        )
    }

    // ====================================================================================================
    // DIPLO SELECTION inputs (decision #11 / m10) — the SELECTION + boolean gate + draw stream IS P5 scope.
    //   The 3 do<한글> bodies hold the decision; the adapter only materialises the world-specific reads that
    //   are NOT plain logic columns (recv_assist KV / income / neighbor partition / capital BFS+Floyd score).
    // ====================================================================================================

    /**
     * The `do불가침제의` input (PHP `:1773-1812`): the `recv_assist`/`resp_assist`/`resp_assist_try` nation_env
     * KV (in foreach insertion order) + the income estimate at taxRate 15 over the supplyCities, + the
     * warTargetNation keys. ZERO draws — the body's selection is the deterministic income-quarter walk.
     */
    private fun buildDiploInput(
        general: TurnGeneral,
        nationId: Int,
        instance: AiInstanceState,
        worldView: AiWorldView,
        kvRecorder: AiKvRecorder,
        year: Int,
        month: Int,
    ): NationDiploFamily.DiploInput {
        val env = nationEnvSnapshot(nationId)
        val recvAssist = decodeAssistPairs(env["recv_assist"])
        val respAssist = decodeAssistMap(env["resp_assist"])
        val respAssistTry = decodeAssistMap(env["resp_assist_try"])
        // PHP `:1789` `key_exists($candNationID, $this->warTargetNation)` — the GeneralAI instance warTarget map.
        val warTargetKeys = (instance.warTargetNation ?: emptyMap()).keys.filter { it != 0 }.toSet()
        // PHP order (:1798-1811): the income estimate is computed ONLY AFTER the non-empty candidate check, so
        // when there is no recv_assist debt the body returns null at `:1798` BEFORE reading income — leaving
        // income unevaluated. Mirror that lazy order: skip the income fold (null) when recv_assist is empty.
        val income = if (recvAssist.isEmpty()) null else diploIncome(nationId, worldView)
        return NationDiploFamily.DiploInput(
            nationId = nationId,
            officerLevel = general.officerLevel,
            yearMonth = opensamguk.logic.actions.nation.NationCommand.joinYearMonth(year, month),
            recvAssist = recvAssist,
            respAssist = respAssist,
            respAssistTry = respAssistTry,
            warTargetNationKeys = warTargetKeys,
            income = income,
            recordNationKv = kvRecorder,
        )
    }

    /**
     * PHP `:1808-1811` — `getGoldIncome + getRiceIncome + getWallIncome` at taxRate 15 over the supplyCities,
     * or null when there are NO supplyCities (PHP `:1804-1806` → null). Reuses the GREEN income helpers; the
     * nation-type income fold is identity here (the nation-type module is not threaded — a faithful no-op for
     * the neutral/default nation type). The capital gets the capital factor.
     */
    private fun diploIncome(nationId: Int, worldView: AiWorldView): Int? {
        val supplyCities = worldView.supplyCities.values.map { it.city }
        if (supplyCities.isEmpty()) return null
        val nationRow = world.getNationById(nationId)
        val capital = nationRow?.capitalCityId ?: 0
        val level = nationRow?.level ?: 1
        val taxRate = 15.0 // PHP `:1808-1810` literal taxRate 15.
        val gold = getGoldIncome(supplyCities, capital, level, taxRate, null, pipeline)
        val rice = getRiceIncome(supplyCities, capital, level, taxRate, null, pipeline)
        val wall = getWallIncome(supplyCities, capital, level, taxRate, null, pipeline)
        return (gold + rice + wall).toInt() // PHP sums the three (the body compares with int-ish ratios).
    }

    /**
     * The `do선전포고` input (PHP `:1880-1951`): the avg-resource `trialProp ** 6`, the `lowTargetNations` set
     * (nations already at war with someone), the `getAllNationStaticInfo` `(id, level, power)` list, and the
     * `isNeighbor` predicate. The (A)(B)(C) draws live in the body on the shared rng.
     */
    private fun buildWarInput(
        nationId: Int,
        worldView: AiWorldView,
        instance: AiInstanceState,
        nationTech: Int,
        nationPolicy: AutorunNationPolicy,
        year: Int,
    ): NationDiploFamily.WarInput {
        // :1880-1911 — the avg gold/rice fold (npc full, user half) + nation gold/rice, divided by genCnt.
        var avgGold = 0.0
        var avgRice = 0.0
        var genCnt = 0
        for (gv in worldView.npcWarGenerals.values + worldView.npcCivilGenerals.values) {
            avgGold += gv.general.gold; avgRice += gv.general.rice; genCnt += 1
        }
        for (gv in worldView.userWarGenerals.values + worldView.userCivilGenerals.values) {
            avgGold += gv.general.gold / 2.0; avgRice += gv.general.rice / 2.0; genCnt += 1
        }
        val nationRow = world.getNationById(nationId)
        avgGold += nationRow?.gold ?: 0
        avgRice += nationRow?.rice ?: 0
        val trialPropPow6: Double = if (genCnt == 0) {
            0.0 // PHP `:1906-1908` genCnt==0 → null upstream; a 0 trialProp makes nextBool(0) a no-draw false.
        } else {
            avgGold /= genCnt
            avgRice /= genCnt
            var trialProp = avgGold / maxOf(nationPolicy.reqNPCWarGold * 1.5, 2000.0) // :1913
            trialProp += avgRice / maxOf(nationPolicy.reqNPCWarRice * 1.5, 2000.0) // :1914
            val devRate = warDevRate(worldView) // :1916 calcNationDevelopedRate.
            trialProp += ((devRate["pop"] ?: 0.0) + (devRate["all"] ?: 0.0)) / 2 // :1918
            trialProp /= 4 // :1920
            Math.pow(trialProp, 6.0) // :1921 trialProp ** 6.
        }

        // :1928-1932 — lowTargetNations = DISTINCT(me) FROM diplomacy WHERE me != nationID AND state IN (0,1).
        val lowTargetNations = world.listDiplomacy()
            .filter { it.fromNationId != nationId && (it.state == 0 || it.state == 1) }
            .map { it.fromNationId }
            .toSet()

        // :1936 — getAllNationStaticInfo: (nation, level, power) for ALL nations (insertion order).
        val allNationStaticInfo = world.listNations().map { Triple(it.id, it.level, it.power) }

        // The PK-ascending logic-city snapshot AiDistance.isNeighbor reads (R-FACADE §1 row order).
        val cityRows = world.listCities().sortedBy { it.id }.map { PerTurnOverlay.toLogicCity(it) }
        return NationDiploFamily.WarInput(
            trialPropPow6 = trialPropPow6,
            lowTargetNations = lowTargetNations,
            allNationStaticInfo = allNationStaticInfo,
            isNeighbor = { destNationId -> AiDistance.isNeighbor(nationId, destNationId, cityRows) },
        )
    }

    /** PHP `:1916` `$this->calcNationDevelopedRate()` over the supplyCities (ZERO draws — throwaway rng). */
    private fun warDevRate(worldView: AiWorldView): Map<String, Double> {
        val supplyInputs = worldView.supplyCities.values.map { cityDevelInput(it.city) }
        if (supplyInputs.isEmpty()) return emptyMap()
        return RatesPromoFamily.calcNationDevelopedRate(
            supplyInputs,
            opensamguk.common.rng.RandUtil(opensamguk.common.rng.LiteHashDrbg("aiWarDevRate")),
        )
    }

    /**
     * The `do천도` input (PHP `:1982-2073`): the capital-connected BFS nation-city set, the Floyd distance maps,
     * the per-city `pop * maxDist / sum(dist) * sqrt(dev)` score, the capital's one-step-closer path neighbours,
     * and the last천도Trial cooldown boolean. The single (dist>1) `choice` draw lives in the body.
     */
    private fun buildRelocateInput(
        general: TurnGeneral,
        nationId: Int,
        worldView: AiWorldView,
        kvRecorder: AiKvRecorder,
        year: Int,
        month: Int,
    ): NationDiploFamily.RelocateInput {
        val nationRow = world.getNationById(nationId)
        val capital = nationRow?.capitalCityId ?: 0
        val env = nationEnvSnapshot(nationId)

        // :2017-2053 — the capital-connected nation cities (BFS over own cities). We approximate the PHP
        // SplQueue walk by the GREEN BFS restricted to own-nation cities; the body only reads `count <= 1`.
        val nationCityIds = capitalConnectedNationCities(nationId, capital)

        // :2060 — Floyd distance over the capital-connected city list (DB-row / PK-asc order).
        val distanceList = AiDistance.searchAllDistanceByCityList(nationCityIds)

        // :2068-2073 — per-city score = pop * maxDist / sum(dist) * sqrt(dev), in nationCityIds order.
        val cityScores = LinkedHashMap<Int, Double>()
        for (cityId in nationCityIds) {
            val row = distanceList[cityId] ?: continue
            val sumDist = row.values.sum().toDouble()
            val maxDist = (row.values.maxOrNull() ?: 0).toDouble()
            val city = worldView.nationCities[cityId]
            val pop = (world.getCityById(cityId)?.population ?: 0).toDouble()
            val dev = city?.dev ?: 0.0
            val score = if (sumDist > 0) pop * maxDist / sumDist * kotlin.math.sqrt(maxOf(0.0, dev)) else 0.0
            cityScores[cityId] = score
        }

        // :2092-2099 — the capital's one-step-closer path neighbours (name order subset).
        val capitalPathNeighbors = CityConst.byId(capital)?.path?.keys?.toList() ?: emptyList()

        // :1995-2006 — the last천도Trial cooldown: a stored trial within turnTerm*30 sec AND the same level → block.
        val lastTrialBlocks = relocateLastTrialBlocks(env["last천도Trial"], general)

        return NationDiploFamily.RelocateInput(
            capital = capital,
            nationCityIds = nationCityIds,
            distanceList = distanceList,
            cityScores = cityScores,
            capitalPathNeighbors = capitalPathNeighbors,
            lastTrialBlocks = lastTrialBlocks,
            recordNationKv = kvRecorder,
            nationId = nationId,
            officerLevel = general.officerLevel,
            turnTime = general.turnTime.toString(),
        )
    }

    /** PHP `:2017-2053` — the capital-connected own-nation city ids (BFS over the own-nation subgraph). */
    private fun capitalConnectedNationCities(nationId: Int, capital: Int): List<Int> {
        if (capital == 0) return emptyList()
        val ownCities = world.listCities().filter { it.nationId == nationId }.map { it.id }.toHashSet()
        val out = LinkedHashSet<Int>()
        val queue = ArrayDeque<Int>()
        queue.addLast(capital)
        out.add(capital)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            val city = CityConst.byId(cur) ?: continue
            for (next in city.path.keys) { // name-order neighbours
                if (next in ownCities && next !in out) {
                    out.add(next)
                    queue.addLast(next)
                }
            }
        }
        return out.toList()
    }

    /** PHP `:1995-2006` — the last천도Trial DateInterval cooldown (within turnTerm*30 sec AND same level → block). */
    private fun relocateLastTrialBlocks(raw: Any?, general: TurnGeneral): Boolean {
        val pair = raw as? List<*> ?: return false
        val lastLevel = (pair.getOrNull(0) as? Number)?.toInt() ?: return false
        val lastTurnTimeStr = pair.getOrNull(1)?.toString() ?: return false
        val last = runCatching { java.time.Instant.parse(lastTurnTimeStr) }.getOrNull() ?: return false
        val diffSeconds = Duration.between(last, general.turnTime).seconds
        return diffSeconds < turnTerm.toLong() * 30 && lastLevel != general.officerLevel
    }

    /** PHP `nation_env['recv_assist']` → `(candNationID, amount)` pairs in foreach insertion order. */
    private fun decodeAssistPairs(raw: Any?): List<Pair<Int, Int>> {
        val list = raw as? List<*> ?: return emptyList()
        return list.mapNotNull { row ->
            val pair = row as? List<*> ?: return@mapNotNull null
            val cand = (pair.getOrNull(0) as? Number)?.toInt() ?: return@mapNotNull null
            val amount = (pair.getOrNull(1) as? Number)?.toInt() ?: return@mapNotNull null
            cand to amount
        }
    }

    /** PHP `nation_env['resp_assist'|'resp_assist_try']` → `"n{cand}" → [cand, value]` (insertion order). */
    private fun decodeAssistMap(raw: Any?): Map<String, List<Int>> {
        val map = raw as? Map<*, *> ?: return emptyMap()
        val out = LinkedHashMap<String, List<Int>>()
        for ((k, v) in map) {
            val vals = (v as? List<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: continue
            out[k.toString()] = vals
        }
        return out
    }

    /** PHP `$general` → the promotion candidate (the chief-gate scalars; stats are the `(F,F,F,F)` flavor). */
    private fun toPromotionCandidate(gv: AiGeneralView, nationTech: Int): RatesPromoFamily.PromotionCandidate {
        val statCalc = StatCalc(gv.general, pipeline)
        return RatesPromoFamily.PromotionCandidate(
            generalId = gv.general.id,
            officerLevel = gv.general.officerLevel,
            strength = AiSeed.promotionStat(statCalc, "strength"),
            intel = AiSeed.promotionStat(statCalc, "intel"),
            npcType = gv.general.npcType,
            killturn = gv.killturn,
            belong = gv.belong,
            dedication = gv.general.dedication,
        )
    }

    /**
     * S3 — the acting-city `cityDevelRate` map (PHP `Util::squeezeFromArray(calcCityDevelRate(city), 0)`, the
     * index-0 ratio per develKey). REUSE the GREEN [RatesPromoFamily.calcCityDevelRate]; ZERO decision-rng draws.
     */
    private fun cityDevelRateMap(city: opensamguk.logic.domain.City?): Map<String, Double> {
        if (city == null) return emptyMap()
        return throwawayCalcCityDevelRate(city).mapValues { it.value.score }
    }

    /**
     * S3 — the per-cityId `(develKey, develVal, develType)` triples the do내정워프 product walks (PHP
     * `calcCityDevelRate($city)`). The develType flag maps the [RatesPromoFamily.StatType] to the
     * [AiInstanceState] genType flag (LEADERSHIP→T_TONGSOLJANG=4, INTEL→T_JIJANG=2, STRENGTH→T_MUJANG=1).
     */
    private fun cityDevelRateTriples(cityId: Int): List<Triple<String, Double, Int>> {
        val city = world.getCityById(cityId)?.let { PerTurnOverlay.toLogicCity(it) } ?: return emptyList()
        return throwawayCalcCityDevelRate(city).map { (key, score) ->
            Triple(key, score.score, statTypeToFlag(score.statType))
        }
    }

    /** Build the [RatesPromoFamily.CityDevelInput] off a logic city + run the GREEN ratio calc (0 draws). */
    private fun throwawayCalcCityDevelRate(city: opensamguk.logic.domain.City): LinkedHashMap<String, RatesPromoFamily.DevelScore> {
        val input = cityDevelInput(city)
        // ZERO draws; the rng is only the 0-draw contract documenter (a throwaway stream, never the decision rng).
        return RatesPromoFamily.calcCityDevelRate(
            input,
            opensamguk.common.rng.RandUtil(opensamguk.common.rng.LiteHashDrbg("aiCityDevelRate")),
        )
    }

    private fun cityDevelInput(city: opensamguk.logic.domain.City): RatesPromoFamily.CityDevelInput =
        RatesPromoFamily.CityDevelInput(
            trust = city.trust,
            pop = city.population.toDouble(), popMax = maxOf(1.0, city.populationMax.toDouble()),
            agri = city.agriculture.toDouble(), agriMax = maxOf(1.0, city.agricultureMax.toDouble()),
            comm = city.commerce.toDouble(), commMax = maxOf(1.0, city.commerceMax.toDouble()),
            secu = city.security.toDouble(), secuMax = maxOf(1.0, city.securityMax.toDouble()),
            def = city.defense.toDouble(), defMax = maxOf(1.0, city.defenseMax.toDouble()),
            wall = city.wall.toDouble(), wallMax = maxOf(1.0, city.wallMax.toDouble()),
        )

    private fun statTypeToFlag(statType: RatesPromoFamily.StatType): Int = when (statType) {
        RatesPromoFamily.StatType.LEADERSHIP -> AiInstanceState.T_TONGSOLJANG // 4
        RatesPromoFamily.StatType.INTEL -> AiInstanceState.T_JIJANG // 2
        RatesPromoFamily.StatType.STRENGTH -> AiInstanceState.T_MUJANG // 1
    }

    /**
     * S6 — the do출병 `SELECT city, nation FROM city WHERE nation IN %li AND city IN %li` (PHP `:2759-2763`).
     * PK-ascending DB-row order (no ORDER BY). The body owns the INPUT order; the adapter runs the row query.
     */
    private fun attackableCitiesOf(nearCityIds: List<Int>, attackableNations: List<Int>): List<Int> {
        val near = nearCityIds.toHashSet()
        val nations = attackableNations.toHashSet()
        return world.listCities()
            .filter { it.nationId in nations && it.id in near }
            .sortedBy { it.id }
            .map { it.id }
    }

    /**
     * S8 — the do방랑군이동/do거병 `$occupiedCities` key set: lord cities (officer_level=12 AND city.nation=0)
     * ∪ nation cities (city.nation != 0). Membership-only (insertion not read), so a plain [Set].
     */
    private fun occupiedCitiesSet(): Set<Int> {
        val nationCities = world.listCities().filter { it.nationId != 0 }.map { it.id }
        val lordCities = world.listGenerals()
            .filter { it.officerLevel == 12 }
            .mapNotNull { g -> g.cityId.takeIf { world.getCityById(it)?.nationId == 0 } }
        return (nationCities + lordCities).toSet()
    }

    /** S11 — `count(nation WHERE gennum < initialNationGenLimit)`; gennum = #generals in the nation. */
    private fun notFullNationCount(): Int =
        world.listNations().count { nation ->
            world.listGenerals().count { it.nationId == nation.id } < GameConst.initialNationGenLimit
        }

    /** PHP do거병 `:3277` `$more = Util::valueFit(3 - year + init_year, 1, 3)`; init_year rides nation meta. */
    private fun foundDeadlineMore(nationId: Int, year: Int): Int {
        val initYear = (world.getNationById(nationId)?.meta?.get("init_year") as? Number)?.toInt() ?: startYear
        return (3 - year + initYear).coerceIn(1, 3)
    }

    /**
     * S16 — `onCalcDomestic('징집인구','score',100)` recruit-viability score (PHP `:585/681/981`). The GREEN
     * [GeneralActionPipeline.onCalcDomestic] left-folds the per-source modules over the seed `100.0`; with the
     * default (empty-module) pipeline the fold is the identity 100.0 (> 1 → the recruit-viability gate passes).
     * Threaded through the SAME ActionPipeline the resolve shares (NO decision-rng draw).
     */
    private fun recruitPopScoreOf(gv: AiGeneralView): Double =
        pipeline.onCalcDomestic(gv.general, "징집인구", "score", 100.0)

    /** S15 — `getCrewTypeObj()->costWithTech(nation['tech'], toInt(getLeadership(false)))` (PHP reward base). */
    private fun unitCostWithTechOf(gv: AiGeneralView, nationTech: Int): Double {
        val crewTypeId = gv.general.crewTypeId.takeIf { it >= 1000 } ?: UnitSetTable.DEFAULT_CREWTYPE
        val unit = UnitSetTable.byId(crewTypeId) ?: UnitSetTable.byId(UnitSetTable.DEFAULT_CREWTYPE)!!
        val leadership = gv.fullLeadership.toInt() // Util::toInt(getLeadership(false)) — trunc-toward-zero.
        return unit.costWithTech(nationTech, leadership)
    }

    // ====================================================================================================
    // S13 — do금쌀구매 trade ladder (PHP `:2367-2480`). ZERO RNG draws (decision #9) — a fully deterministic
    //   buy/sell ladder over the acting general's gold/rice + the city `trade` rate + the cost probe. The
    //   decision logic lives in PHP; the adapter only feeds the live resources + the deterministic arithmetic.
    // ====================================================================================================

    private fun tradeDecision(
        general: TurnGeneral,
        logicCity: opensamguk.logic.domain.City?,
        generalPolicy: AutorunGeneralPolicy,
        instance: AiInstanceState,
        statCalc: StatCalc,
        nationTech: Int,
    ): ChosenCommand? {
        // :2371 — city['trade'] null AND !can상인무시 → null (no market here).
        val tradeRate = logicCity?.trade
        if (tradeRate == null && !generalPolicy.can상인무시) return null

        // :2375-2377 — deathRate = (deathcrew+50000)/(killcrew+50000). The rank vars ride general meta
        // (rank_data LEFT JOIN → 0 when absent), so the default deathRate is 1.0.
        val kill = rankVar(general, "killcrew") + 50_000.0
        val death = rankVar(general, "deathcrew") + 50_000.0
        val deathRate = death / kill

        val absGold = general.gold.toDouble()
        val absRice = general.rice.toDouble()
        val relGold = absGold
        val relRice = absRice * deathRate

        // :2385-2387 — too poor to bother → null.
        if (absGold + absRice < instance.baseDevelCost * 2) return null

        // :2389-2406 — the cost probe (che_모병 when can모병 else che_징병) for `fullLeadership*100` of the
        // current crew type; goldCost = getCost()[0], riceCost = riceWithTech(tech, toInt(amount)).
        val fullLeadership = AiSeed.genTypeLeadership(statCalc)
        val probeAmount = fullLeadership * 100
        val crewTypeId = general.crewTypeId.takeIf { it >= 1000 } ?: UnitSetTable.DEFAULT_CREWTYPE
        val crewType = UnitSetTable.byId(crewTypeId) ?: UnitSetTable.byId(UnitSetTable.DEFAULT_CREWTYPE)!!
        val logicGeneral = PerTurnOverlay.toLogicGeneral(general)
        val costAlgo = if (generalPolicy.can모병) RecruitAlgorithm.cheMobyeong(pipeline) else RecruitAlgorithm.cheJingbyeong(pipeline)
        // PHP `$costCmd->getCost()[0]` — the gold cost of the probe over the APPLIED (post-cap) crew.
        val appliedCrew = appliedCrewForCost(logicGeneral, crewTypeId, probeAmount.toInt())
        val goldCost = costAlgo.getCost(logicGeneral, crewType, appliedCrew, nationTech).gold.toDouble()
        val riceCost = crewType.riceWithTech(nationTech, opensamguk.logic.util.phpToInt(probeAmount)) // :2403-2406

        // :2408-2410 — can't even afford 1.5× → null.
        if ((relGold + relRice) * 1.5 <= goldCost + riceCost) return null

        // :2413-2415 — a flush user general (npc<2) with 3× both → null (no need to trade).
        if (general.npcState < 2 && relGold >= goldCost * 3 && relRice >= riceCost * 3) return null

        val maxAmount = GameConst.maxResourceActionAmount // PHP `GameConst::$maxResourceActionAmount` (:2430/2461).
        val minAmount = instance.nationPolicy.minimumResourceActionAmount // PHP nationPolicy->minimumResourceActionAmount.
        // ZERO decision-rng draws: tradeAmount accepts an rng only to document the 0-draw contract; a throwaway
        // stream (NEVER the decision rng) keeps the shared "GeneralAI" cursor untouched (decision #9).
        val throwawayRng = opensamguk.common.rng.RandUtil(opensamguk.common.rng.LiteHashDrbg("aiTradeAmount"))

        // :2412-2445 — BUY rice (gold → rice). The condition split differs by can상인무시.
        var tryBuying = false
        if (generalPolicy.can상인무시) {
            if (relRice * 1.5 < relGold && relRice < riceCost * 2) tryBuying = true
            else if (relRice * 2 < relGold) tryBuying = true
        } else {
            if (relRice * 2 < relGold && relRice < riceCost * 3) tryBuying = true
        }
        if (tryBuying) {
            val amount = GenDomesticFamily.tradeAmount(relGold, relRice, deathRate, maxAmount, throwawayRng)
            if (amount >= minAmount) {
                val args = linkedMapOf<String, Any?>("buyRice" to true, "amount" to amount) // :2436-2439
                if (candidateAllowedHookForTrade(general, args)) return ChosenCommand(GenDomesticFamily.TRADE_ACTION, args)
            }
        }

        // :2447-2476 — SELL rice (rice → gold), symmetric.
        var trySelling = false
        if (generalPolicy.can상인무시) {
            if (relGold * 1.5 < relRice && relGold < goldCost * 2) trySelling = true
            else if (relGold * 2 < relRice) trySelling = true
        } else {
            if (relGold * 2 < relRice && relGold < goldCost * 3) trySelling = true
        }
        if (trySelling) {
            val amount = GenDomesticFamily.tradeAmount(relRice, relGold, deathRate, maxAmount, throwawayRng)
            if (amount >= minAmount) {
                val args = linkedMapOf<String, Any?>("buyRice" to false, "amount" to amount) // :2467-2470
                if (candidateAllowedHookForTrade(general, args)) return ChosenCommand(GenDomesticFamily.TRADE_ACTION, args)
            }
        }

        return null // :2480
    }

    // ====================================================================================================
    // S14 — do징병 recruit ladders (PHP `:2483-2651`). The armType/crewType SELECTION draws live in the
    //   GenDomesticFamily body on the SHARED rng; the adapter only materialises the candidate sets + the
    //   deterministic post-pick cost ladder (`recruitFinalize`). All ZERO decision-rng draws here.
    // ====================================================================================================

    /**
     * S14 — the preset `getAuxVar('armType')` AFTER the genType filter (PHP `:2526-2533`): a WIZARD preset is
     * dropped when not 지장; a FOOTMAN/ARCHER/CAVALRY preset is dropped when not 무장. null → the body's
     * `choiceUsingWeight($availableArmType)` draw fires over [recruitArmTypeWeights].
     */
    private fun recruitArmType(general: TurnGeneral, genType: Int): Int? {
        val armType = (auxVar(general, "armType") as? Number)?.toInt() ?: return null
        if (armType == 0) return null // PHP `if ($armType)` — falsy 0 → no preset.
        if ((genType and AiInstanceState.T_JIJANG) == 0 && armType == GameUnitConst.T_WIZARD) return null // :2528
        if ((genType and AiInstanceState.T_MUJANG) == 0 &&
            armType in listOf(GameUnitConst.T_FOOTMAN, GameUnitConst.T_ARCHER, GameUnitConst.T_CAVALRY)
        ) {
            return null // :2530
        }
        return armType
    }

    /**
     * S14 — the do징병 `$availableArmType` weight map (PHP `:2536-2552`) in FOOTMAN/ARCHER/CAVALRY/WIZARD
     * insertion order: `sqrt(dexN+500) * fullStrength` for the three physical types (when fullStrength >
     * fullIntel*0.9), then `sqrt(dex4+500) * fullIntel * 3` for WIZARD (when fullIntel > fullStrength*0.9).
     * Read ONLY when [recruitArmType] is null. ZERO draws.
     */
    private fun recruitArmTypeWeights(general: TurnGeneral, genType: Int, statCalc: StatCalc): List<Pair<Int, Double>> {
        val fullStrength = AiSeed.genTypeStrength(statCalc)
        val fullIntel = AiSeed.genTypeIntel(statCalc)
        fun dex(n: Int): Double = kotlin.math.sqrt(metaDouble(general, "dex$n") + 500.0)
        val out = ArrayList<Pair<Int, Double>>()
        if (fullStrength > fullIntel * 0.9) { // :2545
            out.add(GameUnitConst.T_FOOTMAN to dex(1) * fullStrength) // :2546
            out.add(GameUnitConst.T_ARCHER to dex(2) * fullStrength) // :2547
            out.add(GameUnitConst.T_CAVALRY to dex(3) * fullStrength) // :2548
        }
        if (fullIntel > fullStrength * 0.9) { // :2550
            out.add(GameUnitConst.T_WIZARD to dex(4) * fullIntel * 3) // :2551
        }
        return out
    }

    /**
     * S14 — the do징병 `$types` candidate map (PHP `:2571-2577`): for each `GameUnitConst::byType(armType)`
     * crew that `isValid` against the nation's owned cities/regions + tech + relYear, its `pickScore(tech)`,
     * in `byType` iteration order. The `choiceUsingWeight($types)` walk is the body's draw. ZERO draws here.
     */
    private fun recruitCrewScoresFor(
        general: TurnGeneral,
        nationId: Int,
        armType: Int,
        nationTech: Int,
        year: Int,
    ): List<Pair<Int, Double>> {
        // :2561-2567 — own cities/regions from `SELECT city, region, secu, level FROM city WHERE nation = nationID`.
        val ownCities = LinkedHashMap<Int, Int>() // cityId → level
        val ownRegions = LinkedHashSet<Int>()
        for (c in world.listCities().filter { it.nationId == nationId }) {
            ownCities[c.id] = c.level
            CityConst.byId(c.id)?.let { ownRegions.add(it.region) }
        }
        val relYear = maxOf(0, year - startYear) // :2568 valueFit(year - startyear, 0).
        val logicGeneral = PerTurnOverlay.toLogicGeneral(general)
        val nationAux = nationAuxSnapshot(nationId)
        val out = ArrayList<Pair<Int, Double>>()
        for (unit in GameUnitConst.byType(armType)) { // byType iteration order = the parity target.
            if (unitIsValid(unit, logicGeneral, ownCities, ownRegions, relYear, nationTech, nationAux)) {
                out.add(unit.id to unitPickScore(unit, nationTech)) // :2574-2575
            }
        }
        return out
    }

    /**
     * S14 — the do징병 deterministic post-pick cost ladder (PHP `:2585-2650`): the can고급병종 override, the
     * train/atmos gold/rice floor gate, the can모병 vs half-crew branch, the rice gate, and the
     * `hasFullConditionMet` gate. Returns the emitted `(che_징병|che_모병, RAW args)` or null. ZERO draws.
     */
    private fun recruitFinalize(
        general: TurnGeneral,
        chosenCrewTypeId: Int,
        generalPolicy: AutorunGeneralPolicy,
        statCalc: StatCalc,
        nationTech: Int,
        candidateAllowedHook: (String, Map<String, Any?>) -> Boolean,
    ): ChosenCommand? {
        val nationId = general.nationId
        val ownCities = LinkedHashMap<Int, Int>()
        val ownRegions = LinkedHashSet<Int>()
        for (c in world.listCities().filter { it.nationId == nationId }) {
            ownCities[c.id] = c.level
            CityConst.byId(c.id)?.let { ownRegions.add(it.region) }
        }
        val relYear = maxOf(0, world.getState().currentYear - startYear)
        val logicGeneral = PerTurnOverlay.toLogicGeneral(general)
        val nationAux = nationAuxSnapshot(nationId)

        var type = chosenCrewTypeId
        // :2585-2599 — can고급병종: keep the CURRENT crew type when it is still valid + high-tier.
        if (generalPolicy.can고급병종) {
            val currId = general.crewTypeId.takeIf { it >= 1000 }
            val currUnit = currId?.let { GameUnitConst.byId(it) }
            if (currUnit != null && unitIsValid(currUnit, logicGeneral, ownCities, ownRegions, relYear, nationTech, nationAux)) {
                val reqTech = (currUnit.reqConstraints.firstOrNull { it is UnitConstraint.ReqTech } as? UnitConstraint.ReqTech)?.reqTech
                if (reqTech != null) {
                    if (reqTech >= 2000) type = currUnit.id // :2591-2592
                    else if (currUnit.armType != recruitChosenArmType(chosenCrewTypeId) && reqTech >= 1000) type = currUnit.id // :2593-2595
                }
            }
        }

        // :2601-2609 — hard-coded train/atmos金 gold/rice floor gate.
        val fullLeadership = AiSeed.genTypeLeadership(statCalc)
        val gold = general.gold - fullLeadership * 3 // :2602-2603
        val rice = general.rice - fullLeadership * 4 // :2604-2605
        if (gold <= 0 || rice <= 0) return null // :2607-2609

        var crew: Double = fullLeadership * 100 // :2611
        val crewType = UnitSetTable.byId(type) ?: return null
        // :2614-2618 — the riceCost probe at the kill/death-scaled crew.
        val kill = rankVar(general, "killcrew")
        val deathDen = maxOf(rankVar(general, "deathcrew"), 1.0)
        var riceCost = crewType.riceWithTech(
            nationTech,
            opensamguk.logic.util.phpToInt(fullLeadership * 100 * kill / deathDen * 1.2),
        )

        // :2620-2625 — the che_징병 cost probe over the live crew.
        var action = GenDomesticFamily.RECRUIT_ACTION
        val costAlgo = RecruitAlgorithm.cheJingbyeong(pipeline)
        val applied0 = appliedCrewForCost(logicGeneral, type, crew.toInt())
        val cost = costAlgo.getCost(logicGeneral, crewType, applied0, nationTech).gold

        // :2627-2640 — can모병 (gold>=cost*6) → che_모병; else the half-crew fallback (gold<cost & gold*2>=cost).
        if (generalPolicy.can모병 && gold >= cost * 6) {
            action = GenDomesticFamily.RECRUIT_HIRE_ACTION // :2628
        } else if (gold < cost && gold * 2 >= cost) {
            crew *= 0.5 // :2633
            riceCost *= 0.5 // :2634
            crew = GenDomesticFamily.halfCrew(crew.toInt()).toDouble() // :2635 Util::round(crew-49, -2).
        }

        // :2642-2645 — the rice gate (only when !can한계징병).
        if (!generalPolicy.can한계징병 && rice * 1.1 <= riceCost) return null

        val crewAmount = crew.toInt()
        val args = linkedMapOf<String, Any?>("crewType" to type, "amount" to crewAmount) // :2620-2623/:2636-2639
        // :2647-2649 — the hasFullConditionMet gate.
        if (!candidateAllowedHook(action, args)) return null
        return ChosenCommand(action, args)
    }

    /** The chosen crew type's armType (PHP `$armType` used in the can고급병종 `armType != $armType` compare). */
    private fun recruitChosenArmType(crewTypeId: Int): Int =
        GameUnitConst.byId(crewTypeId)?.armType ?: -1

    /** PHP `GameUnitDetail::pickScore($tech)` (`GameUnitDetail.php:215-222`). */
    private fun unitPickScore(unit: GameUnitDetail, tech: Int): Double {
        var defaultWar = GameConst.armperphase + unit.attack + unit.defence +
            opensamguk.common.constants.getTechAbil(tech) * 2.0
        defaultWar *= 1 + unit.speed / 2.0
        defaultWar /= opensamguk.logic.util.valueFit(1 - unit.avoid / 100.0, 0.1)
        defaultWar *= 1 + unit.magicCoef / 2.0
        return defaultWar
    }

    /**
     * PHP `GameUnitDetail::isValid` (`GameUnitDetail.php:224-236`) — every `reqConstraint->test(...)` must pass.
     * The constraint `test` bodies are ported from the `GameUnitConstraint` package (the do징병-relevant subset:
     * ReqTech / ReqCities / ReqRegions / ReqMinRelYear / ReqChief / ReqNotChief / Impossible /
     * ReqCitiesWithCityLevel / ReqHighLevelCities / ReqNationAux).
     */
    private fun unitIsValid(
        unit: GameUnitDetail,
        general: opensamguk.logic.domain.General,
        ownCities: Map<Int, Int>,
        ownRegions: Set<Int>,
        relYear: Int,
        tech: Int,
        nationAux: Map<String, Any?>,
    ): Boolean {
        val effRelYear = maxOf(0, relYear) // :227 max(0, relativeYear).
        for (c in unit.reqConstraints) {
            val ok = when (c) {
                is UnitConstraint.Impossible -> false
                is UnitConstraint.ReqTech -> tech >= c.reqTech
                is UnitConstraint.ReqCities -> c.reqCities.any { name -> CityConst.byName(name)?.id?.let { ownCities.containsKey(it) } == true }
                is UnitConstraint.ReqRegions -> c.reqRegions.any { name -> (CityConst.regionMap[name] as? Int)?.let { ownRegions.contains(it) } == true }
                is UnitConstraint.ReqMinRelYear -> effRelYear >= c.reqMinRelYear
                is UnitConstraint.ReqChief -> general.officerLevel >= 5
                is UnitConstraint.ReqNotChief -> general.officerLevel < 5
                is UnitConstraint.ReqCitiesWithCityLevel ->
                    c.reqCities.any { name -> CityConst.byName(name)?.id?.let { id -> (ownCities[id] ?: -1) >= c.reqCityLevel } == true }
                is UnitConstraint.ReqHighLevelCities -> ownCities.values.count { it >= c.reqCityLevel } >= c.reqCityCount
                is UnitConstraint.ReqNationAux -> nationAuxCompare((nationAux[c.reqNationAuxKey] as? Number)?.toDouble() ?: 0.0, c.cmp, c.value)
            }
            if (!ok) return false
        }
        return true
    }

    private fun nationAuxCompare(lhs: Double, cmp: String, rhs: Double): Boolean = when (cmp) {
        "==" -> lhs == rhs
        "!=" -> lhs != rhs
        "<=" -> lhs <= rhs
        ">=" -> lhs >= rhs
        "<" -> lhs < rhs
        ">" -> lhs > rhs
        else -> false
    }

    /** PHP `$this->nation['aux']` — the nation's aux map (`meta['aux']`), defaulting empty. */
    private fun nationAuxSnapshot(nationId: Int): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        val aux = world.getNationById(nationId)?.meta?.get("aux") as? Map<String, Any?> ?: return emptyMap()
        return aux
    }

    /** PHP `getAuxVar(key)` — `general.meta['aux'][key]` (delete-on-null jsonb). */
    private fun auxVar(general: TurnGeneral, key: String): Any? {
        @Suppress("UNCHECKED_CAST")
        val aux = general.meta["aux"] as? Map<String, Any?> ?: return null
        return aux[key]
    }

    /** PHP `getRankVar(RankColumn::X)` — the rank_data value (LEFT JOIN → 0 when absent); rides general meta. */
    private fun rankVar(general: TurnGeneral, key: String): Double = metaDouble(general, key)

    private fun metaDouble(general: TurnGeneral, key: String): Double =
        (general.meta[key] as? Number)?.toDouble() ?: 0.0

    /**
     * PHP `che_징병.php:96-107` — the APPLIED (post-cap) crew for the cost probe: `valueFit(amount, 100,
     * getLeadership(true)*100 [- crew when same crewType])`. Mirrors [RecruitAlgorithm]'s private appliedCrew.
     */
    private fun appliedCrewForCost(general: opensamguk.logic.domain.General, reqCrewTypeId: Int, amount: Int): Int {
        val leadership = opensamguk.logic.stats.getStatValue(
            general, "leadership", pipeline, 255, withInjury = true, useFloor = true,
        ).toInt()
        var maxCrew = leadership * 100
        if (reqCrewTypeId == general.crewTypeId) maxCrew -= general.crew
        return opensamguk.logic.util.valueFit(amount.toDouble(), 100.0, maxCrew.toDouble()).toInt()
    }

    /** The F-BRIDGE trade gate (the acting general's canonical ctx), reused by [tradeDecision]. */
    private fun candidateAllowedHookForTrade(general: TurnGeneral, args: Map<String, Any?>): Boolean {
        val envMap = WorldEnvBuilder.envMap(world.getState().currentYear, startYear)
        return candidateAllowedHook(general.id, general.cityId, general.nationId, envMap)(
            GenDomesticFamily.TRADE_ACTION, args,
        )
    }

    /**
     * PHP `$this->env['killturn']` — the chooseNonLordPromotion/choosePromotion min-killturn base. The env
     * killturn is `4800 / turnterm` (ResetHelper.php:264); the world state meta overrides it when present.
     */
    private fun killturnEnv(): Int =
        (world.getState().meta["killturn"] as? Number)?.toInt() ?: (4800 / turnTerm.coerceAtLeast(1))

    /** PHP nation `chief_set` bitfield → the occupied chief levels set (decoded via the per-bit isOfficerSet). */
    private fun decodeChiefSet(nation: Nation?): Set<Int> {
        val raw = (nation?.meta?.get("chief_set") as? Number)?.toInt() ?: 0
        if (raw == 0) return emptySet()
        val out = LinkedHashSet<Int>()
        for (level in 1..12) {
            if ((raw shr level) and 1 != 0) out.add(level)
        }
        return out
    }

    // --- S2/S17 cutTurn (engine wall-clock; NOT a logic column) ---

    /**
     * The one-deploy-per-turn `cutTurn(getTurnTime(), turnterm)` formatted compare value (PHP H-HELPERS §2).
     * The exact second-floor formatting is the turn loop's job; here a stable lexicographic `Y-m-d H:i:s`
     * stamp off the general's turnTime suffices for the same-turn-bucket compare (a parity target the G-GATE
     * refines). Engine wall-clock math, NOT a logic-model column.
     */
    private fun cutTurn(turnTime: java.time.Instant): String = turnTime.toString()

    private fun turnTimeOf(gv: AiGeneralView): String {
        val tg = world.getGeneralById(gv.general.id) ?: return ""
        return cutTurn(tg.turnTime)
    }

    /** PHP `getTurnTime(TURNTIME_HM)` — the `HH:mm` stamp for the nation fail-log. */
    private fun turnTimeHm(turnTime: java.time.Instant): String {
        val s = turnTime.toString() // ISO-8601 ...THH:MM:SS...
        val tIdx = s.indexOf('T')
        return if (tIdx >= 0 && s.length >= tIdx + 6) s.substring(tIdx + 1, tIdx + 6) else ""
    }

    /** Build the read-only [GeneralAiInput] the spine branches on (PHP `$general->getVar(...)`). */
    private fun buildGeneralAiInput(
        general: TurnGeneral,
        generalPolicy: AutorunGeneralPolicy,
        year: Int,
        month: Int,
        rng: opensamguk.common.rng.RandUtil,
    ): GeneralAiInput {
        val nation = world.getNationById(general.nationId)
        val capital = nation?.capitalCityId != null && nation.capitalCityId != 0
        val initYear = (nation?.meta?.get("init_year") as? Number)?.toInt() ?: startYear
        val initMonth = (nation?.meta?.get("init_month") as? Number)?.toInt() ?: 1
        val relYearMonth = (year * 12 + month) - (initYear * 12 + initMonth)
        val npcMessageProb = GameConst.npcMessageFreqByDay * turnTerm / (60.0 * 24.0)
        return GeneralAiInput(
            generalId = general.id,
            npcType = general.npcState,
            nationId = general.nationId,
            officerLevel = general.officerLevel,
            injury = general.injury,
            npcmsg = general.meta["npcmsg"] as? String,
            capital = capital,
            relYearMonth = relYearMonth,
            can선양 = generalPolicy.can선양,
            can국가선택 = generalPolicy.can국가선택,
            cureThreshold = CURE_THRESHOLD,
            npcMessageProb = npcMessageProb,
            rng = rng,
        )
    }

    private fun resolveDef(code: String): GeneralActionDefinition = registry.resolve(code)

    // --- read-only world projections for the instance state (NO mutation) ---

    private fun toAiNationRow(n: Nation): AiNationRow = AiNationRow(
        nation = n.id,
        level = n.level,
        capital = n.capitalCityId ?: 0,
        gold = n.gold,
        rice = n.rice,
        type = n.typeCode,
        name = n.name,
    )

    /** The `nation_env` KV snapshot the instance reads (pre-turn; the deltas are visible NEXT turn). */
    private fun nationEnvSnapshot(nationId: Int): Map<String, Any?> =
        (world.getNationById(nationId)?.meta?.get("nation_env") as? Map<*, *>)
            ?.entries?.associate { (k, v) -> k.toString() to v } ?: emptyMap()

    /** PHP `SELECT you, state, term FROM diplomacy WHERE me=%i AND state IN (0,1)` (insertion order). */
    private fun diplomacyRowsFor(nationId: Int): List<AiDiplomacyRow> =
        world.listDiplomacy()
            .filter { it.fromNationId == nationId && (it.state == 0 || it.state == 1) }
            .map { AiDiplomacyRow(you = it.toNationId, state = it.state, term = it.term) }

    /** PHP `SELECT max(front) FROM city WHERE nation=%i AND supply=1` (`:230`). */
    private fun frontMaxFor(nationId: Int): Int =
        world.listCities()
            .filter { it.nationId == nationId && it.supplyState != 0 }
            .maxOfOrNull { it.frontState } ?: 0

    companion object {
        /** The 요양 injury threshold (PHP `$this->nationPolicy->cureThreshold`, `GeneralAI.php:3772`). */
        private const val CURE_THRESHOLD: Int = 30

        /** PHP `GameConst::$chiefStatMin` (d_setting/GameConst.php:11) — the chief strength/intel gate. */
        private const val CHIEF_STAT_MIN: Int = 65

        /** PHP `GameConst::$defaultStatNPCMax` (d_setting/GameConst.php:9) — the do거병 stat-midpoint base. */
        private const val DEFAULT_STAT_NPC_MAX: Int = 75
    }
}
