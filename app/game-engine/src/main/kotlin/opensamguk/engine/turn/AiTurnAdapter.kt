package opensamguk.engine.turn

import opensamguk.common.constants.GameConst
import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.ai.AiDiplomacyRow
import opensamguk.logic.ai.AiEnv
import opensamguk.logic.ai.AiInstanceState
import opensamguk.logic.ai.AiKvRecorder
import opensamguk.logic.ai.AiNationRow
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
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.ConstraintMode
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.stats.StatCalc
import opensamguk.logic.statview.WorldEnvBuilder

/**
 * P5 F-SEAM (Task FM1) — the SINGLE seam owner that bridges the daemon's in-memory world to the PURE
 * `:logic` [GeneralAI] decision spine.
 *
 * For ONE due general it:
 *  1. builds the SOLE per-general-per-decision `"GeneralAI"` [opensamguk.common.rng.RandUtil] via
 *     [AiSeed.rng][opensamguk.logic.ai.AiSeed.rng] (F-SEED; 5-component `serializeSeed(hidden,"GeneralAI",
 *     year,month,generalId)`), built ONCE and threaded BY REFERENCE through the whole decision — NEVER
 *     re-seeded (decision #1, the load-bearing parity contract);
 *  2. constructs the read-only [AiInstanceState] (`updateInstance`/`calcGenType` FIRST-draw) + [AiWorldView]
 *     (the categorize/derive facade) over the live [InMemoryTurnWorld] — READ-ONLY over GAME ENTITIES;
 *  3. wires the `candidateAllowed` F-BRIDGE gate over a [WorldStateViewAdapter] (FULL mode — the IDENTICAL
 *     gate [ReservedTurnHandler.handle] makes);
 *  4. routes the AI's meta-KV side-effects (`last_attackable`/`killturn`/`defence_train`/…) through the
 *     [AiKvRecorder] delta seam ([kvDeltas]) — NOT inline, NOT a module-static Map (decision #12 / M4);
 *  5. runs [GeneralAI.chooseGeneralTurn] and returns the chosen [ChosenCommand].
 *
 * **The AI is READ-ONLY over GAME ENTITIES** — this adapter never calls `world.updateGeneral`/`updateCity`
 * (nor the dirty-free apply); the chosen command's mutation runs the EXISTING [ReservedTurnHandler] resolve
 * → [ChangeRecorder] delta path. The only AI-side writes are the queued [kvDeltas] (visible NEXT turn).
 *
 * PURE construction over the in-memory world — NO JPA `EntityManager`, NO Spring-Data repo (the daemon
 * write-path architecture rule, enforced by `DaemonNoEntityManagerTest`).
 *
 * @param world the live in-memory turn world (read-only here).
 * @param registry the command registry (the F-BRIDGE gate resolves a `che_*` to its def — IDENTICAL to
 *   the handler's `registry.resolve`).
 * @param hiddenSeed the per-game `UniqueConst::$hiddenSeed` (the captured golden FIXTURE INPUT) — seed
 *   component 1 of the `"GeneralAI"` lineage.
 * @param startYear the scenario start year — `develCost = (year-startYear+10)*2` (the AI env).
 * @param turnTerm the env `turnterm` (minutes-per-turn) — the npcmsg prob + `calcRecentWarTurn` divisor.
 * @param pipeline the GREEN stat pipeline (the per-decision [StatCalc] shares it with the resolve).
 */
class AiTurnAdapter(
    private val world: InMemoryTurnWorld,
    private val registry: CommandRegistry,
    private val hiddenSeed: String,
    private val startYear: Int,
    private val turnTerm: Int = 1,
    private val pipeline: GeneralActionPipeline = GeneralActionPipeline(),
) {

    /**
     * The queued meta-KV deltas this decision produced (decision #12 / M4). The AI is read-only over GAME
     * ENTITIES; its only writes are these deltas. Insertion order = write order. Drained by the F-SEAM
     * caller into the [ChangeRecorder] meta path (a later FM task wires the recorder-backed sink).
     */
    val kvDeltas: List<KvDelta> get() = _kvDeltas.toList()
    private val _kvDeltas = ArrayList<KvDelta>()

    /** Whether [generalId] is AI-controlled (`npc >= 2`, PHP `$general->isNPC()`) — the hook gate. */
    fun isAiControlled(generalId: Int): Boolean =
        world.getGeneralById(generalId)?.let { ReservedTurnHandler.isAiControlled(it) } ?: false

    /**
     * Choose the AI command for [generalId], replacing the [reserved] one (R-SEAM §2 `:332`). Runs the
     * whole decision on the SOLE per-general `"GeneralAI"` rng (threaded by reference). The handler's
     * `aiHook` calls this; a non-AI general never reaches here (the handler gates on [isAiControlled]).
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
        val rng = opensamguk.logic.ai.AiSeed.rng(hiddenSeed, year, month, generalId)

        // The merged policies (F-POLICY). The 4-layer KV override (server/nation/aiOptions) is consumed
        // here; FM1 supplies the npcType+nationId branch (the live can-flags + the merged priority order).
        val generalPolicy = AutorunGeneralPolicy(npcType = npcType, nationId = nationId)
        val develCost = WorldEnvBuilder.envMap(year, startYear)["develCost"] as Int
        val nationPolicy = AutorunNationPolicy(npcType = npcType, tech = 0, develcost = develCost)

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

        // (3) the F-BRIDGE candidate gate over the FULL-mode WorldStateViewAdapter (IDENTICAL to the
        // handler's evaluateConstraints(FULL) call, fronted by argTest — decision #10).
        val overlay = PerTurnOverlay(world)
        val envMap = WorldEnvBuilder.envMap(year, startYear)
        val view = WorldStateViewAdapter(overlay, env = envMap)
        val candidateAllowedHook: (String, Map<String, Any?>) -> Boolean = { actionCode, rawArgs ->
            val ctx = ConstraintContext(
                actorId = generalId,
                cityId = general.cityId,
                nationId = nationId,
                env = envMap,
                mode = ConstraintMode.FULL,
            )
            candidateAllowed(actionCode, rawArgs, ctx, view) { code -> resolveDef(code) }
        }

        // The meta-KV general-side delta seam (decision #12) — killturn/defence_train route here, NOT
        // inline (the AI never writes a general row). The general-side KV is keyed by generalId via the
        // same KvDelta carrier (nationId field reused as the row id; the recorder-backed sink in a later
        // FM task disambiguates by scope).
        val recordGeneralKv: (Int, String, Any?) -> Unit = { gid, key, value ->
            _kvDeltas.add(KvDelta(gid, key, value))
        }

        // Eagerly run updateInstance() (NO draws — calcDiplomacyState etc.) so instance.dipState is
        // available to the world-view facade BELOW. The reqUpdateInstance guard makes the hook's second
        // updateInstance() call a no-op, so calcGenType (the FIRST draw) still fires exactly once on the
        // threaded rng inside the hook (the draw-order parity contract is preserved).
        instance.updateInstance()

        // The updateInstance prologue hook — re-asserts updateInstance() (now a guarded no-op) THEN runs
        // calcGenType (the FIRST draw, F-INSTANCE) on the threaded-by-reference rng. This is the very first
        // draw of the decision (GeneralAI.chooseGeneralTurn step 1).
        val statCalc = StatCalc(PerTurnOverlay.toLogicGeneral(general), pipeline)
        val updateInstanceHook: (opensamguk.common.rng.RandUtil) -> Unit = { r ->
            instance.updateInstance()
            instance.calcGenType(r, statCalc)
        }

        // F-FACADE — the read-only categorize/derive facade over the live nation cities (PK-ascending),
        // seeded with the derived dipState + the policy war thresholds. The do<한글> family bodies read it.
        val worldView = AiWorldView(
            ownNationId = nationId,
            // The facade reads logic-model city rows (engine → logic via the SAME overlay conversion).
            cityRows = world.listCities().filter { it.nationId == nationId }.map { PerTurnOverlay.toLogicCity(it) },
            warTargetNation = instance.warTargetNation,
            ownGeneralId = generalId,
            generals = emptyList(),
            dipState = instance.dipState,
            minWarCrew = nationPolicy.minWarCrew,
            minNpcWarLeadership = nationPolicy.minNPCWarLeadership,
            turnTerm = turnTerm,
        )

        // Build the per-general AI context the world-driven family bodies read (F-DISPATCH consumer seam).
        // Only the faithfully-derivable scalars are populated; the expensive pipeline/BFS-derived inputs
        // stay defaulted, which makes the bodies that need them null no-ops (the catalog-sanctioned dispatch
        // skip) until the gate harness (G-GATE) materialises them. READ-ONLY over GAME ENTITIES.
        val logicCity = world.getCityById(general.cityId)?.let { PerTurnOverlay.toLogicCity(it) }
        val ctx = GeneralAiContext(
            rng = rng,
            instance = instance,
            world = worldView,
            generalPolicy = generalPolicy,
            nationPolicy = nationPolicy,
            env = aiEnv,
            turnTerm = turnTerm,
            selfGeneralId = generalId,
            selfCityId = general.cityId,
            candidateAllowed = candidateAllowedHook,
            recordGeneralKv = recordGeneralKv,
            // with-injury flavor (PHP `$this->leadership = getLeadership()` — withInjury=true default).
            leadershipWithInjury = statCalc.getStatValue("leadership"),
            strengthWithInjury = statCalc.getStatValue("strength"),
            intelWithInjury = statCalc.getStatValue("intel"),
            // no-injury full flavor (G8) — REUSE the GREEN AiSeed genType table (do NOT re-round).
            fullLeadership = opensamguk.logic.ai.AiSeed.genTypeLeadership(statCalc),
            fullStrength = opensamguk.logic.ai.AiSeed.genTypeStrength(statCalc),
            fullIntel = opensamguk.logic.ai.AiSeed.genTypeIntel(statCalc),
            nationTech = 0,
            selfCrew = general.crew,
            selfCity = logicCity,
            cityDevelRate = cityDevelRateOf(logicCity),
            selfTrain = general.train.toDouble(),
            selfAtmos = general.atmos.toDouble(),
            selfGold = general.gold,
            selfRice = general.rice,
            selfNpcType = npcType,
            selfKillturn = ReservedTurnHandler.metaInt(general, "killturn", 0),
        )

        // Assemble the live GeneralAI through the SINGLE factory seam (the ASSEMBLE step). All 7 families'
        // world-driven do<한글> bodies are merged via GeneralAiDoBodies.fromFamilies(ctx); the bridge gate +
        // the meta-KV delta sink + the calcGenType-FIRST-draw prologue are wired through it. READ-ONLY over
        // GAME ENTITIES — the factory never mutates a row; the chosen command's resolve runs the EXISTING path.
        val bodies = GeneralAiDoBodies.fromFamilies(ctx)
        val ai = GeneralAiFactory.build(
            generalPolicy = generalPolicy,
            bodies = bodies,
            nationPolicy = nationPolicy,
            updateInstance = updateInstanceHook,
        )

        val input = buildGeneralAiInput(general, generalPolicy, year, month, rng)
        return ai.chooseGeneralTurn(reservedCommand = ChosenCommand(reserved.actionCode, ReservedTurnHandler.decodeArgs(reserved.argJson)), input = input)
    }

    /**
     * Choose the AI NATION command for the chief [generalId] (officer_level>=5), replacing the [reserved]
     * one (R-SEAM §2 `:305-308`). Builds the per-general `"GeneralAI"` rng + the read-only instance/world
     * facade + the merged nation policy, assembles the FULLY-WIRED [GeneralAI] (all 7 families' bodies) via
     * the factory, and runs [GeneralAI.chooseNationTurn]. The lifecycle's nation hook calls this; a human
     * chief (npc<2) never reaches here. READ-ONLY over GAME ENTITIES — meta-KV writes route through [kvDeltas].
     */
    fun chooseNationTurn(generalId: Int, reserved: ReservedTurn, lastTurn: LastTurn): ChosenCommand {
        val general = world.getGeneralById(generalId)
            ?: error("AiTurnAdapter: general $generalId not in world")
        val state = world.getState()
        val year = state.currentYear
        val month = state.currentMonth
        val nationId = general.nationId
        val npcType = general.npcState

        val rng = opensamguk.logic.ai.AiSeed.rng(hiddenSeed, year, month, generalId)
        val generalPolicy = AutorunGeneralPolicy(npcType = npcType, nationId = nationId)
        val develCost = WorldEnvBuilder.envMap(year, startYear)["develCost"] as Int
        val nationPolicy = AutorunNationPolicy(npcType = npcType, tech = 0, develcost = develCost)

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

        val overlay = PerTurnOverlay(world)
        val envMap = WorldEnvBuilder.envMap(year, startYear)
        val view = WorldStateViewAdapter(overlay, env = envMap)
        val candidateAllowedHook: (String, Map<String, Any?>) -> Boolean = { actionCode, rawArgs ->
            val ctx = ConstraintContext(
                actorId = generalId,
                cityId = general.cityId,
                nationId = nationId,
                env = envMap,
                mode = ConstraintMode.FULL,
            )
            candidateAllowed(actionCode, rawArgs, ctx, view) { code -> resolveDef(code) }
        }
        val recordGeneralKv: (Int, String, Any?) -> Unit = { gid, key, value ->
            _kvDeltas.add(KvDelta(gid, key, value))
        }

        val worldView = AiWorldView(
            ownNationId = nationId,
            cityRows = world.listCities().filter { it.nationId == nationId }.map { PerTurnOverlay.toLogicCity(it) },
            warTargetNation = instance.warTargetNation,
            ownGeneralId = generalId,
            generals = emptyList(),
            dipState = instance.dipState,
            minWarCrew = nationPolicy.minWarCrew,
            minNpcWarLeadership = nationPolicy.minNPCWarLeadership,
            turnTerm = turnTerm,
        )
        val ctx = GeneralAiContext(
            rng = rng,
            instance = instance,
            world = worldView,
            generalPolicy = generalPolicy,
            nationPolicy = nationPolicy,
            env = aiEnv,
            turnTerm = turnTerm,
            selfGeneralId = generalId,
            selfCityId = general.cityId,
            candidateAllowed = candidateAllowedHook,
            recordGeneralKv = recordGeneralKv,
        )
        // The nation reserved-honor gate (decision #4) is the SAME bridge gate the resolve makes (FULL mode).
        val bodies = GeneralAiDoBodies.fromFamilies(ctx).copy(
            hasFullConditionMet = { cmd -> candidateAllowedHook(cmd.actionCode, cmd.args) },
        )
        val ai = GeneralAiFactory.build(
            generalPolicy = generalPolicy,
            bodies = bodies,
            nationPolicy = nationPolicy,
            nationHooks = NationPassHooks(
                nationGeneralId = generalId,
                useAutoNationTurn = (general.meta["use_auto_nation_turn"] as? Number)?.toInt()?.let { it != 0 } ?: true,
            ),
        )

        val input = NationAiInput(npcType = npcType, officerLevel = general.officerLevel, month = month, rng = rng)
        return ai.chooseNationTurn(
            reservedCommand = ChosenCommand(reserved.actionCode, ReservedTurnHandler.decodeArgs(reserved.argJson)),
            lastTurn = lastTurn,
            input = input,
        )
    }

    /** The acting city's per-develKey ratios (PHP `calcCityDevelRate`, RatesPromoFamily) — the do일반내정/긴급내정
     *  candidate thresholds. Reuses the GREEN PURE primitive; empty when the city is unknown (재야/lost). */
    private fun cityDevelRateOf(city: opensamguk.logic.domain.City?): Map<String, Double> {
        if (city == null) return emptyMap()
        val input = opensamguk.logic.ai.families.RatesPromoFamily.CityDevelInput(
            trust = city.trust,
            pop = city.population.toDouble(), popMax = maxOf(1.0, city.populationMax.toDouble()),
            agri = city.agriculture.toDouble(), agriMax = maxOf(1.0, city.agricultureMax.toDouble()),
            comm = city.commerce.toDouble(), commMax = maxOf(1.0, city.commerceMax.toDouble()),
            secu = city.security.toDouble(), secuMax = maxOf(1.0, city.securityMax.toDouble()),
            def = city.defense.toDouble(), defMax = maxOf(1.0, city.defenseMax.toDouble()),
            wall = city.wall.toDouble(), wallMax = maxOf(1.0, city.wallMax.toDouble()),
        )
        // ZERO draws; the rng is only the 0-draw contract documenter (a throwaway stream, never the decision rng).
        val rate = opensamguk.logic.ai.families.RatesPromoFamily.calcCityDevelRate(
            input,
            opensamguk.common.rng.RandUtil(opensamguk.common.rng.LiteHashDrbg("aiCityDevelRate")),
        )
        return rate.mapValues { it.value.score }
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
        // joinYearMonth(year,month) - joinYearMonth(init_year,init_month); init_* default to startYear/1
        // when the nation has no founding stamp (the env math is F-INSTANCE/adapter territory; FM1 uses
        // the available founding meta or the scenario start as the faithful fallback).
        val initYear = (nation?.meta?.get("init_year") as? Number)?.toInt() ?: startYear
        val initMonth = (nation?.meta?.get("init_month") as? Number)?.toInt() ?: 1
        val relYearMonth = (year * 12 + month) - (initYear * 12 + initMonth)
        // PHP `:3719` npcMessageFreqByDay * turnterm / (60*24) (Double).
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
        /**
         * The 요양 injury threshold (PHP `$this->nationPolicy->cureThreshold`, `GeneralAI.php:3772`). The
         * policy default; the full policy-derived value is wired by the F-POLICY/F-INSTANCE consumers.
         */
        private const val CURE_THRESHOLD: Int = 30
    }
}
