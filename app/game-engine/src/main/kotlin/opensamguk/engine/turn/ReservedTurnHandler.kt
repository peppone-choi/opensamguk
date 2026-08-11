package opensamguk.engine.turn

import opensamguk.common.constants.GameConst
import opensamguk.common.constants.ScenarioLifecycleMeta
import opensamguk.common.josa.JosaUtil
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.infra.persistence.GeneralTurnSlotWriteRow
import opensamguk.engine.war.BattleCommandContextBuilder
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.actions.develop.SightseeingExternalSelector
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.actions.founding.CheGeonguk
import opensamguk.logic.actions.founding.GeneralUniqueLotteryIntent
import opensamguk.logic.actions.founding.CheHaesan
import opensamguk.logic.actions.founding.CheSeonyang
import opensamguk.logic.actions.develop.CheGunryangMaemae
import opensamguk.logic.actions.military.CheSukryeonJeonhwan
import opensamguk.logic.actions.military.RecruitAlgorithm
import opensamguk.logic.actions.military.UnitSetTable
import opensamguk.logic.world.CityConstRegistry
import opensamguk.logic.actions.personnel.CheInjaeTamsaek
import opensamguk.logic.actions.personnel.CheRandomImgwan
import opensamguk.logic.actions.personnel.JoinCommand
import opensamguk.logic.actions.personnel.RandomImgwanNpcCandidate
import opensamguk.logic.actions.personnel.RandomImgwanPermutationReplay
import opensamguk.logic.actions.personnel.RandomImgwanWeightedCandidate
import opensamguk.logic.actions.trade.CheJangbiMaemae
import opensamguk.logic.actions.vote.deriveItemPool
import opensamguk.logic.actions.vote.giveRandomUniqueItem
import opensamguk.logic.actions.war.CheChulbyeong
import opensamguk.logic.domestic.uniqueLotterySeed
import opensamguk.logic.inheritance.InheritCatalog
import opensamguk.logic.ai.ChosenCommand
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.ConstraintMode
import opensamguk.logic.constraints.ConstraintResult
import opensamguk.logic.constraints.evaluateConstraints
import opensamguk.logic.diplomacy.DiplomacyCascadeTerm
import opensamguk.logic.domain.WorldEnv
import opensamguk.logic.domain.autorunLimit
import opensamguk.logic.event.EventTarget
import opensamguk.logic.statview.WorldEnvBuilder
import opensamguk.logic.tick.ServerClock
import opensamguk.logic.util.valueFit
import opensamguk.logic.war.ConquerAdmin
import opensamguk.logic.war.ConquerCity
import opensamguk.logic.war.ConquerCityInput
import opensamguk.logic.war.ProcessWarResult
import opensamguk.logic.util.phpRound
import opensamguk.logic.world.GeneralBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.sqrt
import opensamguk.logic.domain.City as LogicCity
import opensamguk.logic.domain.General as LogicGeneral
import opensamguk.logic.domain.Nation as LogicNation

private data class UniqueItemInfo(val name: String, val rawName: String)

private data class ResolvedCommandExecution(
    val actionCode: String,
    val definition: GeneralActionDefinition,
    val context: GeneralActionResolveContext,
)

private data class AlternativeCommandSpec(
    val actionCode: String,
    val args: Map<String, Any?> = emptyMap(),
)

private val UNIQUE_ITEM_CATALOG: Map<String, UniqueItemInfo> =
    InheritCatalog.availableUnique().mapValues { (_, item) ->
        UniqueItemInfo(
            name = item.getValue("title"),
            rawName = item.getValue("rawName"),
        )
    }

/**
 * P1 Task F3 — the daemon-side reserved-turn handler.
 *
 * For ONE due general it runs the full PHP `TurnExecutionHelper` pipeline collapsed to the P1 slice:
 *
 *   resolve action-code → **full** constraints (the SAME `:logic` library) → seed per-action RNG →
 *   resolve (mutate a logic draft) → [ChangeRecorder] diff → **dirty-free** apply to the world + logs.
 *
 * Faithful-port anchors:
 *  - **One constraint library:** the deny/allow decision uses [evaluateConstraints] over a
 *    [WorldStateViewAdapter] in `mode=FULL` — the EXACT constraints the game-api precheck evaluates
 *    (Task E2), only the row source differs. On Deny/Unknown the turn falls back to 휴식
 *    ([CommandRegistry.fallback]) and pushes the deny-reason log (PHP `getFailString()` path).
 *  - **One env-builder:** the full-mode `ConstraintContext.env` (`year`/`startYear`/`develCost`) is
 *    built by [WorldEnvBuilder] — the SAME single helper E2's `PrecheckStateViewFactory` uses, so the
 *    precheck and full-mode env can NEVER drift (P1 review-edit #7).
 *  - **Seed (PHP grand truth `TurnExecutionHelper.php:340-347`):** six-component
 *    `serializeSeed(hiddenSeed, "generalCommand", year, month, generalId, definition.key)` where
 *    component 2 is the LITERAL string `"generalCommand"` and component 6 is the short command class
 *    name (= the registry key `che_상업투자`/`che_농지개간`). `hiddenSeed` is the per-game
 *    `UniqueConst::$hiddenSeed` captured from the golden game (a FIXTURE INPUT, NOT a constant).
 *  - **Single dirty source:** the resolver mutates a [GeneralActionDraft] only; the world is updated
 *    through [InMemoryTurnWorld.applyGeneralDirtyFree]/[InMemoryTurnWorld.applyCityDirtyFree], and the
 *    [ChangeRecorder] is the ONLY thing that marks a row dirty (design Risk #4).
 */
class ReservedTurnHandler(
    private val world: InMemoryTurnWorld,
    private val registry: CommandRegistry,
    /** Per-game `UniqueConst::$hiddenSeed` — the captured golden FIXTURE INPUT (seed component 1). */
    private val hiddenSeed: String,
    /** The starting year of the scenario — `develCost = (year - startYear + 10) * 2`. */
    private val startYear: Int,
    /**
     * The scenario number (`env['scenario']`, PHP `che_거병.php:93`) — selects the founding `secretlimit`
     * (`>= 1000` ⇒ 1, the live-server branch; else 3). Threaded into the founding preload args. Default 0
     * for the P1–P4 / test call sites that never found a nation. See [DaemonLoopConfig] for the live thread.
     */
    private val scenario: Int = 0,
    /**
     * `$gameStor->turnterm` — 게임의 분/턴 간격(per-game 설정). 외교 제의 명령(불가침/종전/불가침파기)의
     * 서신 validUntil = date + max(30, turnterm*3)분 공식([opensamguk.logic.actions.nation.DiplomacySeam])에
     * 쓰이도록 [GeneralActionResolveContext.turnterm]로 흘려보낸다. startYear/scenario/hiddenSeed와 동일한
     * per-game 생성자 주입 패턴(per-call이 아님) — handle()의 시그니처를 넓히지 않는다. 기본 60(prod 기본값)이라
     * P1–P4/테스트 호출부는 source-compatible. [DaemonLoopConfig]가 라이브에서 실제 turnterm을 주입한다.
     */
    private val turnTerm: Int = 60,
    /**
     * Succession hook for a dying ruler (`General.php:554-558` → `func.php:1807 nextRuler`). The full
     * candidate-selection + 후계 promotion + possible `deleteNation` cascade is RNG-driven (the
     * `NextNPCRuler` seed) and is wired by the G1 gate; here it is a pluggable hook so kill() can apply
     * the ruler's own `officer_level=1` demotion and delegate succession. Default = no-op (no heir).
     */
    private val nextRuler: (generalId: Int, env: LifecycleEnv) -> Unit = { _, _ -> },
    /**
     * Dying-message provider (`General.php:573-580` → `TextDecoration\DyingMessage`). The RNG-selected
     * variant is wired by the G1 gate; the default is the byte-exact PHP `$defaultMessage`
     * (`<Y>;name;</>;이; <R>사망</>했습니다.`) with the JosaUtil `이` substitution.
     */
    private val dyingMessage: (TurnGeneral) -> String = ReservedTurnHandler::defaultDyingMessage,
    /**
     * P5 FM1 — the GENERAL-pass AI interpose (R-SEAM §2 `TurnExecutionHelper.php:332-336`).
     *
     * For an autorun-eligible general ([isAutorunEligible]: `npc >= 2` or an active human window), this hook
     * REPLACES the reserved `(actionCode, argJson)` with the AI's `chooseGeneralTurn(...)` result
     * BEFORE the constraint/resolve runs; when the chosen command differs from the reserved one,
     * `autorunMode` is set true (the PHP `if ($cmd !== $newCmd) { $autorunMode = true; … }` branch).
     *
     * **The AI is READ-ONLY over GAME ENTITIES** — it returns `(actionCode, RAW args)`; the chosen
     * command's mutation runs the EXISTING resolve→[ChangeRecorder] delta path, and the AI's meta-KV
     * side-effects route through the [AiTurnAdapter]'s [opensamguk.logic.ai.AiKvRecorder] delta seam,
     * NOT inline and NOT a module-static Map (decision #12 / M4). No new entity write path.
     *
     * Default `null` = no AI (the P1–P4 general/E2E call sites stay on the human reserved path).
     */
    private val aiHook: ((generalId: Int, reserved: ReservedTurn) -> ChosenCommand)? = null,
    private val pipelineBuilder: EngineGeneralActionPipelineBuilder? = null,
    private val dynamicEventHandler: (EventTarget) -> Unit = { },
    private val randomImgwanPermutationReplay: RandomImgwanPermutationReplay? = null,
    private val sightseeingExternalSelector: SightseeingExternalSelector? = null,
    private val actionRngFactory: (String) -> RandUtil = { seed -> RandUtil(LiteHashDrbg(seed)) },
    /**
     * The lone dirty source — exposed so the flush (F4)/tests/nation-pass read its patches. A
     * constructor param (default = fresh) so the live config can share ONE recorder with the ruler-
     * succession handler wired into [nextRuler] (the succession + the reserved turns must diff into the
     * SAME recorder — P2 Risk #4 single-dirty-source).
     */
    val recorder: ChangeRecorder = ChangeRecorder(),
) {

    /** Outcome of resolving one general's reserved turn (for the lifecycle/test to inspect). */
    data class HandledTurn(
        val generalId: Int,
        /** Resolved definition (the requested action, or [CommandRegistry.fallback] when denied). */
        val definition: GeneralActionDefinition,
        /** True when constraints denied/unknown and the turn fell back to 휴식. */
        val fellBack: Boolean,
        /** The deny reason (PHP `getFailString()`), or null on an allowed turn. */
        val denyReason: String?,
        /** Logs pushed for this turn (the action log, or the deny-reason log on fallback). */
        val logs: List<String>,
        /** The full-mode `ConstraintContext.env` map used (env-parity oracle for the test). */
        val env: Map<String, Any?>,
        /**
         * The PARSED reserved-arg map that threaded into the resolver draft ctx (R-SEAM §1; FM1). The
         * `general_turn.arg`/`nation_turn.arg` jsonb decoded once, fed to [GeneralActionResolveContext].
         * Empty for a no-arg command. Surfaced for the parity oracle (the seed never uses the arg).
         */
        val args: Map<String, Any?> = emptyMap(),
        /**
         * PHP `$autorunMode` (`TurnExecutionHelper.php:333-336`) — true ONLY when the [aiHook] replaced
         * the reserved command with a DIFFERENT one. Threads into [applyKillturnDecrement]'s autorun
         * branch. False when the reserved command is honored verbatim.
         */
        val autorunMode: Boolean = false,
        val requestId: String? = null,
        val reservedActionCode: String? = null,
    )

    /**
     * Handle ONE reserved turn for [generalId] (the FLAT-action-code overload — kept for the P1–P4
     * call sites that do not carry an arg payload; delegates to the widened [ReservedTurn] overload).
     */
    fun handle(generalId: Int, actionCode: String, year: Int, month: Int, date: String): HandledTurn =
        handle(generalId, ReservedTurn(actionCode = actionCode, argJson = ""), year, month, date)

    /**
     * Handle ONE reserved turn for [generalId] (R-SEAM §1 — the widened seam carrying `argJson`).
     *
     * The flow (R-SEAM §2 `TurnExecutionHelper.php:326-348`): for an autorun-eligible general the [aiHook]
     * REPLACES the reserved command BEFORE constraints/resolve (`autorunMode` set on change); then the
     * stored `arg` jsonb is decoded ONCE and threaded into the resolver draft ctx. **The seed's 6th
     * component is `definition.key` — NEVER the arg — so this widening is behavior-additive on the
     * resolver side only (RNG-seed parity unaffected, R-SEAM §1).**
     *
     * @param reserved the reserved `(actionCode, argJson)` from the `general_turn` ring / enqueued cmd.
     * @param year the game year (RNG seed component 3 + cost env).
     * @param month the game month (RNG seed component 4).
     * @param date the turn-time `HH:MM` for the action log `<1>date</>` suffix.
     */
    fun handle(generalId: Int, reserved: ReservedTurn, year: Int, month: Int, date: String): HandledTurn {
        val general = world.getGeneralById(generalId)
            ?: error("ReservedTurnHandler: general $generalId not in world")
        val cityId = general.cityId
        val nationId = general.nationId
        val baseRegistry = pipelineBuilder?.registryFor(general) ?: registry
        val runtimeRegistry = sightseeingExternalSelector?.let(baseRegistry::withSightseeingExternalSelector) ?: baseRegistry

        // --- the GENERAL-pass AI interpose (R-SEAM §2 :332-336) ---
        var actionCode = reserved.actionCode
        var args: Map<String, Any?> = decodeArgs(reserved.argJson)
        var autorunMode = false
        if (aiHook != null && isAutorunEligible(general, year, month)) {
            val chosen: ChosenCommand = aiHook.invoke(generalId, reserved)
            if (chosen.actionCode != reserved.actionCode) {
                // PHP `if ($cmd !== $newCmd) { $autorunMode = true; $cmd = $newCmd; }` (:333-336).
                autorunMode = true
                actionCode = chosen.actionCode
                args = chosen.args // the AI emits RAW args (the chosen command's own arg payload).
            }
            // A chosen command equal to the reserved one is honored verbatim (autorunMode stays false),
            // keeping the human-reserved arg map decoded above.
        }

        // ONE env, built by THE shared env-builder (same call as E2 precheck — cannot drift).
        val state = world.getState()
        val phase = state.currentPhase.coerceIn(1, GameConst.phasesPerMonth)
        val env = LinkedHashMap(WorldEnvBuilder.commandEnvMap(year, startYear, month, phase))
        env["ownCities"] = world.listCities()
            .filter { it.nationId == nationId }
            .sortedBy { it.id }
            .associateTo(LinkedHashMap()) { it.id to it.level }
        env["unitSet"] = UnitSetTable.activeUnitSet(state.config, state.meta)
        env["mapName"] = CityConstRegistry.activeMapName(state.config, state.meta)
        val worldEnv: WorldEnv = WorldEnvBuilder.worldEnv(year, startYear)

        val baseDefinition = resolveRuntimeDefinition(runtimeRegistry, actionCode, general, year)
        val parsedArgs = runCatching { baseDefinition.parseArgsForGeneral(args, generalId) }.getOrNull()
        if (parsedArgs == null || !baseDefinition.matchesArgsSchema(parsedArgs)) {
            world.pushLog(denyLog(general, baseDefinition, INVALID_ARGS_DENY_REASON, month, date))
            return HandledTurn(
                generalId = generalId,
                definition = runtimeRegistry.fallback,
                fellBack = true,
                denyReason = INVALID_ARGS_DENY_REASON,
                logs = listOf(INVALID_ARGS_DENY_REASON),
                env = env,
                args = args,
                autorunMode = autorunMode,
            )
        }
        val normalizedArgs = parsedArgs
        val actionArgs = augmentGeneralActionArgs(actionCode, normalizedArgs, general, year)
        val definition = baseDefinition.bindArgs(actionArgs)

        // --- FULL-mode constraints over the live world (the SAME :logic constraint library) ---
        // dest-* 제약(ExistsDestNation/ExistsDestGeneral/Allow·DisallowDiplomacyBetweenStatus 등)은
        // ctx.destGeneralId/destCityId/destNationId를 읽으므로, 최종 args 맵(예약 디코드 또는 AI 교체 후)에서
        // 동일 키 3종을 ctx로 흘려준다 — AiTurnAdapter:397-399와 EXACTLY 동일. 이걸 누락하면 인간-예약
        // 외교 수락(불가침/종전/파기 수락)이 id 0에 대해 평가되어 무조건 Deny → 휴식 폴백으로 떨어진다.
        val overlay = PerTurnOverlay(world)
        val ctx = ConstraintContext(
            actorId = generalId,
            cityId = cityId,
            nationId = nationId,
            destGeneralId = intArg(actionArgs, "destGeneralID"),
            destCityId = intArg(actionArgs, "destCityID"),
            destNationId = intArg(actionArgs, "destNationID"),
            args = actionArgs,
            env = env,
            mode = ConstraintMode.FULL,
        )
        val view = WorldStateViewAdapter(overlay, env = env, args = actionArgs)
        val result = evaluateConstraints(definition.buildConstraints(ctx), ctx, view)

        if (result !is ConstraintResult.Allow) {
            // Deny / Unknown → 휴식 fallback + the deny-reason log (PHP getFailString()).
            val reason = when (result) {
                is ConstraintResult.Deny -> result.reason
                is ConstraintResult.Unknown -> UNKNOWN_DENY_REASON
                ConstraintResult.Allow -> null // unreachable
            }
            if (reason != null) world.pushLog(denyLog(general, definition, reason, month, date))
            return HandledTurn(
                generalId = generalId,
                definition = runtimeRegistry.fallback,
                fellBack = true,
                denyReason = reason,
                logs = if (reason != null) listOf(reason) else emptyList(),
                env = env,
                args = normalizedArgs,
                autorunMode = autorunMode,
            )
        }

        // --- seed the per-action RNG (six-component PHP construction) ---
        val rng = actionRngFactory(
            serializeSeed(hiddenSeed, "generalCommand", year, month, generalId, definition.key),
        )

        val currentCity = world.getCityById(cityId)
        if (currentCity == null) {
            val isRest = definition.key == runtimeRegistry.fallback.key
            return HandledTurn(
                generalId = generalId,
                definition = if (isRest) definition else runtimeRegistry.fallback,
                fellBack = !isRest,
                denyReason = null,
                logs = emptyList(),
                env = env,
                args = normalizedArgs,
                autorunMode = autorunMode,
            )
        }

        // --- resolve over a logic draft (the Immer-draft replacement) ---
        val preGeneral: LogicGeneral = PerTurnOverlay.toLogicGeneral(general)
        val preCity: LogicCity = PerTurnOverlay.toLogicCity(currentCity)
        val nation = world.getNationById(nationId)?.let { PerTurnOverlay.toLogicNation(it) }

        val draft = GeneralActionDraft(preGeneral, preCity, nation)
        // Thread the parsed reserved/AI-chosen args into the resolver draft ctx (R-SEAM §1; FM1). The
        // seed above already keyed on definition.key — the arg never touches the RNG construction.
        //
        // FOUNDING preload (거병/건국/cr_건국/무작위건국): augment `args` with the PHP-query substitutes the
        // resolver expects (newNationId placeholder, existing-nation set, scenario). These are pure DB-query
        // stand-ins — they consume NOTHING from the action rng (GeobyeongTest proves a fresh rng yields the
        // same first draw post-resolve). Built ONLY after constraints Allow (we are past the early-return),
        // so a denied 거병 never allocates an id. The actor name is threaded so the created nation's name is
        // the actor's and general/global logs can use the same actor name PHP's General object exposes.
        // HandledTurn.args keeps the ORIGINAL args (the parity oracle) — the founding preload never pollutes it.
        val isFounding = actionCode in FOUNDING_COMMANDS
        val resolveArgs = when {
            isFounding -> buildFoundingArgs(actionCode, actionArgs, general, year, month)
            actionCode == HAESAN -> buildSameMonthGuardArgs(actionArgs, general, year, month)
            actionCode == INJAE_TAMSAEK -> buildScoutArgs(actionArgs, year, month)
            else -> actionArgs
        }
        preloadDraftTargets(actionCode, draft, resolveArgs)
        if (actionCode == HAESAN) {
            preloadDisbandCascade(draft, nationId, generalId)
        }
        val battleContext = if (actionCode == "che_출병") {
            val destCityId = intArg(actionArgs, "destCityID")
                ?: error("che_출병 passed constraints without destCityID")
            BattleCommandContextBuilder.build(
                world = world,
                attackerGeneralId = generalId,
                finalTargetCityId = destCityId,
                hiddenSeed = hiddenSeed,
                loggerYear = year,
                loggerMonth = month,
                pipelineFor = pipelineBuilder?.let { builder -> { g -> builder.pipelineFor(g) } },
            )
        } else {
            null
        }
        val destGeneralName = intArg(actionArgs, "destGeneralID")?.let { world.getGeneralById(it)?.name }.orEmpty()
        val resolveCtx = GeneralActionResolveContext(
            draft, rng, worldEnv, month, date,
            args = resolveArgs,
            generalName = general.name,
            destGeneralName = destGeneralName,
            // 외교 제의 서신 validUntil(= date + max(30, turnterm*3)분) 공식이 읽는 per-game turnterm.
            turnterm = turnTerm,
            // 무작위건국: rng.choice가 소모하는 도시 id 목록. PHP `SELECT city FROM city WHERE level>=5 AND level<=6 AND nation=0`
            // 의 기본 정렬(= id 오름차순)이므로 id-ascending으로 정렬해 draw-for-draw 패러티를 유지한다.
            candidateGenerals = if (actionCode == MUJAKWI_GEONGUK || actionCode == IDONG) {
                world.listGenerals()
                    .filter { it.nationId == nationId && it.id != generalId }
                    .map { PerTurnOverlay.toLogicGeneral(it) }
            } else emptyList(),
            candidateCityIds = if (actionCode == MUJAKWI_GEONGUK) {
                world.listCities()
                    .filter { it.nationId == 0 && it.level in 5..6 }
                    .map { it.id }
                    .sorted()
            } else emptyList(),
            battleContext = battleContext,
        )
        definition.resolve(resolveCtx)
        val executions = mutableListOf(ResolvedCommandExecution(actionCode, definition, resolveCtx))
        val successfulExecution = resolveAlternativeChain(
            registry = runtimeRegistry,
            first = executions.single(),
            draft = draft,
            rng = rng,
            worldEnv = worldEnv,
            month = month,
            date = date,
            general = general,
            cityId = cityId,
            nationId = nationId,
            year = year,
            env = env,
            executions = executions,
        )
        val resolveContexts = executions.mapTo(mutableListOf()) { it.context }
        backfillRandomImgwanDestNation(actionCode, draft, preGeneral)
        for (execution in executions) {
            when (val executedDefinition = execution.definition) {
                is CheChulbyeong -> drainWarBattleResult(executedDefinition.lastBattleResult, draft)
                is CheInjaeTamsaek -> {
                    drainScoutNpc(executedDefinition.lastBuiltNpc)
                    recordScoutInheritance(executedDefinition.lastBuiltNpc, general.userId, execution.context.args)
                }
            }
            recordCommandInheritance(execution.definition, general, draft.general)
        }
        val rareSaleHistory = rareSaleHistoryLog(actionCode, resolveArgs, general, nation, year, month)
        successfulExecution?.let { execution ->
            uniqueLotteryIntent(execution.actionCode, execution.definition, execution.context)
                ?.let { consumeUniqueLottery(it, draft, execution.context) }
        }

        // che_해산 exposes deleteNation(func.php:1713-1805) through its tombstone seam. Capture the
        // snapshot before applying the draft's general/city neutralization so ng_old_nations keeps the
        // pre-delete member list, matching the ruler-death deleteNation path.
        for (execution in executions) {
            val executedDefinition = execution.definition
            if (executedDefinition is CheHaesan) {
                executedDefinition.lastDeletedNationId?.let { deletedNationId ->
                    recorder.markNationDeleted(world, deletedNationId)
                }
            }
        }

        // --- ChangeRecorder = the SINGLE dirty source ---
        recorder.diffGeneral(preGeneral, draft.general)
        val postCity = effectivePostCity(actionCode, draft.city)
        val enginePostCity = world.getCityById(postCity.id)
        if (enginePostCity != null) {
            recorder.diffCity(PerTurnOverlay.toLogicCity(enginePostCity), postCity)
        } else {
            recorder.diffCity(preCity, postCity)
        }

        // --- dirty-free apply: write the post-state engine rows; ChangeRecorder owns dirtiness ---
        world.applyGeneralDirtyFree(applyGeneralPatch(general, draft.general))
        draft.destGeneral?.let { destG ->
            if (destG.id != generalId) {
                val pre = world.getGeneralById(destG.id)
                    ?: error("ReservedTurnHandler: dest general ${destG.id} not in world")
                recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(pre), destG)
                world.applyGeneralDirtyFree(applyGeneralPatch(pre, destG))
            }
        }
        enginePostCity?.let { world.applyCityDirtyFree(applyCityPatch(it, postCity)) }
        draft.destCity?.takeIf { it.id != postCity.id }?.let { destCity ->
            val pre = world.getCityById(destCity.id)
                ?: error("ReservedTurnHandler: dest city ${destCity.id} not in world")
            recorder.diffCity(PerTurnOverlay.toLogicCity(pre), destCity)
            world.applyCityDirtyFree(applyCityPatch(pre, destCity))
        }

        // --- 외교 cascade 적용 (외교 수락/파기 등 nation-command가 일반 패스로 들어온 경우) ---
        // 외교 수락(불가침/종전/파기 수락)은 NationCommand로서 양방향 diplomacy 행을 draft.cascadeDiplomacy에
        // 누적한다. 일반 패스도 이 cascade를 ChangeRecorder 단일 dirty 소스를 통해 적용해야 행이 실제 전환된다
        // (ProcessNationCommand.dispatchRegistered의 diplomacyDeltas 적용 패턴과 동일). state/term/dead만
        // 전환되는 UPSERT이며, 사전 행이 없으면(현재 슬라이스 범위 밖) 건너뛴다.
        //
        // term encoding: 급습(term<0 relative) / 이호경식(IF state=0 → 3 else term+3) 은 absolute 가 아니다.
        // [DiplomacyCascadeTerm.apply] 가 pre-row 기준으로 PHP UPDATE 결과를 전개한다.
        for (delta in draft.cascadeDiplomacy) {
            val pre = world.getDiplomacy(delta.me, delta.you) ?: continue
            val applied = DiplomacyCascadeTerm.apply(
                preState = pre.state,
                preTerm = pre.term,
                deltaState = delta.state,
                deltaTerm = delta.term,
            )
            world.updateDiplomacy(delta.me, delta.you, applied.state, applied.term)
            val post = world.getDiplomacy(delta.me, delta.you) ?: continue
            recorder.diffDiplomacy(pre, post)
        }

        // --- founding / cascade write-set drain (거병 INSERTs + 건국/이동/집합 UPDATEs) ---
        // Without this, the founding nation/diplomacy/nation_turn INSERTs and the roaming-leader/follower
        // moves the resolver staged would silently vanish at flush (the live-daemon prod data-loss seam).
        // The ChangeRecorder stays the SINGLE dirty source; applyNationPatch/applyGeneralPatch/applyCityPatch
        // update the world read-state dirty-free (design Risk #4).

        // nation UPDATE (건국/cr_건국/무작위건국 level 0→1 + name/color/type/capital). A non-founding command
        // never reassigns draft.nation, so it stays the pre-state reference (referential no-op → skipped).
        val resolvedNation = draft.nation
        if (nation != null && resolvedNation != null && resolvedNation !== nation) {
            val postNation = resolvedNation.copy(tech = materializeMariaDbFloat(resolvedNation.tech))
            recorder.diffNation(nation, postNation)
            world.getNationById(nationId)?.let { world.applyNationDirtyFree(applyNationPatch(it, postNation)) }
        }
        draft.destNation?.takeIf { it.id != nationId }?.let { destNation ->
            val pre = world.getNationById(destNation.id)
                ?: error("ReservedTurnHandler: dest nation ${destNation.id} not in world")
            val postNation = destNation.copy(tech = materializeMariaDbFloat(destNation.tech))
            recorder.diffNation(PerTurnOverlay.toLogicNation(pre), postNation)
            world.applyNationDirtyFree(applyNationPatch(pre, postNation))
        }
        // cascade generals (무작위건국 follower moves; 이동 roaming-leader / 집합 troop members — these were
        // previously DROPPED: the handler only drained cascadeDiplomacy). The actor itself is NOT here (it is
        // draft.general, already diffed above) — the resolver appends only the OTHER moved generals.
        for (movedGeneral in draft.cascadeGenerals) {
            val pre = world.getGeneralById(movedGeneral.id) ?: continue
            recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(pre), movedGeneral)
            world.applyGeneralDirtyFree(applyGeneralPatch(pre, movedGeneral))
        }
        // cascade cities (방랑 / 무작위 city reverts — defensive; empty for 거병).
        for (movedCity in draft.cascadeCities) {
            val pre = world.getCityById(movedCity.id) ?: continue
            recorder.diffCity(PerTurnOverlay.toLogicCity(pre), movedCity)
            world.applyCityDirtyFree(applyCityPatch(pre, movedCity))
        }
        for (execution in executions) {
            val executedDefinition = execution.definition
            if (executedDefinition is CheChulbyeong) {
                drainConquerCity(executedDefinition.lastBattleResult, general, year, month)
            }
        }
        for (rankIncrement in draft.rankIncrements) {
            val column = RankColumn.byColumn(rankIncrement.column) ?: continue
            recorder.recordRankIncrease(rankIncrement.generalId, column, rankIncrement.value)
        }
        // CREATED-set (거병 INSERTs). Ordering is LOAD-BEARING: nation FIRST (the FK target + the frozen
        // step-3 contract general→nation→troop→diplomacy), then diplomacy, then nation_turn. The world's
        // LinkedHashSet/list preserves the enqueue order through consumeDirtyState.
        for (createdNation in draft.createdNations) world.createNation(PerTurnOverlay.toEngineNation(createdNation))
        for (createdDip in draft.createdDiplomacy) world.createDiplomacy(PerTurnOverlay.toEngineDiplomacy(createdDip))
        for (createdNationTurn in draft.createdNationTurns) world.createNationTurn(createdNationTurn)

        // --- logs ---
        for (ctx in resolveContexts) {
            for (event in ctx.orderedLogEvents().sortedBy(::phpActionLoggerFlushRank)) {
                world.pushLog(logEvent(general, event))
                if (rareSaleHistory != null && isRareSaleGlobalAction(event)) {
                    world.pushLog(globalHistoryLog(general, rareSaleHistory))
                }
            }
        }

        // --- buffered Messages → mailbox channel (receiver row BEFORE sender row) ---
        for (ctx in resolveContexts) {
            for (message in ctx.messages()) routeMessage(message, year, month)
        }

        if (executions.any { it.definition is CheHaesan && it.definition.lastDeletedNationId != null }) {
            dynamicEventHandler(EventTarget.OCCUPY_CITY)
        }

        return HandledTurn(
            generalId = generalId,
            definition = definition,
            fellBack = false,
            denyReason = null,
            logs = resolveContexts.flatMap { it.logs() },
            env = env,
            args = normalizedArgs,
            autorunMode = autorunMode,
        )
    }

    private fun phpActionLoggerFlushRank(event: GeneralActionResolveContext.BufferedLog): Int = when {
        event.scope == "general" && event.category == "history" -> 0
        event.scope == "general" && event.category == "action" -> 1
        event.scope == "nation" && event.category == "history" -> 4
        event.scope == "global" && event.category == "history" -> 5
        event.scope == "global" && event.category == "action" -> 6
        else -> 7
    }

    private fun drainWarBattleResult(result: ProcessWarResult?, draft: GeneralActionDraft) {
        if (result == null) return
        for ((generalId, increments) in result.rankIncrements) {
            for ((battleColumn, value) in increments) {
                val column = RankColumn.byColumn(battleColumn.column) ?: continue
                recorder.recordRankIncrease(generalId, column, value)
            }
        }
        draft.general = result.attacker.state.snapshot()
        result.attackerNationTech?.let { tech ->
            draft.nation = draft.nation?.copy(tech = tech)
        }

        if (result.attackerCityDeadDelta != 0) {
            draft.city = draft.city.copy(dead = draft.city.dead + result.attackerCityDeadDelta)
        }

        val defenderCityPost = result.city.state.snapshot().copy(
            dead = result.city.state.snapshot().dead + result.defenderCityDeadDelta,
        )
        if (defenderCityPost.id == draft.city.id) {
            draft.city = defenderCityPost
        } else {
            draft.destCity = defenderCityPost
        }

        for (defender in result.defenders) {
            val post = defender.state.snapshot()
            if (post != defender.getGeneral()) {
                draft.cascadeGenerals.add(post)
            }
        }

        val defenderNationId = result.city.state.city.nationId
        val rice = result.defenderNationRice
        if (defenderNationId > 0 && rice != null) {
            val pre = world.getNationById(defenderNationId)?.let { PerTurnOverlay.toLogicNation(it) }
            if (pre != null) {
                draft.destNation = pre.copy(
                    rice = rice,
                    tech = result.defenderNationTech ?: pre.tech,
                )
            }
        } else if (defenderNationId > 0) {
            val defenderNationTech = result.defenderNationTech
            if (defenderNationTech != null) {
                val pre = world.getNationById(defenderNationId)?.let { PerTurnOverlay.toLogicNation(it) }
                if (pre != null) draft.destNation = pre.copy(tech = defenderNationTech)
            }
        }

        applyBattleDiplomacyCasualty(
            fromNationId = draft.general.nationId,
            toNationId = defenderNationId,
            delta = result.attackerDiplomacyCasualtyDelta,
        )
        applyBattleDiplomacyCasualty(
            fromNationId = defenderNationId,
            toNationId = draft.general.nationId,
            delta = result.defenderDiplomacyCasualtyDelta,
        )
    }

    private fun applyBattleDiplomacyCasualty(fromNationId: Int, toNationId: Int, delta: Int) {
        if (delta == 0) return
        val pre = world.getDiplomacy(fromNationId, toNationId) ?: return
        world.updateDiplomacy(
            fromNationId,
            toNationId,
            pre.state,
            pre.term,
            pre.dead + delta,
        )
        world.getDiplomacy(fromNationId, toNationId)?.let { recorder.diffDiplomacy(pre, it) }
    }

    private fun drainConquerCity(result: ProcessWarResult?, attacker: TurnGeneral, year: Int, month: Int) {
        if (result?.conquerCity != true) return
        val cityId = result.city.state.city.id
        val defenderCity = world.getCityById(cityId) ?: return
        val defenderNationId = defenderCity.nationId
        val defenderNation = if (defenderNationId > 0) world.getNationById(defenderNationId) else null
        val postBattleGenerals = result.defenders.associate { it.getGeneral().id to it.state.snapshot() } +
            mapOf(result.attacker.getGeneral().id to result.attacker.state.snapshot())
        val logicGenerals = world.listGenerals().map { g ->
            postBattleGenerals[g.id] ?: PerTurnOverlay.toLogicGeneral(g)
        }
        val defenderCityGenerals = logicGenerals
            .filter { it.nationId == defenderNationId && it.cityId == cityId }
            .sortedBy { it.id }
        val defenderNationGenerals = logicGenerals
            .filter { it.nationId == defenderNationId }
            .sortedBy { it.id }
        val logicCities = world.listCities().map { PerTurnOverlay.toLogicCity(it) }
        val logicNations = world.listNations().associate { it.id to PerTurnOverlay.toLogicNation(it) }
        val attackerNation = logicNations[attacker.nationId]
        val conquer = ConquerCity.resolve(
            ConquerCityInput(
                admin = ConquerAdmin(
                    hiddenSeed = hiddenSeed,
                    year = year,
                    month = month,
                    joinMode = world.getState().meta["join_mode"]?.toString() ?: "",
                ),
                attacker = PerTurnOverlay.toLogicGeneral(world.getGeneralById(attacker.id) ?: attacker),
                defenderCity = PerTurnOverlay.toLogicCity(defenderCity),
                defenderNation = defenderNation?.let { PerTurnOverlay.toLogicNation(it) },
                attackerNation = attackerNation,
                defenderCityGenerals = defenderCityGenerals,
                defenderNationCityCount = world.listCities().count { it.nationId == defenderNationId },
                defenderNationGenerals = defenderNationGenerals,
                allCitiesForBfs = logicCities,
                diplomacyForFront = world.listDiplomacy().map { PerTurnOverlay.toLogicDiplomacy(it) },
                attackerNationName = world.getNationById(attacker.nationId)?.name ?: "",
                attackerGeneralName = attacker.name,
                attackerNationChiefIds = world.listGenerals()
                    .filter { it.nationId == attacker.nationId && it.officerLevel >= 5 }
                    .map { it.id },
                defenderNationName = defenderNation?.name ?: "",
                cityName = defenderCity.name,
                nationNames = world.listNations().associate { it.id to it.name },
            ),
            occupyCityHandler = { dynamicEventHandler(EventTarget.OCCUPY_CITY) },
        )

        conquer.deletedNationId?.let { recorder.markNationDeleted(world, it) }
        for (delta in conquer.generalDeltas) applyWarGeneralDelta(delta.post)
        for (delta in conquer.nationDeltas) applyWarNationDelta(delta.post)
        for (delta in conquer.cityDeltas) applyWarCityDelta(delta.post)
        for (front in conquer.frontResults) {
            for ((frontCityId, frontState) in front.fronts) {
                val pre = world.getCityById(frontCityId) ?: continue
                applyWarCityDelta(PerTurnOverlay.toLogicCity(pre).copy(frontState = frontState))
            }
        }
        if (conquer.destroyNationEvent) {
            dynamicEventHandler(EventTarget.DESTROY_NATION)
        }
        for (invite in conquer.scoutInvites) {
            val source = world.getGeneralById(invite.sourceGeneralId) ?: continue
            val destination = world.getGeneralById(invite.destinationGeneralId) ?: continue
            val sourceNation = world.getNationById(source.nationId) ?: continue
            val sourceTarget = opensamguk.logic.message.MessageTarget(
                source.id,
                source.name,
                source.nationId,
                sourceNation.name,
                sourceNation.color,
            )
            val destinationTarget = opensamguk.logic.message.MessageTarget(
                destination.id,
                destination.name,
                destination.nationId,
                "재야",
                "#000000",
            )
            routeMessage(
                opensamguk.logic.message.Message(
                    opensamguk.logic.message.MessageType.PRIVATE,
                    sourceTarget,
                    destinationTarget,
                    "${sourceNation.name}${JosaUtil.pick(sourceNation.name, "로")} 망명 권유 서신",
                    CONQUER_MESSAGE_TIME_FORMAT.format(world.getState().lastTurnTime),
                    "9999-12-31 12:59:59",
                    linkedMapOf("action" to "scout"),
                ),
                year,
                month,
            )
        }
        for (line in conquer.conquerLogs) {
            world.pushLog(
                LogEntryDraft(
                    scope = line.scope.wireValue,
                    category = line.category.wireValue,
                    text = line.text,
                    generalId = line.generalId,
                    nationId = line.nationId,
                ),
            )
        }
        for (slot in conquer.turnSlotWrites) {
            recorder.recordGeneralTurnSlotWrite(
                GeneralTurnSlotWriteRow(
                    generalId = slot.generalId,
                    turnIdx = slot.turnIdx,
                    actionCode = slot.actionCode,
                    argJson = slot.argJson,
                    brief = slot.brief,
                ),
            )
        }
    }

    private fun applyWarGeneralDelta(post: LogicGeneral) {
        val pre = world.getGeneralById(post.id) ?: return
        recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(pre), post)
        world.applyGeneralDirtyFree(applyGeneralPatch(pre, post))
    }

    private fun applyWarCityDelta(post: LogicCity) {
        val pre = world.getCityById(post.id) ?: return
        recorder.diffCity(PerTurnOverlay.toLogicCity(pre), post)
        world.applyCityDirtyFree(applyCityPatch(pre, post))
    }

    private fun applyWarNationDelta(post: LogicNation) {
        val pre = world.getNationById(post.id) ?: return
        recorder.diffNation(PerTurnOverlay.toLogicNation(pre), post)
        world.applyNationDirtyFree(applyNationPatch(pre, post))
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // B4 — the post-command lifecycle tail (TurnExecutionHelper.php:47-230, General.php:515-639).
    //
    // The engine [TurnGeneral] is the P0-B/P1 slice: `npcState` is the `npc` column and `age` is a
    // column; the remaining lifecycle scalars ride the `meta` bag (`killturn`/`block`/`deadyear`/
    // `owner`/`owner_name`/`npc_org`/`lived_month`/`specage`/`specage2`/`dex1..5`) — the same
    // convention `UpdateNationLevel` reads `meta["killturn"]`. These methods mutate the world's
    // stored row in place; the per-general drain owns the flush seam.
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    private fun applyLifecycleGeneral(pre: TurnGeneral, post: TurnGeneral) {
        recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(pre), PerTurnOverlay.toLogicGeneral(post))
        world.applyGeneralDirtyFree(post)
    }

    internal fun setAutorunLimit(generalId: Int, limit: Int) {
        val general = world.getGeneralById(generalId)
            ?: error("ReservedTurnHandler.setAutorunLimit: general $generalId not in world")
        applyLifecycleGeneral(general, general.copy(meta = withMeta(general.meta, "autorun_limit" to limit)))
    }

    fun preprocessGeneral(generalId: Int, year: Int, month: Int) {
        val general = world.getGeneralById(generalId)
            ?: error("ReservedTurnHandler.preprocessGeneral: general $generalId not in world")
        val hasMedicalSpecialty =
            general.role.specialDomestic == "che_event_의술" ||
                general.role.specialWar == "che_의술"
        if (!hasMedicalSpecialty) {
            if (general.injury == 0) return
            applyLifecycleGeneral(general, general.copy(injury = maxOf(0, general.injury - 10)))
            return
        }

        if (general.injury > 0) {
            applyLifecycleGeneral(general, general.copy(injury = 0))
            world.pushLog(actionLog(general, "<C>●</><C>의술</>을 펼쳐 스스로 치료합니다!"))
        }
        val rng = RandUtil(
            LiteHashDrbg(serializeSeed(hiddenSeed, "preprocess", year, month, generalId)),
        )
        val curedPatients = world.listGenerals()
            .asSequence()
            .filter { patient ->
                patient.id != generalId &&
                    patient.cityId == general.cityId &&
                    patient.injury > 10 &&
                    (general.nationId != 0 || patient.nationId == 0)
            }
            .sortedBy { it.id }
            .filter { rng.nextBool(0.5) }
            .toList()
        for (patient in curedPatients) {
            applyLifecycleGeneral(patient, patient.copy(injury = 0))
            val josaYi = JosaUtil.pick(general.name, "이")
            world.pushLog(
                actionLog(patient, "<C>●</><Y>${general.name}</>$josaYi <C>의술</>로써 치료해줍니다!"),
            )
        }
        if (curedPatients.isNotEmpty()) {
            val lastPatient = curedPatients.last()
            val text = if (curedPatients.size == 1) {
                val josaUl = JosaUtil.pick(lastPatient.name, "을")
                "<C>●</><C>의술</>을 펼쳐 도시의 장수 <Y>${lastPatient.name}</>$josaUl 치료합니다!"
            } else {
                "<C>●</><C>의술</>을 펼쳐 도시의 장수들 <Y>${lastPatient.name}</> 외 " +
                    "<C>${curedPatients.size - 1}</>명을 치료합니다!"
            }
            world.pushLog(actionLog(general, text))
        }
    }

    /**
     * killturn decrement / reset after a command runs (`processCommand`, `TurnExecutionHelper.php:153-165`).
     *
     * `increaseVarWithLimit('killturn', -1)` (no floor — killturn CAN cross below zero, the kill gate
     * in [updateTurnTime] then reads it) when ANY of: NPCType>=2 OR killturn>baseline OR autorunMode
     * OR commandClassName=='휴식'. ELSE `setVar('killturn', baseline)` (a human running a real command
     * at-or-under baseline resets the clock).
     */
    fun applyKillturnDecrement(generalId: Int, commandClassName: String, env: LifecycleEnv) {
        val general = world.getGeneralById(generalId)
            ?: error("ReservedTurnHandler.applyKillturnDecrement: general $generalId not in world")
        val killturn = metaInt(general, "killturn", 0)
        val next = if (
            general.npcState >= 2 ||
            killturn > env.baselineKillturn ||
            env.autorunMode ||
            commandClassName == REST_COMMAND
        ) {
            killturn - 1 // increaseVarWithLimit('killturn', -1) — NO floor (LazyVarUpdater.php:80).
        } else {
            env.baselineKillturn // setVar('killturn', $killTurn).
        }
        applyLifecycleGeneral(general, general.copy(meta = withMeta(general.meta, "killturn" to next)))
    }

    /**
     * The block branch (`processBlocked`, `TurnExecutionHelper.php:47-70`). `block<2` is a no-op
     * returning false. `block==2|3` → `increaseVarWithLimit('killturn', -1, 0)` (FLOORED at 0) + push
     * the matching block action log, then return true so the caller SKIPS the command this turn.
     */
    fun processBlocked(generalId: Int, env: LifecycleEnv): Boolean {
        val general = world.getGeneralById(generalId)
            ?: error("ReservedTurnHandler.processBlocked: general $generalId not in world")
        val blocked = metaInt(general, "block", 0)
        if (blocked < 2) return false
        val date = env.turnTimeHm
        val message = when (blocked) {
            2 -> "현재 멀티, 또는 비매너로 인한<R>블럭</> 대상자입니다. <1>$date</>"
            3 -> "현재 악성유저로 분류되어 <R>블럭</> 대상자입니다. <1>$date</>"
            else -> return false // Hmm? (PHP fall-through guard).
        }
        val killturn = metaInt(general, "killturn", 0)
        val next = maxOf(0, killturn - 1) // increaseVarWithLimit('killturn', -1, 0) — floored at 0.
        applyLifecycleGeneral(general, general.copy(meta = withMeta(general.meta, "killturn" to next)))
        world.pushLog(actionLog(general, message))
        return true
    }

    /**
     * `updateTurnTime` (`TurnExecutionHelper.php:170-230`): +1 lived_month inheritance, the killturn<=0
     * kill/possession-release gate (LC2), the age>=retirementYear rebirth gate (LC3), then advance
     * `turntime = addTurn(turntime, turnTerm)`. Returns the [LifecycleOutcome] the caller inspects.
     *
     * NOTE: a KILLED general is dropped from the world (the F3 tombstone) — its turntime is NOT
     * advanced (PHP `return`s out of updateTurnTime right after kill, `:204`).
     */
    fun updateTurnTime(generalId: Int, env: LifecycleEnv): LifecycleOutcome {
        val general = world.getGeneralById(generalId)
            ?: error("ReservedTurnHandler.updateTurnTime: general $generalId not in world")

        world.getAccessLog(generalId)?.let {
            recorder.recordAccessLogUpsert(world, it.copy(refreshScore = 0))
        }

        val myset = minOf(
            GameConst.maxDefSettingChange,
            metaInt(general, "myset", 6) + GameConst.incDefSettingChange,
        )
        // +1 lived_month inheritance (`:278` increaseInheritancePoint(lived_month, 1) is in the drain;
        // the inheritance accumulator rides meta in the slice).
        val livedMonth = metaInt(general, "lived_month", 0) + 1
        var current = general.copy(meta = withMeta(general.meta, "myset" to myset, "lived_month" to livedMonth))
        applyLifecycleGeneral(general, current)

        // 삭턴장수 삭제처리 — killturn<=0 (LC2: kill() / possession-release).
        val killturn = metaInt(current, "killturn", 0)
        if (killturn <= 0) {
            val outcome = killOrReleasePossession(current, env)
            if (outcome == LifecycleOutcome.KILLED) return outcome // PHP returns out of updateTurnTime.
            // possession-release falls through to advance turntime.
            current = world.getGeneralById(generalId)!!
        }

        // 은퇴 — age>=retirementYear, human only (LC3: rebirth()). PHP `TurnExecutionHelper.php:209-216`
        // gates only the pre-rebirth applyDB+CheckHall call on isunited==0; rebirth itself runs in both
        // state 0 and the transient state 1. The daemon freezes state 2|3 before this drain.
        var rebirthed = false
        if (current.age >= GameConst.retirementYear && current.npcState == 0) {
            rebirth(current, env)
            rebirthed = true
            current = world.getGeneralById(generalId)!!
        }

        // advance turntime by addTurn (the nextTurnTimeBase aux variant is out of the slice scope).
        applyLifecycleGeneral(
            current,
            current.copy(turnTime = ServerClock.addTurn(current.turnTime, env.turnTerm, 1)),
        )
        return when {
            rebirthed -> LifecycleOutcome.REBIRTHED
            killturn <= 0 -> LifecycleOutcome.POSSESSION_RELEASED
            else -> LifecycleOutcome.SURVIVED
        }
    }

    /**
     * killturn<=0 branch of [updateTurnTime] (`TurnExecutionHelper.php:185-206`).
     *  - NPCType==1 & deadyear>year → 유체이탈 possession release (a NON-delete branch): push the global
     *    log FIRST, then legacy `killturn=(deadyear-year)*12` converted to three phase turns,
     *    `npc=npc_org, owner=0, user_id=null, defence_train=80,
     *    owner_name=null`, then DELETE general_access_log and Kotlin `general_owner`.
     *  - else → [kill] (the F3 tombstone/delete set plus durable owner-link deletion).
     */
    internal fun killOrReleasePossession(general: TurnGeneral, env: LifecycleEnv): LifecycleOutcome {
        val deadyear = metaInt(general, "deadyear", 0)
        if (general.npcState == 1 && deadyear > env.year) {
            // ── 유체이탈 possession release (NON-delete) ──
            // (1) the global log is pushed FIRST (before the field mutations, :192).
            val ownerName = metaString(general, "owner_name")
            val josaYi = JosaUtil.pick(ownerName, "이")
            world.pushLog(globalLog(general, "$ownerName</>$josaYi <Y>${general.name}</>의 육체에서 <S>유체이탈</>합니다!"))
            // (2) the field set (verbatim :194-198).
            val npcOrg = metaInt(general, "npc_org", general.npcState)
            val released = general.copy(
                npcState = npcOrg, // npc = npc_org
                userId = null,
                meta = withMeta(
                    general.meta,
                    "killturn" to (deadyear - env.year) * 12 * GameConst.phasesPerMonth,
                    "killturn_unit" to ScenarioLifecycleMeta.KILLTURN_UNIT_PHASE,
                    "owner" to 0,
                    "defence_train" to 80,
                    "owner_name" to null,
                ),
            )
            applyLifecycleGeneral(general, released)
            recorder.recordGeneralOwnerDelete(general.id)
            recorder.recordAccessLogDelete(world, general.id)
            return LifecycleOutcome.POSSESSION_RELEASED
        }
        kill(general, env)
        return LifecycleOutcome.KILLED
    }

    /**
     * kill() — the PHP tombstone plus Kotlin owner-link deletion (`General.php:515-600`). Ordering is load-bearing for
     * the log byte-match: nextRuler/demote → troop cleanup → dying message → storeOldGeneral → the F3
     * delete-set finalize → nation gennum-1. (Inherit-point refund / select_pool null / the user
     * logger are out of the engine slice; the F3 [ChangeRecorder.markGeneralDeleted] performs
     * storeOldGeneral + the PHP delete set, durable owner-link delete, and clears updatedVar so the killed row never re-enters the
     * update-set, `General.php:595`.)
     */
    private fun kill(general: TurnGeneral, env: LifecycleEnv) {
        val generalId = general.id

        // 군주였으면 유지 이음 — officer_level==12 → nextRuler() then setVar('officer_level', 1) (:554-558).
        if (general.officerLevel == 12) {
            nextRuler(generalId, env)
            world.getGeneralById(generalId)?.let { applyLifecycleGeneral(it, it.copy(officerLevel = 1)) }
        }

        // 부대 처리 — troop leader (troop == own id) → free all members + delete the troop (:560-570).
        if (general.troopId == generalId) {
            for (member in world.listGenerals().filter { it.troopId == generalId }) {
                applyLifecycleGeneral(member, member.copy(troopId = 0))
            }
            world.removeTroop(generalId)
        }

        // dying message global log (:573-580) — default is the byte-exact $defaultMessage.
        world.pushLog(globalLog(general, dyingMessage(general)))

        // storeOldGeneral + the F3 delete set and durable owner-link delete (clears updatedVar — no double-apply).
        recorder.markGeneralDeleted(world, generalId)

        // nation gennum-1 (:597-599) — gennum rides the nation meta bag in the slice.
        world.getNationById(general.nationId)?.let { n ->
            val gennum = (n.meta["gennum"] as? Number)?.toInt() ?: 0
            world.updateNation(n.copy(meta = withMeta(n.meta, "gennum" to gennum - 1)))
        }
    }

    /**
     * rebirth() — the in-place UPDATE (NO delete), the age>=retirementYear branch of [updateTurnTime]
     * (`General.php:602-639`). DISTINCT from kill (the opposite op: the row stays). The FULL field set
     * verbatim `:616-633`: leadership/strength/intel `multiplyVarWithLimit(0.85, min 10)`, `injury=0`,
     * experience/dedication ×0.5, `age=20`, `specage=0`, `specage2=0`, dex1..5 ×0.5, ALL 37 RankColumn
     * `setRankVar(0)`, THEN the THREE distinct log pushes (`:636-638`). The Int stat/exp/dedication
     * columns take the PHP raw float rounded half-away-from-zero (the handler [applyGeneralPatch]
     * convention); the ×0.85 stats are floored at 10.
     */
    private fun rebirth(general: TurnGeneral, env: LifecycleEnv) {
        val nextStats = GeneralStats(
            leadership = maxOf(10, phpRound(general.stats.leadership * 0.85)),
            strength = maxOf(10, phpRound(general.stats.strength * 0.85)),
            intelligence = maxOf(10, phpRound(general.stats.intelligence * 0.85)),
            politics = general.stats.politics,
            charm = general.stats.charm,
        )
        val nextMeta = LinkedHashMap(general.meta)
        nextMeta["specage"] = 0
        nextMeta["specage2"] = 0
        for (i in 1..5) {
            val key = "dex$i"
            if (nextMeta.containsKey(key)) {
                nextMeta[key] = phpRound(((nextMeta[key] as? Number)?.toDouble() ?: 0.0) * 0.5)
            }
        }
        val reborn = general.copy(
            stats = nextStats,
            injury = 0,
            experience = phpRound(general.experience * 0.5),
            dedication = phpRound(general.dedication * 0.5),
            age = 20,
            meta = nextMeta,
        )
        applyLifecycleGeneral(general, reborn)

        // ALL 37 rank_data rows reset to 0 (setRankVar — a Set displaces any pending delta).
        for (col in RankColumn.entries) recorder.recordRankSet(general.id, col, 0)

        // the THREE distinct log pushes (:636-638) — order is load-bearing.
        val josaYi = JosaUtil.pick(general.name, "이")
        world.pushLog(globalLog(general, "<Y>${general.name}</>$josaYi <R>은퇴</>하고 그 자손이 유지를 이어받았습니다."))
        world.pushLog(actionLog(general, "나이가 들어 <R>은퇴</>하고 자손에게 자리를 물려줍니다."))
        world.pushLog(historyLog(general, "나이가 들어 은퇴하고, 자손에게 관직을 물려줌"))
    }

    private fun augmentGeneralActionArgs(
        actionCode: String,
        args: Map<String, Any?>,
        general: TurnGeneral,
        year: Int,
    ): Map<String, Any?> {
        if (actionCode !in JOIN_COMMANDS_WITH_DEST_NATION) return args
        val out = LinkedHashMap(args)
        out["relYear"] = year - startYear
        out["actorNpcType"] = general.npcState
        if (actionCode == JANGSU_DAESANG_IMGWAN) {
            val destGeneralId = intArg(args, "destGeneralID")
            val destNationId = destGeneralId?.let { world.getGeneralById(it)?.nationId }?.takeIf { it > 0 }
            if (destNationId != null) out["destNationID"] = destNationId
        }
        return out
    }

    private fun resolveRuntimeDefinition(
        registry: CommandRegistry,
        actionCode: String,
        general: TurnGeneral,
        year: Int,
    ): GeneralActionDefinition {
        if (actionCode != RANDOM_IMGWAN) return registry.resolve(actionCode)
        val relYear = year - startYear
        val genLimit = if (relYear < GameConst.openingPartYear) {
            GameConst.initialNationGenLimit
        } else {
            GameConst.defaultMaxGeneral
        }
        val useNpcForeignBranch = general.npcState >= 2 && worldFiction() == 0 && scenario in 1000 until 2000
        return registry.resolveRandomImgwan(
            npcCandidates = if (useNpcForeignBranch) randomImgwanNpcCandidates(genLimit) else emptyList(),
            weightedCandidates = if (useNpcForeignBranch) emptyList() else randomImgwanWeightedCandidates(genLimit),
            useNpcForeignBranch = useNpcForeignBranch,
            permutationReplay = randomImgwanPermutationReplay,
        )
    }

    private fun preloadDraftTargets(
        actionCode: String,
        draft: GeneralActionDraft,
        args: Map<String, Any?>,
    ) {
        intArg(args, "destGeneralID")?.let { id ->
            world.getGeneralById(id)?.let { draft.destGeneral = PerTurnOverlay.toLogicGeneral(it) }
        }
        when (actionCode) {
            IMGWAN -> intArg(args, "destNationID")?.let { preloadDestNationAndLordCity(draft, it) }
            JANGSU_DAESANG_IMGWAN -> {
                val target = intArg(args, "destGeneralID")?.let { world.getGeneralById(it) } ?: return
                draft.destGeneral = PerTurnOverlay.toLogicGeneral(target)
                preloadDestNationAndLordCity(draft, target.nationId)
            }
        }
    }

    private fun preloadDisbandCascade(draft: GeneralActionDraft, nationId: Int, generalId: Int) {
        world.listGenerals()
            .filter { it.nationId == nationId && it.id != generalId }
            .sortedBy { it.id }
            .mapTo(draft.cascadeGenerals) { PerTurnOverlay.toLogicGeneral(it) }
        world.listCities()
            .filter { it.nationId == nationId }
            .sortedBy { it.id }
            .mapTo(draft.cascadeCities) { PerTurnOverlay.toLogicCity(it) }
    }

    private fun recordCommandInheritance(
        definition: GeneralActionDefinition,
        general: TurnGeneral,
        postGeneral: LogicGeneral,
    ) {
        val owner = general.userId?.toIntOrNull() ?: return
        if (owner <= 0 || general.npcState >= 2) return
        when (definition) {
            is CheGeonguk -> {
                if (definition.lastAlternative != null) return
                recorder.recordInheritancePointIncrease(owner, "active_action", 1.0, null)
                if (definition.lastUnifierGrant > 0) {
                    recorder.recordInheritancePointIncrease(owner, "unifier", definition.lastUnifierGrant.toDouble(), null)
                }
            }
            is CheSeonyang -> {
                if (!definition.lastRuntimeDenied && postGeneral.lastTurn.command == definition.name) {
                    recorder.recordInheritancePointIncrease(owner, "active_action", 1.0, null)
                }
            }
            is JoinCommand -> {
                if (postGeneral.lastTurn.command == definition.name) {
                    recorder.recordInheritancePointIncrease(owner, "active_action", 1.0, null)
                }
            }
            is CheRandomImgwan -> {
                if (definition.lastAlternative == null && postGeneral.lastTurn.command == definition.name) {
                    recorder.recordInheritancePointIncrease(owner, "active_action", 1.0, null)
                }
            }
        }
    }

    private fun resolveAlternativeChain(
        registry: CommandRegistry,
        first: ResolvedCommandExecution,
        draft: GeneralActionDraft,
        rng: RandUtil,
        worldEnv: WorldEnv,
        month: Int,
        date: String,
        general: TurnGeneral,
        cityId: Int,
        nationId: Int,
        year: Int,
        env: Map<String, Any?>,
        executions: MutableList<ResolvedCommandExecution>,
    ): ResolvedCommandExecution? {
        var current = first
        val visited = linkedSetOf(first.actionCode)
        while (true) {
            val alternative = alternativeSpec(current.definition) ?: return current
            check(visited.add(alternative.actionCode)) {
                "ReservedTurnHandler: alternative cycle ${visited.joinToString(" -> ")} -> ${alternative.actionCode}"
            }
            val next = resolveAlternativeCommand(
                registry = registry,
                spec = alternative,
                draft = draft,
                rng = rng,
                worldEnv = worldEnv,
                month = month,
                date = date,
                general = general,
                cityId = cityId,
                nationId = nationId,
                year = year,
                env = env,
                failureContext = executions.first().context,
            ) ?: return null
            executions.add(next)
            current = next
        }
    }

    private fun alternativeSpec(definition: GeneralActionDefinition): AlternativeCommandSpec? {
        val actionCode = when (definition) {
            is CheGeonguk -> definition.lastAlternative
            is CheHaesan -> definition.lastAlternative
            is CheRandomImgwan -> definition.lastAlternative
            is CheChulbyeong -> definition.lastAlternative
            else -> null
        } ?: return null
        val args = if (definition is CheChulbyeong && actionCode == IDONG) {
            linkedMapOf("destCityID" to (definition.lastChosenCityId ?: return null))
        } else {
            emptyMap()
        }
        return AlternativeCommandSpec(actionCode, args)
    }

    private fun resolveAlternativeCommand(
        registry: CommandRegistry,
        spec: AlternativeCommandSpec,
        draft: GeneralActionDraft,
        rng: RandUtil,
        worldEnv: WorldEnv,
        month: Int,
        date: String,
        general: TurnGeneral,
        cityId: Int,
        nationId: Int,
        year: Int,
        env: Map<String, Any?>,
        failureContext: GeneralActionResolveContext,
    ): ResolvedCommandExecution? {
        val baseDefinition = resolveRuntimeDefinition(registry, spec.actionCode, general, year)
        val parsedArgs = runCatching { baseDefinition.parseArgsForGeneral(spec.args, general.id) }.getOrNull()
        if (parsedArgs == null || !baseDefinition.matchesArgsSchema(parsedArgs)) {
            failureContext.addLog("$INVALID_ARGS_DENY_REASON ${baseDefinition.name} 실패. <1>$date</>")
            return null
        }
        val actionArgs = augmentGeneralActionArgs(spec.actionCode, parsedArgs, general, year)
        val definition = baseDefinition.bindArgs(actionArgs)
        val constraintContext = ConstraintContext(
            actorId = general.id,
            cityId = cityId,
            nationId = nationId,
            destGeneralId = intArg(actionArgs, "destGeneralID"),
            destCityId = intArg(actionArgs, "destCityID"),
            destNationId = intArg(actionArgs, "destNationID"),
            args = actionArgs,
            env = env,
            mode = ConstraintMode.FULL,
        )
        when (val result = evaluateConstraints(
            definition.buildConstraints(constraintContext),
            constraintContext,
            WorldStateViewAdapter(PerTurnOverlay(world), env = env, args = actionArgs),
        )) {
            ConstraintResult.Allow -> Unit
            is ConstraintResult.Deny -> {
                failureContext.addLog("${result.reason} ${definition.name} 실패. <1>$date</>")
                return null
            }
            is ConstraintResult.Unknown -> {
                failureContext.addLog("$UNKNOWN_DENY_REASON ${definition.name} 실패. <1>$date</>")
                return null
            }
        }

        val resolveArgs = when {
            spec.actionCode in FOUNDING_COMMANDS -> buildFoundingArgs(spec.actionCode, actionArgs, general, year, month)
            spec.actionCode == HAESAN -> buildSameMonthGuardArgs(actionArgs, general, year, month)
            spec.actionCode == INJAE_TAMSAEK -> buildScoutArgs(actionArgs, year, month)
            else -> actionArgs
        }
        preloadDraftTargets(spec.actionCode, draft, resolveArgs)
        if (spec.actionCode == HAESAN) {
            preloadDisbandCascade(draft, nationId, general.id)
        }
        val context = GeneralActionResolveContext(
            draft, rng, worldEnv, month, date,
            args = resolveArgs,
            generalName = general.name,
            destGeneralName = intArg(actionArgs, "destGeneralID")?.let { world.getGeneralById(it)?.name }.orEmpty(),
            turnterm = turnTerm,
            candidateGenerals = if (spec.actionCode == MUJAKWI_GEONGUK || spec.actionCode == IDONG) {
                world.listGenerals()
                    .filter { it.nationId == nationId && it.id != general.id }
                    .map { PerTurnOverlay.toLogicGeneral(it) }
            } else {
                emptyList()
            },
            candidateCityIds = if (spec.actionCode == MUJAKWI_GEONGUK) {
                world.listCities()
                    .filter { it.nationId == 0 && it.level in 5..6 }
                    .map { it.id }
                    .sorted()
            } else {
                emptyList()
            },
        )
        definition.resolve(context)
        return ResolvedCommandExecution(spec.actionCode, definition, context)
    }

    private fun preloadDestNationAndLordCity(draft: GeneralActionDraft, nationId: Int) {
        val nation = world.getNationById(nationId) ?: return
        draft.destNation = PerTurnOverlay.toLogicNation(nation)
        val lord = lordOf(nationId) ?: return
        draft.destCity = world.getCityById(lord.cityId)?.let { PerTurnOverlay.toLogicCity(it) }
    }

    private fun backfillRandomImgwanDestNation(
        actionCode: String,
        draft: GeneralActionDraft,
        preGeneral: LogicGeneral,
    ) {
        if (actionCode != RANDOM_IMGWAN || draft.destNation != null) return
        val destNationId = draft.general.nationId
        if (destNationId == 0 || destNationId == preGeneral.nationId) return
        val pre = world.getNationById(destNationId) ?: return
        val logic = PerTurnOverlay.toLogicNation(pre)
        draft.destNation = logic.copy(
            gennum = logic.gennum + 1,
            meta = withMeta(logic.meta, "gennum" to logic.gennum + 1),
        )
    }

    private fun randomImgwanNpcCandidates(genLimit: Int): List<RandomImgwanNpcCandidate> {
        val lords = world.listGenerals()
            .filter { it.nationId > 0 && it.officerLevel == 12 }
            .associateBy { it.nationId }
        return world.listNations().mapNotNull { nation ->
            val lord = lords[nation.id] ?: return@mapNotNull null
            val gennum = nationGennum(nation)
            if (nationScout(nation) != 0 || gennum >= genLimit) return@mapNotNull null
            RandomImgwanNpcCandidate(
                nationId = nation.id,
                name = nation.name,
                gennum = gennum,
                affinity = metaInt(lord.meta, "affinity"),
                lordCityId = lord.cityId,
            )
        }
    }

    private fun randomImgwanWeightedCandidates(genLimit: Int): List<RandomImgwanWeightedCandidate> {
        val nations = world.listNations().associateBy { it.id }
        val lords = world.listGenerals()
            .filter { it.nationId > 0 && it.officerLevel == 12 }
            .associateBy { it.nationId }
        return world.listGenerals()
            .filter { it.npcState in RANDOM_IMGWAN_WEIGHTED_NPC_TYPES && it.nationId != 0 }
            .groupBy { it.nationId }
            .mapNotNull { (nationId, generals) ->
                val nation = nations[nationId] ?: return@mapNotNull null
                val gennum = nationGennum(nation)
                if (nationScout(nation) != 0 || gennum >= genLimit) return@mapNotNull null
                val lordCityId = lords[nationId]?.cityId ?: nation.capitalCityId ?: return@mapNotNull null
                val warpower = generals.sumOf { randomImgwanWarpower(it) }
                val develpower = generals.sumOf { randomImgwanDevelpower(it) }
                if (warpower + develpower <= 0.0) return@mapNotNull null
                RandomImgwanWeightedCandidate(
                    nationId = nationId,
                    name = nation.name,
                    gennum = gennum,
                    warpower = warpower,
                    develpower = develpower,
                    npcLeq1 = generals.any { it.npcState < 2 },
                    lordCityId = lordCityId,
                )
            }
    }

    private fun randomImgwanWarpower(general: TurnGeneral): Double {
        val kill = rankVar(general, "killcrew_person") + 50_000.0
        val death = rankVar(general, "deathcrew_person") + 50_000.0
        val npcCoef = if (general.npcState < 2) 1.15 else 1.0
        val lead = if (general.stats.leadership >= 40) general.stats.leadership.toDouble() else 0.0
        return (kill / death) * npcCoef * lead
    }

    private fun randomImgwanDevelpower(general: TurnGeneral): Double =
        (sqrt(general.stats.intelligence.toDouble() * general.stats.strength.toDouble()) * 2.0 +
            general.stats.leadership / 2.0) / 5.0

    private fun lordOf(nationId: Int): TurnGeneral? =
        world.listGenerals().firstOrNull { it.nationId == nationId && it.officerLevel == 12 }

    private fun nationGennum(nation: Nation): Int =
        metaInt(nation.meta, "gennum", world.listGenerals().count { it.nationId == nation.id })

    private fun nationScout(nation: Nation): Int = metaInt(nation.meta, "scout")

    private fun rankVar(general: TurnGeneral, key: String): Double =
        (general.meta[key] as? Number)?.toDouble() ?: 0.0

    private fun worldFiction(): Int = metaInt(world.getState().meta, "fiction")

    /**
     * Build the FOUNDING preload args — the PHP-query substitutes the founding resolver expects but cannot
     * run itself (the daemon supplies them; `che_거병.php:79-110` reads them off the adapter). These are pure
     * DB-query stand-ins consuming NOTHING from the action rng.
     *
     *  - `che_거병` (the prod crash): `newNationId` (PHP `insertId()` placeholder = [InMemoryTurnWorld.allocateNationId]),
     *    `existingNationIds` (getAllNationStaticInfo, world nations), `existingNationNames` (the dup-name scan),
     *    `scenario` (→ secretlimit 1|3).
     *  - `che_건국`/`cr_건국`/`che_무작위건국`: `nationName`/`nationType`/`colorType` already arrive on the
     *    reserved arg jsonb; the runtime engine-scanned preload (`sameMonthOrBefore` 동월 가드, 무작위
     *    `candidateCityIds`) is a faithful-port follow-up (WAVE 0b). Until then they pass `args` through
     *    unchanged — the resolver early-returns (no crash, no found), exactly the pre-fix behavior — and the
     *    drain block above is already laid so WAVE 0b only adds the preload.
     */
    private fun buildFoundingArgs(
        actionCode: String,
        args: Map<String, Any?>,
        general: TurnGeneral,
        year: Int,
        month: Int,
    ): Map<String, Any?> = when (actionCode) {
        GEOBYEONG -> {
            val existing = world.listNations()
            LinkedHashMap(args).apply {
                put("newNationId", world.allocateNationId())
                put("existingNationIds", existing.map { it.id })
                put("existingNationNames", existing.map { it.name }.toSet())
                put("scenario", scenario)
            }
        }
        "che_건국", "cr_건국", MUJAKWI_GEONGUK -> {
            // nationName/nationType/colorType는 예약 arg jsonb에 이미 있음(사용자가 인테이크 시 선택).
            // 런타임 엔진 스캔 preload만 추가: sameMonthOrBefore 동월 가드(che_건국.php:148).
            LinkedHashMap(args).apply {
                put("sameMonthOrBefore", sameMonthOrBefore(general, year, month))
            }
        }
        else -> args
    }

    private fun buildSameMonthGuardArgs(
        args: Map<String, Any?>,
        general: TurnGeneral,
        year: Int,
        month: Int,
    ): Map<String, Any?> = LinkedHashMap(args).apply {
        put("sameMonthOrBefore", sameMonthOrBefore(general, year, month))
    }

    private fun buildScoutArgs(args: Map<String, Any?>, year: Int, month: Int): Map<String, Any?> {
        val generals = world.listGenerals()
        val dexSource = generals.filter { it.npcState < 4 }
        val avgDexTotal = if (dexSource.isEmpty()) {
            0.0
        } else {
            dexSource.sumOf { metaInt(it.meta, "dex1") + metaInt(it.meta, "dex2") + metaInt(it.meta, "dex3") + metaInt(it.meta, "dex4") }
                .toDouble() / dexSource.size
        }
        val avgDex5 = if (dexSource.isEmpty()) {
            0
        } else {
            (dexSource.sumOf { metaInt(it.meta, "dex5") }.toDouble() / dexSource.size).toInt()
        }
        val state = world.getState()
        val maxGeneral = (state.meta["maxgeneral"] as? Number)?.toInt() ?: GameConst.defaultMaxGeneral
        val develCost = (state.meta["develcost"] as? Number)?.toInt()
            ?: (WorldEnvBuilder.envMap(year, startYear)["develCost"] as Int)
        val turnterm = (state.meta["turnterm"] as? Number)?.toInt() ?: turnTerm
        return LinkedHashMap(args).apply {
            put("maxGenCnt", maxGeneral)
            put("totalGenCnt", generals.count { it.npcState <= 2 })
            put("totalNpcCnt", generals.count { it.npcState in 3..4 })
            put("avgGenDexTotal", avgDexTotal)
            put("avgGenDex5", avgDex5)
            put("year", year)
            put("startYear", startYear)
            put("month", month)
            put("develCost", develCost)
            put("turnterm", turnterm)
            put("cityPool", world.listCities().sortedBy { it.id }.map { linkedMapOf("id" to it.id, "nationId" to it.nationId) })
            put("existingGeneralNames", generals.map { it.name })
        }
    }

    private fun drainScoutNpc(builtNpc: CheInjaeTamsaek.BuiltScoutNpc?) {
        val built = builtNpc?.built ?: return
        recorder.recordGeneralCreate(world, built.toTurnGeneral(world.allocateGeneralId(), world.getState()))
    }

    private fun recordScoutInheritance(
        builtNpc: CheInjaeTamsaek.BuiltScoutNpc?,
        ownerId: String?,
        args: Map<String, Any?>,
    ) {
        if (builtNpc == null) return
        val owner = ownerId?.toIntOrNull() ?: return
        val maxGenCnt = (args["maxGenCnt"] as? Number)?.toInt() ?: return
        val totalGenCnt = (args["totalGenCnt"] as? Number)?.toInt() ?: return
        val totalNpcCnt = (args["totalNpcCnt"] as? Number)?.toInt() ?: return
        val foundProp = calcScoutFoundProp(maxGenCnt, totalGenCnt, totalNpcCnt)
        recorder.recordInheritancePointIncrease(
            ownerID = owner,
            key = "active_action",
            value = valueFit(sqrt(1.0 / foundProp), 1.0),
            aux = null,
        )
    }

    private fun calcScoutFoundProp(maxGenCnt: Int, totalGenCnt: Int, totalNpcCnt: Int): Double {
        val current = (totalGenCnt + totalNpcCnt / 2.0).toInt()
        val remain = maxOf(0, maxGenCnt - current)
        val main = (remain.toDouble() / maxGenCnt).let { it * it * it * it * it * it }
        val small = 1.0 / (totalNpcCnt / 3.0 + 1.0)
        val big = 1.0 / maxGenCnt
        return if (totalNpcCnt < 50) maxOf(main, small) else maxOf(main, big)
    }

    /**
     * Route a logic [opensamguk.logic.message.Message] through the mailbox channel: produce its send
     * rows (receiver BEFORE sender) and record each INSERT with the pre-assigned in-memory id folded
     * into the body's receiver/sender back-references (T0.5, same pattern as [ProcessNationCommand]).
     */
    private fun routeMessage(message: opensamguk.logic.message.Message, year: Int, month: Int) {
        val drafts = message.send()
        var receiverId: Int? = null
        for (draft in drafts) {
            val option = LinkedHashMap(draft.option ?: emptyMap())
            if (draft.whichRow == opensamguk.logic.message.MessageRowDraft.Row.RECEIVER) {
                val id = recorder.recordMessageInsert(
                    mailbox = draft.mailbox, type = draft.type.value, srcId = draft.srcId, destId = draft.destId,
                    time = message.date, validUntil = message.validUntil,
                    bodyJson = encodeMessageBody(draft, option, receiverIdToFold = null),
                )
                receiverId = id
            } else {
                recorder.recordMessageInsert(
                    mailbox = draft.mailbox, type = draft.type.value, srcId = draft.srcId, destId = draft.destId,
                    time = message.date, validUntil = message.validUntil,
                    bodyJson = encodeMessageBody(draft, option, receiverIdToFold = receiverId),
                )
            }
        }
    }

    /**
     * PHP `Util::joinYearMonth(y, m) = y*12 + m - 1` (che_건국.php:148 same-month guard).
     * `init_year`/`init_month` ride the game env/world state, not the actor's nation meta.
     * `yearMonth <= initYearMonth`
     * → true → resolver early-returns with "다음 턴..." (no write).
     */
    private fun sameMonthOrBefore(@Suppress("UNUSED_PARAMETER") general: TurnGeneral, year: Int, month: Int): Boolean {
        val state = world.getState()
        val initYear = (state.meta["init_year"] as? Number)?.toInt() ?: return false
        val initMonth = (state.meta["init_month"] as? Number)?.toInt() ?: 1
        val initYearMonth = initYear * 12 + initMonth - 1
        val yearMonth = year * 12 + month - 1
        return yearMonth <= initYearMonth
    }

    /** Byte-faithful `{src,dest,text,option}` jsonb for a message row. */
    private fun encodeMessageBody(
        draft: opensamguk.logic.message.MessageRowDraft,
        option: Map<String, Any?>,
        receiverIdToFold: Int?,
    ): String {
        val opt = LinkedHashMap<String, Any?>(option)
        if (receiverIdToFold != null) opt["receiverMessageID"] = receiverIdToFold
        val body = linkedMapOf<String, Any?>(
            "src" to draft.src.toArray(),
            "dest" to draft.dest.toArray(),
            "text" to draft.text,
            "option" to (if (draft.option == null) null else opt),
        )
        return opensamguk.infra.persistence.MetaJson.encode(body)
    }

    companion object {
        private val CONQUER_MESSAGE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)
        /** Deny reason when the full-mode evaluator can't resolve a requirement (shouldn't occur in FULL). */
        const val UNKNOWN_DENY_REASON = "처리할 수 없습니다."
        const val INVALID_ARGS_DENY_REASON = "인자가 올바르지 않습니다."

        /** The 휴식 (rest) command name — the killturn-decrement branch in [applyKillturnDecrement]. */
        const val REST_COMMAND = "휴식"

        /** che_거병 — the no-arg INSERT-created-set founding command (the prod crash seam). */
        const val GEOBYEONG = "che_거병"

        /** che_무작위건국 — the random-city founding command (needs candidateCityIds preload). */
        const val MUJAKWI_GEONGUK = "che_무작위건국"
        const val HAESAN = "che_해산"
        const val IDONG = "che_이동"

        const val IMGWAN = "che_임관"
        const val JANGSU_DAESANG_IMGWAN = "che_장수대상임관"
        const val RANDOM_IMGWAN = "che_랜덤임관"
        const val INJAE_TAMSAEK = "che_인재탐색"

        val JOIN_COMMANDS_WITH_DEST_NATION = setOf(IMGWAN, JANGSU_DAESANG_IMGWAN)

        val RANDOM_IMGWAN_WEIGHTED_NPC_TYPES = setOf(0, 1, 2, 3, 6)

        /**
         * Founding commands whose resolve seam is augmented with PHP-query preload args (and whose actor
         * name is threaded as the created nation's name). 거병 INSERTs a nation; 건국/cr_건국/무작위건국 UPDATE
         * a wandering nation (Bug B — WAVE 0b preload pending). Anything else passes through unchanged.
         */
        val FOUNDING_COMMANDS = setOf("che_거병", "che_건국", "cr_건국", "che_무작위건국")

        internal val UNIQUE_ITEM_LOTTERY_COMMAND_CODES: Set<String> = linkedSetOf(
            "che_강행",
            "che_거병",
            "che_건국",
            "che_견문",
            "che_군량매매",
            "che_귀환",
            "che_기술연구",
            "che_단련",
            "che_등용",
            "che_랜덤임관",
            "che_무작위건국",
            "che_물자조달",
            "che_사기진작",
            "che_상업투자",
            "che_숙련전환",
            "che_은퇴",
            "che_이동",
            "che_인재탐색",
            "che_임관",
            "che_장비매매",
            "che_장수대상임관",
            "che_전투태세",
            "che_전투특기초기화",
            "che_정착장려",
            "che_주민선정",
            "che_증여",
            "che_집합",
            "che_징병",
            "che_출병",
            "che_헌납",
            "che_훈련",
            "cr_건국",
            "cr_맹훈련",
        )

        /** A lenient JSON reader for the stored `arg` jsonb (tolerant of trailing commas / lax keys). */
        private val ARG_JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * Whether [general] is AI-controlled — PHP `$general->isNPC()` (`npc >= 2`; the same threshold
         * the killturn-decrement / npcType branches already use). Human-window eligibility is separate.
         */
        internal fun isAiControlled(general: TurnGeneral): Boolean = general.npcState >= 2

        internal fun isAutorunEligible(general: TurnGeneral, year: Int, month: Int): Boolean =
            isAiControlled(general) ||
                (general.meta.autorunLimit()?.let { year * 12 + month - 1 < it } ?: false)

        /**
         * Decode the stored `arg` jsonb (PHP `Json::decode($rawTurn['arg'])`) into the parsed arg map
         * the resolver reads (R-SEAM §1). A blank/`null`/non-object payload yields an empty map (a
         * no-arg command). Insertion order is preserved (the jsonb key order is a parity surface).
         */
        internal fun decodeArgs(argJson: String): Map<String, Any?> {
            if (argJson.isBlank()) return emptyMap()
            val root = try {
                ARG_JSON.parseToJsonElement(argJson)
            } catch (_: Exception) {
                return emptyMap()
            }
            if (root !is JsonObject) return emptyMap()
            val out = LinkedHashMap<String, Any?>(root.size)
            for ((key, element) in root) out[key] = jsonToAny(element)
            return out
        }

        /** Convert a [kotlinx.serialization.json.JsonElement] leaf to the closest Kotlin scalar. */
        private fun jsonToAny(element: kotlinx.serialization.json.JsonElement): Any? = when (element) {
            is JsonNull -> null
            is JsonPrimitive -> when {
                element.isString -> element.content
                element.booleanOrNull != null -> element.boolean
                // Int 범위 안의 정수 jsonb 값은 Int로 좁힌다. resolve 본문(예: che_불가침수락/종전수락의
                // `args["destNationID"] as? Int`)과 parseArgs가 Int 타입을 기대하므로, Long으로 두면
                // `as? Int`가 null이 되어 외교 행 전환이 조용히 누락된다(`as? Number)?.toInt()`를 쓰는
                // 본문은 Int/Long 모두 받으므로 영향 없음). 범위를 벗어난 값만 Long으로 유지.
                element.longOrNull != null ->
                    element.long.let { if (it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) it.toInt() else it }
                else -> element.content.toDoubleOrNull() ?: element.content
            }
            is JsonObject -> element.mapValues { jsonToAny(it.value) }
            is JsonArray -> element.map { jsonToAny(it) }
        }

        /** Read a lifecycle scalar that rides the `meta` bag (e.g. `killturn`/`block`/`deadyear`). */
        internal fun metaInt(g: TurnGeneral, key: String, default: Int): Int =
            (g.meta[key] as? Number)?.toInt() ?: default

        internal fun metaInt(meta: Map<String, Any?>, key: String, default: Int = 0): Int =
            (meta[key] as? Number)?.toInt() ?: default

        internal fun intArg(args: Map<String, Any?>, key: String): Int? =
            (args[key] as? Number)?.toInt()

        /** Read a lifecycle scalar as a string (e.g. `owner_name`), or null. */
        internal fun metaString(g: TurnGeneral, key: String): String? = g.meta[key] as? String

        /** Insertion-order-preserving meta merge (the jsonb the flush writes keeps PHP key order). */
        internal fun withMeta(meta: Map<String, Any?>, vararg pairs: Pair<String, Any?>): Map<String, Any?> {
            val out = LinkedHashMap(meta)
            for ((k, v) in pairs) out[k] = v
            return out
        }

        internal fun materializeMariaDbFloat(value: Double): Double =
            String.format(java.util.Locale.ROOT, "%.6g", value.toFloat()).toDouble()

        /**
         * Map the resolver's post-state logic [General] back onto the engine [TurnGeneral] (slice
         * columns + meta carried verbatim). Engine `experience`/`dedication` are Int accumulators;
         * the resolver's raw Double collapses to Int the SAME way the persist does — Postgres ROUNDS
         * the float into the `integer` column (half-away-from-zero), it does NOT truncate. The G2
         * golden proves it (3030+44*0.7=3060.8 → 3061; 3030+64*0.7=3074.8 → 3075). Round via phpRound
         * here so the engine row matches the D1 mapper's rounded column.
         */
        private fun applyGeneralPatch(engine: TurnGeneral, post: LogicGeneral): TurnGeneral =
            engine.copy(
                userId = post.userId,
                gold = post.gold,
                rice = post.rice,
                injury = post.injury,
                officerLevel = post.officerLevel,
                cityId = post.cityId,
                nationId = post.nationId,
                troopId = post.troop,
                stats = GeneralStats(
                    leadership = post.leadership,
                    strength = post.strength,
                    intelligence = post.intel,
                    politics = post.politics,
                    charm = post.charm,
                ),
                experience = phpRound(post.experience),
                dedication = phpRound(post.dedication),
                crew = post.crew,
                crewTypeId = post.crewTypeId,
                train = phpRound(post.train),
                atmos = phpRound(post.atmos),
                npcState = post.npcType,
                age = post.age ?: engine.age,
                turnTime = post.turnTime ?: engine.turnTime,
                role = engine.role.copy(
                    items = GeneralItems(
                        horse = post.horse,
                        weapon = post.weapon,
                        book = post.book,
                        item = post.item,
                    ),
                ),
                meta = withMeta(post.meta, "last_turn" to post.lastTurn.toRaw()),
            )

        /**
         * Map a founding/건국 logic [Nation]'s mutated fields back onto the engine [Nation] row (the
         * world read-apply for the nation-UPDATE drain). A verbatim clone of
         * `ProcessNationCommand.applyLogicToNation`: the [ChangeRecorder.diffNation] is the authoritative
         * persist path; this only keeps same-tick world reads consistent (`tech`/`gennum` ride `meta`,
         * so they are carried by the `meta` copy — there is no separate engine column).
         */
        private fun applyNationPatch(engine: Nation, logic: LogicNation): Nation =
            engine.copy(
                name = logic.name,
                color = logic.color,
                capitalCityId = logic.capitalCityId,
                gold = logic.gold,
                rice = logic.rice,
                power = logic.power,
                tech = logic.tech,
                level = logic.level,
                typeCode = logic.typeCode,
                meta = logic.meta,
            )

        /**
         * Map the resolver's post-state logic [City] back onto the engine [City]. The engine City has
         * no `trust` column — `trust` lives in `meta["trust"]` (the inverse of [PerTurnOverlay.toLogicCity]).
         */
        private fun applyCityPatch(engine: City, post: LogicCity): City {
            val materializedTrust = materializeMariaDbFloat(post.trust)
            val nextMeta = if (materializedTrust != (engine.meta["trust"] as? Number)?.toDouble()) {
                val m = LinkedHashMap(engine.meta); m["trust"] = materializedTrust; m
            } else {
                engine.meta
            }
            return engine.copy(
                level = post.level,
                state = post.state,
                commerce = post.commerce,
                commerceMax = post.commerceMax,
                agriculture = post.agriculture,
                agricultureMax = post.agricultureMax,
                population = post.population,
                populationMax = post.populationMax,
                dead = post.dead,
                security = post.security,
                securityMax = post.securityMax,
                defence = post.defense,
                defenceMax = post.defenseMax,
                wall = post.wall,
                wallMax = post.wallMax,
                supplyState = post.supplyState,
                frontState = post.frontState,
                nationId = post.nationId,
                trade = post.trade,
                region = post.region,
                term = post.term,
                officerSet = post.officerSet,
                conflict = post.conflict,
                meta = nextMeta,
            )
        }

        /** Wrap an action log line as a `general` action [LogEntryDraft]. */
        private fun actionLog(general: TurnGeneral, text: String): LogEntryDraft = LogEntryDraft(
            scope = "general",
            category = "action",
            text = text,
            generalId = general.id,
            nationId = general.nationId,
        )

        /** Wrap a line as a `global` action [LogEntryDraft] (pushGlobalActionLog). */
        private fun globalLog(general: TurnGeneral, text: String): LogEntryDraft = LogEntryDraft(
            scope = "global",
            category = "action",
            text = text,
            generalId = general.id,
            nationId = general.nationId,
        )

        private fun globalHistoryLog(general: TurnGeneral, text: String): LogEntryDraft = LogEntryDraft(
            scope = "global",
            category = "history",
            text = text,
            generalId = general.id,
            nationId = general.nationId,
        )

        /** Wrap a line as a `general` history [LogEntryDraft] (pushGeneralHistoryLog). */
        private fun historyLog(general: TurnGeneral, text: String): LogEntryDraft = LogEntryDraft(
            scope = "general",
            category = "history",
            text = text,
            generalId = general.id,
            nationId = general.nationId,
        )

        private fun logEvent(
            actor: TurnGeneral,
            event: GeneralActionResolveContext.BufferedLog,
        ): LogEntryDraft {
            val targetId = event.targetGeneralId ?: actor.id
            val targetNationId = if (event.targetGeneralId == null) actor.nationId else null
            return when (event.scope) {
                "global" -> LogEntryDraft(
                    scope = "global",
                    category = event.category,
                    text = event.text,
                    generalId = actor.id,
                    nationId = actor.nationId,
                )
                "nation" -> LogEntryDraft(
                    scope = "nation",
                    category = event.category,
                    text = event.text,
                    nationId = actor.nationId,
                )
                else -> LogEntryDraft(
                    scope = "general",
                    category = event.category,
                    text = event.text,
                    generalId = targetId,
                    nationId = targetNationId,
                )
            }
        }

        /**
         * The byte-exact PHP `DyingMessage::$defaultMessage` (`<Y>;name;</>;이; <R>사망</>했습니다.`) with
         * the JosaUtil `이` substitution. The RNG-selected variant pool is wired by the G1 gate.
         */
        internal fun defaultDyingMessage(general: TurnGeneral): String {
            val josaYi = JosaUtil.pick(general.name, "이")
            return "<Y>${general.name}</>$josaYi <R>사망</>했습니다."
        }

        /** Wrap a deny-reason as a `general` action [LogEntryDraft] (the 휴식-fallback log). */
        private fun denyLog(
            general: TurnGeneral,
            definition: GeneralActionDefinition,
            reason: String,
            month: Int,
            date: String,
        ): LogEntryDraft = LogEntryDraft(
            scope = "general",
            category = "action",
            text = "<C>●</>${month}월:$reason ${definition.name} 실패. <1>$date</>",
            generalId = general.id,
            nationId = general.nationId,
        )
    }

    private fun effectivePostCity(actionCode: String, postCity: LogicCity): LogicCity {
        if (actionCode != MUJAKWI_GEONGUK) return postCity
        val current = world.getCityById(postCity.id)?.let { PerTurnOverlay.toLogicCity(it) } ?: return postCity
        return current.copy(
            nationId = postCity.nationId,
            conflict = postCity.conflict,
        )
    }

    private fun rareSaleHistoryLog(
        actionCode: String,
        args: Map<String, Any?>,
        general: TurnGeneral,
        nation: LogicNation?,
        year: Int,
        month: Int,
    ): String? {
        if (actionCode != "che_장비매매") return null
        if (args["itemCode"] != "None") return null
        val itemType = args["itemType"] as? String ?: return null
        val soldCode = when (itemType) {
            "horse" -> general.role.items.horse
            "weapon" -> general.role.items.weapon
            "book" -> general.role.items.book
            "item" -> general.role.items.item
            else -> null
        } ?: "None"
        val info = uniqueItemInfo(soldCode) ?: return null
        val josaYi = JosaUtil.pick(general.name, "이")
        val josaUl = JosaUtil.pick(info.rawName, "을")
        val nationName = nation?.name ?: "재야"
        val body = "<R><b>【판매】</b></><D><b>$nationName</b></>의 <Y>${general.name}</>$josaYi <C>${info.name}</>$josaUl 판매했습니다!"
        return "<C>●</>${year}년 ${month}월:$body"
    }

    private fun isRareSaleGlobalAction(event: GeneralActionResolveContext.BufferedLog): Boolean =
        event.scope == "global" &&
            event.category == "action" &&
            (event.text.contains("</>가 <C>") || event.text.contains("</>이 <C>")) &&
            event.text.contains("판매했습니다!")

    private fun uniqueLotteryIntent(
        actionCode: String,
        definition: GeneralActionDefinition,
        context: GeneralActionResolveContext,
    ): GeneralUniqueLotteryIntent? {
        val explicit = when (definition) {
            is CheGeonguk -> definition.lastUniqueLotteryIntent
            is RecruitAlgorithm -> definition.lastUniqueLotteryIntent
            is CheSukryeonJeonhwan -> definition.lastUniqueLotteryIntent
            is CheGunryangMaemae -> definition.lastUniqueLotteryIntent
            is CheJangbiMaemae -> definition.lastUniqueLotteryIntent
            is JoinCommand -> definition.lastUniqueLotteryIntent
            is CheRandomImgwan -> definition.lastUniqueLotteryIntent
            else -> null
        }
        if (explicit != null) return explicit
        if (actionCode !in UNIQUE_ITEM_LOTTERY_COMMAND_CODES) return null
        return GeneralUniqueLotteryIntent(
            generalId = context.draft.general.id,
            year = context.env.year,
            month = context.month,
            seedReason = definition.lotteryActionName,
            acquireType = when (actionCode) {
                "che_건국", "cr_건국", MUJAKWI_GEONGUK -> "건국"
                RANDOM_IMGWAN -> "랜덤 임관"
                else -> "아이템"
            },
            afterTail = "handlerCommandTail",
        )
    }

    private fun consumeUniqueLottery(
        intent: GeneralUniqueLotteryIntent,
        draft: GeneralActionDraft,
        context: GeneralActionResolveContext,
    ) {
        if (draft.general.npcType >= 2) return

        val allItems = activeAllItemsTable()
        val uniqueCatalog = uniqueCatalogFor(allItems)
        val itemTypeCount = allItems.size
        var maxTrialsByYear = 1
        val relativeYear = intent.year - startYear
        for ((targetYear, targetTrials) in activeMaxUniqueItemLimit()) {
            if (relativeYear < targetYear) break
            maxTrialsByYear = targetTrials
        }
        var trialCount = minOf(itemTypeCount, maxTrialsByYear)
        var maxCount = itemTypeCount
        for (code in generalItemCodes(draft.general)) {
            if (code != null && isUniqueItem(code, uniqueCatalog)) {
                trialCount -= 1
                maxCount -= 1
            }
        }
        if (trialCount <= 0) {
            refundRandomUniqueIfNeeded(draft, "유니크를 얻을 공간이 없어 ${activeInheritItemRandomPoint()} 포인트 반환")
            return
        }

        val state = world.getState()
        val initialYear = (state.meta["init_year"] as? Number)?.toInt() ?: intent.year
        val initialMonth = (state.meta["init_month"] as? Number)?.toInt() ?: 1
        val relativeMonth = intent.year * 12 + intent.month - (initialYear * 12 + initialMonth)
        val randomUniqueOrdered = generalAux(draft.general)["inheritRandomUnique"] != null
        val availableBuyUnique = relativeMonth >= activeMinMonthToAllowInheritItem()
        val humanCount = world.listGenerals().count { it.npcState < 2 }.coerceAtLeast(1)
        var probability = if (scenario < 100) {
            1.0 / (humanCount * 3 * itemTypeCount)
        } else {
            1.0 / (humanCount * itemTypeCount)
        }
        if (intent.acquireType == "랜덤 임관") {
            probability = 1.0 / (humanCount * itemTypeCount / 10.0 / 2.0)
        }
        probability = minOf(probability * activeUniqueTrialCoef(), activeMaxUniqueTrialProb()) / sqrt(7.0)
        if ((randomUniqueOrdered && availableBuyUnique) || intent.acquireType == "건국") probability = 1.0

        val rng = RandUtil(LiteHashDrbg(uniqueLotterySeed(hiddenSeed, intent.year, intent.month, intent.generalId, intent.seedReason)))
        var won = false
        for (idx in 0 until maxCount) {
            if (rng.nextBool(probability)) {
                won = true
                break
            }
            probability *= Math.pow(10.0, 0.25)
        }
        if (!won) return

        val unavailableSlots = generalItemCodesByType(draft.general)
            .filterValues { it != null && isUniqueItem(it, uniqueCatalog) }
            .keys
        val occupied = occupiedUniqueCounts(draft.general, uniqueCatalog)
        val available = deriveItemPool(allItems)
            .asSequence()
            .filterNot { it.itemType in unavailableSlots }
            .mapNotNull { entry ->
                val remaining = entry.weight - (occupied[entry.itemCode] ?: 0)
                if (remaining > 0) entry.copy(weight = remaining) else null
            }
            .toList()
        if (available.isEmpty()) {
            refundRandomUniqueIfNeeded(draft, "얻을 유니크가 없어 ${activeInheritItemRandomPoint()} 포인트 반환")
            return
        }

        val (itemType, itemCode) = giveRandomUniqueItem(rng, available)
        draft.general = applyGeneralItem(draft.general, itemType, itemCode)
        if (randomUniqueOrdered && availableBuyUnique) {
            draft.general = draft.general.copy(meta = withGeneralAux(draft.general.meta, "inheritRandomUnique", null))
        }
        val info = uniqueItemInfo(itemCode, uniqueCatalog)
            ?: error("unique lottery selected an uncatalogued item: $itemCode")
        val generalName = world.getGeneralById(draft.general.id)?.name.orEmpty()
        val nationName = draft.nation?.name
            ?: world.getNationById(draft.general.nationId)?.name
            ?: "재야"
        val josaYi = JosaUtil.pick(generalName, "이")
        val josaUl = JosaUtil.pick(info.rawName, "을")
        context.addLog("<C>${info.name}</>$josaUl 습득했습니다!")
        context.addGeneralHistoryLog("<C>${info.name}</>$josaUl 습득")
        context.addGlobalActionLog("<Y>$generalName</>$josaYi <C>${info.name}</>$josaUl 습득했습니다!")
        context.addGlobalHistoryLog("<C><b>【${intent.acquireType}】</b></><D><b>$nationName</b></>의 <Y>$generalName</>$josaYi <C>${info.name}</>$josaUl 습득했습니다!")
    }

    private fun occupiedUniqueCounts(
        postGeneral: LogicGeneral,
        uniqueCatalog: Map<String, UniqueItemInfo>,
    ): Map<String, Int> {
        val occupied = LinkedHashMap<String, Int>()
        for (general in world.listGenerals().sortedBy { it.id }) {
            val codes = if (general.id == postGeneral.id) generalItemCodes(postGeneral) else generalItemCodes(general)
            for (code in codes) {
                if (code != null && isUniqueItem(code, uniqueCatalog)) occupied[code] = (occupied[code] ?: 0) + 1
            }
        }
        val auctionItems = world.getState().meta["activeUniqueAuctionItems"] as? Iterable<*>
        auctionItems?.forEach { code -> code?.toString()?.let { occupied[it] = (occupied[it] ?: 0) + 1 } }
        val stored = world.getState().meta["storedUniqueItemCounts"] as? Map<*, *>
        stored?.forEach { (code, count) ->
            val key = code?.toString() ?: return@forEach
            occupied[key] = (occupied[key] ?: 0) + ((count as? Number)?.toInt() ?: 0)
        }
        return occupied
    }

    private fun activeAllItemsTable(): Map<String, Map<String, Int>> {
        val raw = world.getState().meta["allItems"] as? Map<*, *> ?: return GameConst.allItems
        val out = LinkedHashMap<String, Map<String, Int>>()
        for ((typeKey, typeItems) in raw) {
            val type = typeKey?.toString() ?: continue
            val items = typeItems as? Map<*, *> ?: continue
            val converted = LinkedHashMap<String, Int>()
            for ((code, amount) in items) {
                val itemCode = code?.toString() ?: continue
                val count = (amount as? Number)?.toInt() ?: continue
                converted[itemCode] = count
            }
            out[type] = converted
        }
        return out.ifEmpty { GameConst.allItems }
    }

    private fun uniqueCatalogFor(allItems: Map<String, Map<String, Int>>): Map<String, UniqueItemInfo> {
        val out = LinkedHashMap<String, UniqueItemInfo>()
        for ((_, items) in allItems) {
            for ((code, amount) in items) {
                if (amount == 0) continue
                out[code] = UNIQUE_ITEM_CATALOG[code] ?: continue
            }
        }
        return out
    }

    private fun activeMaxUniqueItemLimit(): List<List<Int>> {
        val raw = world.getState().meta["maxUniqueItemLimit"] as? List<*> ?: return GameConst.maxUniqueItemLimit
        val rows = raw.mapNotNull { row ->
            val values = row as? List<*> ?: return@mapNotNull null
            val year = (values.getOrNull(0) as? Number)?.toInt() ?: return@mapNotNull null
            val trials = (values.getOrNull(1) as? Number)?.toInt() ?: return@mapNotNull null
            listOf(year, trials)
        }
        return rows.ifEmpty { GameConst.maxUniqueItemLimit }
    }

    private fun activeUniqueTrialCoef(): Double =
        (world.getState().meta["uniqueTrialCoef"] as? Number)?.toDouble() ?: GameConst.uniqueTrialCoef.toDouble()

    private fun activeMaxUniqueTrialProb(): Double =
        (world.getState().meta["maxUniqueTrialProb"] as? Number)?.toDouble() ?: GameConst.maxUniqueTrialProb

    private fun activeMinMonthToAllowInheritItem(): Int =
        (world.getState().meta["minMonthToAllowInheritItem"] as? Number)?.toInt()
            ?: GameConst.minMonthToAllowInheritItem

    private fun activeInheritItemRandomPoint(): Int =
        (world.getState().meta["inheritItemRandomPoint"] as? Number)?.toInt()
            ?: GameConst.inheritItemRandomPoint

    private fun isUniqueItem(code: String, uniqueCatalog: Map<String, UniqueItemInfo>): Boolean =
        uniqueCatalog.containsKey(code)

    private fun uniqueItemInfo(code: String): UniqueItemInfo? = UNIQUE_ITEM_CATALOG[code]

    private fun uniqueItemInfo(code: String, uniqueCatalog: Map<String, UniqueItemInfo>): UniqueItemInfo? =
        uniqueCatalog[code] ?: UNIQUE_ITEM_CATALOG[code]

    private fun refundRandomUniqueIfNeeded(draft: GeneralActionDraft, logText: String) {
        if (generalAux(draft.general)["inheritRandomUnique"] == null) return
        draft.general = draft.general.copy(meta = withGeneralAux(draft.general.meta, "inheritRandomUnique", null))
        val ownerId = draft.general.userId?.toIntOrNull() ?: return
        val point = activeInheritItemRandomPoint()
        recorder.recordInheritancePointIncrease(ownerId, "previous", point.toDouble(), null)
        recorder.recordInheritanceLog(ownerId, logText, "inheritPoint")
        recorder.recordRankIncrease(draft.general.id, RankColumn.INHERIT_SPENT_DYN, -point)
    }

    private fun generalAux(general: LogicGeneral): Map<*, *> = general.meta["aux"] as? Map<*, *> ?: emptyMap<Any?, Any?>()

    private fun withGeneralAux(meta: Map<String, Any?>, key: String, value: Any?): Map<String, Any?> {
        val aux = LinkedHashMap<String, Any?>()
        (meta["aux"] as? Map<*, *>)?.forEach { (k, v) -> if (k is String) aux[k] = v }
        if (value == null) {
            aux.remove(key)
        } else {
            aux[key] = value
        }
        return LinkedHashMap(meta).apply { this["aux"] = aux }
    }

    private fun generalItemCodes(general: LogicGeneral): List<String?> = listOf(general.horse, general.weapon, general.book, general.item)

    private fun generalItemCodes(general: TurnGeneral): List<String?> = listOf(
        general.role.items.horse,
        general.role.items.weapon,
        general.role.items.book,
        general.role.items.item,
    )

    private fun generalItemCodesByType(general: LogicGeneral): Map<String, String?> = linkedMapOf(
        "horse" to general.horse,
        "weapon" to general.weapon,
        "book" to general.book,
        "item" to general.item,
    )

    private fun applyGeneralItem(general: LogicGeneral, itemType: String, itemCode: String): LogicGeneral = when (itemType) {
        "horse" -> general.copy(horse = itemCode)
        "weapon" -> general.copy(weapon = itemCode)
        "book" -> general.copy(book = itemCode)
        "item" -> general.copy(item = itemCode)
        else -> general
    }
}

/**
 * The `game_env` slice the lifecycle tail reads (PHP `$gameStor` fields used in
 * `TurnExecutionHelper.php:153-230` + `General.php:515-639`): the killturn baseline, the current
 * year/month, the turn term (for [ServerClock.addTurn]), the `isunited` gate (rebirth skips applyDB
 * when not 0), the autorun flag, and the `HH:MM` turn-time suffix for block logs.
 */
data class LifecycleEnv(
    /** `$gameStor->killturn` — the reset baseline for a human running a real command. */
    val baselineKillturn: Int,
    val year: Int,
    val month: Int,
    /** `$gameStor->turnterm` — the minutes-per-turn grid for [ServerClock.addTurn]. */
    val turnTerm: Int,
    /** `$gameStor->isunited` — rebirth applyDB/CheckHall only when 0 (`:210`). */
    val isunited: Int = 0,
    val autorunMode: Boolean = false,
    /** `$general->getTurnTime(TURNTIME_HM)` — the `<1>HH:MM</>` suffix on the block log. */
    val turnTimeHm: String = "",
)

/** The outcome of [ReservedTurnHandler.updateTurnTime] (the lifecycle branch the drain inspects). */
enum class LifecycleOutcome {
    /** killturn>0, age<retirement — turntime advanced, the row persists. */
    SURVIVED,

    /** NPCType==1 & deadyear>year — possession released (유체이탈), turntime advanced, NOT deleted. */
    POSSESSION_RELEASED,

    /** killturn<=0 (not possession) — kill() tombstoned the row and removed its durable owner link. */
    KILLED,

    /** age>=retirementYear human — rebirth() in-place UPDATE, turntime advanced, NOT deleted. */
    REBIRTHED,
}
