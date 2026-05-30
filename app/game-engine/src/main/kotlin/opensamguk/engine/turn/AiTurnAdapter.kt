package opensamguk.engine.turn

import opensamguk.common.constants.CityConst
import opensamguk.common.constants.GameConst
import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.actions.GeneralActionDefinition
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
import opensamguk.logic.ai.families.RatesPromoFamily
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.ConstraintMode
import opensamguk.logic.domain.LastTurn
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

        val bodies = GeneralAiDoBodies.fromFamilies(ctx).copy(
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
