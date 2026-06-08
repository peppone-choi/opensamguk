package opensamguk.engine.turn

import opensamguk.common.constants.GameConst
import opensamguk.common.josa.JosaUtil
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.ai.ChosenCommand
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.ConstraintMode
import opensamguk.logic.constraints.ConstraintResult
import opensamguk.logic.constraints.evaluateConstraints
import opensamguk.logic.domain.WorldEnv
import opensamguk.logic.statview.WorldEnvBuilder
import opensamguk.logic.tick.ServerClock
import opensamguk.logic.util.phpRound
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import opensamguk.logic.domain.City as LogicCity
import opensamguk.logic.domain.General as LogicGeneral
import opensamguk.logic.domain.Nation as LogicNation

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
     * For an AI-controlled general ([isAiControlled]: `npc >= 2`, PHP `$general->isNPC()`), this hook
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
         * branch. False on a human turn and on an AI turn that honored the reserved command verbatim.
         */
        val autorunMode: Boolean = false,
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
     * The flow (R-SEAM §2 `TurnExecutionHelper.php:326-348`): for an AI-controlled general the [aiHook]
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

        // --- the GENERAL-pass AI interpose (R-SEAM §2 :332-336) ---
        // For an AI-controlled general the hook replaces the reserved command BEFORE resolve; the AI is
        // read-only over GAME ENTITIES (it returns (actionCode, RAW args)) — its meta-KV deltas route
        // through the AiTurnAdapter's recorder seam, never inline here.
        var actionCode = reserved.actionCode
        var args: Map<String, Any?> = decodeArgs(reserved.argJson)
        var autorunMode = false
        if (aiHook != null && isAiControlled(general)) {
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
        val env: Map<String, Any?> = WorldEnvBuilder.envMap(year, startYear)
        val worldEnv: WorldEnv = WorldEnvBuilder.worldEnv(year, startYear)

        val definition = registry.resolve(actionCode)

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
            destGeneralId = (args["destGeneralID"] as? Number)?.toInt(),
            destCityId = (args["destCityID"] as? Number)?.toInt(),
            destNationId = (args["destNationID"] as? Number)?.toInt(),
            env = env,
            mode = ConstraintMode.FULL,
        )
        val view = WorldStateViewAdapter(overlay, env = env)
        val result = evaluateConstraints(definition.buildConstraints(ctx), ctx, view)

        if (result !is ConstraintResult.Allow) {
            // Deny / Unknown → 휴식 fallback + the deny-reason log (PHP getFailString()).
            val reason = when (result) {
                is ConstraintResult.Deny -> result.reason
                is ConstraintResult.Unknown -> UNKNOWN_DENY_REASON
                ConstraintResult.Allow -> null // unreachable
            }
            if (reason != null) world.pushLog(denyLog(general, definition, reason, date))
            return HandledTurn(
                generalId = generalId,
                definition = registry.fallback,
                fellBack = true,
                denyReason = reason,
                logs = if (reason != null) listOf(reason) else emptyList(),
                env = env,
                args = args,
                autorunMode = autorunMode,
            )
        }

        // --- seed the per-action RNG (six-component PHP construction) ---
        val rng = RandUtil(
            LiteHashDrbg(serializeSeed(hiddenSeed, "generalCommand", year, month, generalId, definition.key)),
        )

        // --- resolve over a logic draft (the Immer-draft replacement) ---
        val preGeneral: LogicGeneral = PerTurnOverlay.toLogicGeneral(general)
        val preCity: LogicCity = PerTurnOverlay.toLogicCity(
            world.getCityById(cityId) ?: error("ReservedTurnHandler: city $cityId not in world"),
        )
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
        // the actor's (the resolver else falls back to meta["name"]). HandledTurn.args keeps the ORIGINAL
        // args (the parity oracle) — the founding preload never pollutes it.
        val isFounding = actionCode in FOUNDING_COMMANDS
        val resolveArgs = if (isFounding) buildFoundingArgs(actionCode, args, general, year, month) else args
        val resolveCtx = GeneralActionResolveContext(
            draft, rng, worldEnv, month, date,
            args = resolveArgs,
            generalName = if (isFounding) general.name else "",
            // 외교 제의 서신 validUntil(= date + max(30, turnterm*3)분) 공식이 읽는 per-game turnterm.
            turnterm = turnTerm,
            // 무작위건국: rng.choice가 소모하는 도시 id 목록. PHP `SELECT city FROM city WHERE level>=5 AND level<=6 AND nation=0`
            // 의 기본 정렬(= id 오름차순)이므로 id-ascending으로 정렬해 draw-for-draw 패러티를 유지한다.
            candidateCityIds = if (actionCode == MUJAKWI_GEONGUK) {
                world.listCities()
                    .filter { it.nationId == 0 && it.level in 5..6 }
                    .map { it.id }
                    .sorted()
            } else emptyList(),
        )
        definition.resolve(resolveCtx)

        // --- ChangeRecorder = the SINGLE dirty source ---
        recorder.diffGeneral(preGeneral, draft.general)
        recorder.diffCity(preCity, draft.city)

        // --- dirty-free apply: write the post-state engine rows; ChangeRecorder owns dirtiness ---
        world.applyGeneralDirtyFree(applyGeneralPatch(general, draft.general))
        world.applyCityDirtyFree(applyCityPatch(world.getCityById(cityId)!!, draft.city))

        // --- 외교 cascade 적용 (외교 수락/파기 등 nation-command가 일반 패스로 들어온 경우) ---
        // 외교 수락(불가침/종전/파기 수락)은 NationCommand로서 양방향 diplomacy 행을 draft.cascadeDiplomacy에
        // 누적한다. 일반 패스도 이 cascade를 ChangeRecorder 단일 dirty 소스를 통해 적용해야 행이 실제 전환된다
        // (ProcessNationCommand.dispatchRegistered의 diplomacyDeltas 적용 패턴과 동일). state/term/dead만
        // 전환되는 UPSERT이며, 사전 행이 없으면(현재 슬라이스 범위 밖) 건너뛴다.
        for (delta in draft.cascadeDiplomacy) {
            val pre = world.getDiplomacy(delta.me, delta.you) ?: continue
            world.updateDiplomacy(delta.me, delta.you, delta.state, delta.term)
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
        val postNation = draft.nation
        if (nation != null && postNation != null && postNation !== nation) {
            recorder.diffNation(nation, postNation)
            world.getNationById(nationId)?.let { world.applyNationDirtyFree(applyNationPatch(it, postNation)) }
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
        // CREATED-set (거병 INSERTs). Ordering is LOAD-BEARING: nation FIRST (the FK target + the frozen
        // step-3 contract general→nation→troop→diplomacy), then diplomacy, then nation_turn. The world's
        // LinkedHashSet/list preserves the enqueue order through consumeDirtyState.
        for (createdNation in draft.createdNations) world.createNation(PerTurnOverlay.toEngineNation(createdNation))
        for (createdDip in draft.createdDiplomacy) world.createDiplomacy(PerTurnOverlay.toEngineDiplomacy(createdDip))
        for (createdNationTurn in draft.createdNationTurns) world.createNationTurn(createdNationTurn)

        // --- logs ---
        for (line in resolveCtx.logs()) world.pushLog(actionLog(general, line))
        // The broadcast (general_id=0) action log — 거병's "<Y>{name}</>{josa} <G><b>{city}</b></>에 거병하였습니다."
        // headline (che_거병.php:161) routes here. Mirror ProcessNationCommand:207 so the founding broadcast
        // survives the general-pass handler (was silently dropped — only resolveCtx.logs() was drained).
        // (The per-target/PLAIN level-change side-bucket — resolveCtx.plainLogs()/dest logs — is a broader
        // general-pass log-routing parity item deferred to the WAVE 4 log/output pass.)
        for (line in resolveCtx.globalActionLogs()) world.pushLog(globalLog(general, line))

        // --- buffered Messages → mailbox channel (receiver row BEFORE sender row) ---
        for (message in resolveCtx.messages()) routeMessage(message, year, month)

        return HandledTurn(
            generalId = generalId,
            definition = definition,
            fellBack = false,
            denyReason = null,
            logs = resolveCtx.logs(),
            env = env,
            args = args,
            autorunMode = autorunMode,
        )
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
        world.applyGeneralDirtyFree(general.copy(meta = withMeta(general.meta, "killturn" to next)))
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
        world.applyGeneralDirtyFree(general.copy(meta = withMeta(general.meta, "killturn" to next)))
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

        // +1 lived_month inheritance (`:278` increaseInheritancePoint(lived_month, 1) is in the drain;
        // the inheritance accumulator rides meta in the slice).
        val livedMonth = metaInt(general, "lived_month", 0) + 1
        var current = general.copy(meta = withMeta(general.meta, "lived_month" to livedMonth))
        world.applyGeneralDirtyFree(current)

        // 삭턴장수 삭제처리 — killturn<=0 (LC2: kill() / possession-release).
        val killturn = metaInt(current, "killturn", 0)
        if (killturn <= 0) {
            val outcome = killOrReleasePossession(current, env)
            if (outcome == LifecycleOutcome.KILLED) return outcome // PHP returns out of updateTurnTime.
            // possession-release falls through to advance turntime.
            current = world.getGeneralById(generalId)!!
        }

        // 은퇴 — age>=retirementYear, human only (LC3: rebirth()). `General.php:209-216` gates the
        // applyDB+CheckHall on isunited==0; here the rebirth itself is gated on isunited==0 (B4 test
        // contract: rebirth must NOT fire on a unified server — and the daemon already freezes the
        // whole tick at isunited 2|3 upstream, so this only diverges for the transient isunited==1).
        var rebirthed = false
        if (current.age >= GameConst.retirementYear && current.npcState == 0 && env.isunited == 0) {
            rebirth(current, env)
            rebirthed = true
            current = world.getGeneralById(generalId)!!
        }

        // advance turntime by addTurn (the nextTurnTimeBase aux variant is out of the slice scope).
        world.applyGeneralDirtyFree(
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
     *    log FIRST, then `killturn=(deadyear-year)*12, npc=npc_org, owner=0, defence_train=80,
     *    owner_name=null`, then DELETE general_access_log ONLY (no-op in the V1 slice — no table).
     *  - else → [kill] (the F3 tombstone + 4-table delete).
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
                meta = withMeta(
                    general.meta,
                    "killturn" to (deadyear - env.year) * 12,
                    "owner" to 0,
                    "defence_train" to 80,
                    "owner_name" to null,
                ),
            )
            world.applyGeneralDirtyFree(released)
            // (3) DELETE general_access_log — NOT ported to the V1 baseline schema (no table): no-op.
            return LifecycleOutcome.POSSESSION_RELEASED
        }
        kill(general, env)
        return LifecycleOutcome.KILLED
    }

    /**
     * kill() — the tombstone + 4-table delete (`General.php:515-600`). Ordering is load-bearing for
     * the log byte-match: nextRuler/demote → troop cleanup → dying message → storeOldGeneral → the F3
     * delete-set finalize → nation gennum-1. (Inherit-point refund / select_pool null / the user
     * logger are out of the engine slice; the F3 [ChangeRecorder.markGeneralDeleted] performs
     * storeOldGeneral + the 4-table delete + clears updatedVar so the killed row never re-enters the
     * update-set, `General.php:595`.)
     */
    private fun kill(general: TurnGeneral, env: LifecycleEnv) {
        val generalId = general.id

        // 군주였으면 유지 이음 — officer_level==12 → nextRuler() then setVar('officer_level', 1) (:554-558).
        if (general.officerLevel == 12) {
            nextRuler(generalId, env)
            world.getGeneralById(generalId)?.let { world.applyGeneralDirtyFree(it.copy(officerLevel = 1)) }
        }

        // 부대 처리 — troop leader (troop == own id) → free all members + delete the troop (:560-570).
        if (general.troopId == generalId) {
            for (member in world.listGenerals().filter { it.troopId == generalId }) {
                world.applyGeneralDirtyFree(member.copy(troopId = 0))
            }
            world.removeTroop(generalId)
        }

        // dying message global log (:573-580) — default is the byte-exact $defaultMessage.
        world.pushLog(globalLog(general, dyingMessage(general)))

        // storeOldGeneral + the F3 4-table delete (tombstone; clears updatedVar — no double-apply).
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
        world.applyGeneralDirtyFree(reborn)

        // ALL 37 rank_data rows reset to 0 (setRankVar — a Set displaces any pending delta).
        for (col in RankColumn.entries) recorder.recordRankSet(general.id, col, 0)

        // the THREE distinct log pushes (:636-638) — order is load-bearing.
        val josaYi = JosaUtil.pick(general.name, "이")
        world.pushLog(globalLog(general, "<Y>${general.name}</>$josaYi <R>은퇴</>하고 그 자손이 유지를 이어받았습니다."))
        world.pushLog(actionLog(general, "나이가 들어 <R>은퇴</>하고 자손에게 자리를 물려줍니다."))
        world.pushLog(historyLog(general, "나이가 들어 은퇴하고, 자손에게 관직을 물려줌"))
    }

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
     * `init_year`/`init_month` ride the actor's nation meta (방랑국). `yearMonth <= initYearMonth`
     * → true → resolver early-returns with "다음 턴..." (no write).
     */
    private fun sameMonthOrBefore(general: TurnGeneral, year: Int, month: Int): Boolean {
        val nation = world.getNationById(general.nationId) ?: return false
        val initYear = (nation.meta["init_year"] as? Number)?.toInt() ?: return false
        val initMonth = (nation.meta["init_month"] as? Number)?.toInt() ?: 1
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
        /** Deny reason when the full-mode evaluator can't resolve a requirement (shouldn't occur in FULL). */
        const val UNKNOWN_DENY_REASON = "처리할 수 없습니다."

        /** The 휴식 (rest) command name — the killturn-decrement branch in [applyKillturnDecrement]. */
        const val REST_COMMAND = "휴식"

        /** che_거병 — the no-arg INSERT-created-set founding command (the prod crash seam). */
        const val GEOBYEONG = "che_거병"

        /** che_무작위건국 — the random-city founding command (needs candidateCityIds preload). */
        const val MUJAKWI_GEONGUK = "che_무작위건국"

        /**
         * Founding commands whose resolve seam is augmented with PHP-query preload args (and whose actor
         * name is threaded as the created nation's name). 거병 INSERTs a nation; 건국/cr_건국/무작위건국 UPDATE
         * a wandering nation (Bug B — WAVE 0b preload pending). Anything else passes through unchanged.
         */
        val FOUNDING_COMMANDS = setOf("che_거병", "che_건국", "cr_건국", "che_무작위건국")

        /** A lenient JSON reader for the stored `arg` jsonb (tolerant of trailing commas / lax keys). */
        private val ARG_JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * Whether [general] is AI-controlled — PHP `$general->isNPC()` (`npc >= 2`; the same threshold
         * the killturn-decrement / npcType branches already use). Humans (npc 0/1) never hit the hook.
         */
        internal fun isAiControlled(general: TurnGeneral): Boolean = general.npcState >= 2

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

        /** Read a lifecycle scalar as a string (e.g. `owner_name`), or null. */
        internal fun metaString(g: TurnGeneral, key: String): String? = g.meta[key] as? String

        /** Insertion-order-preserving meta merge (the jsonb the flush writes keeps PHP key order). */
        internal fun withMeta(meta: Map<String, Any?>, vararg pairs: Pair<String, Any?>): Map<String, Any?> {
            val out = LinkedHashMap(meta)
            for ((k, v) in pairs) out[k] = v
            return out
        }

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
                gold = post.gold,
                rice = post.rice,
                injury = post.injury,
                officerLevel = post.officerLevel,
                cityId = post.cityId,
                nationId = post.nationId,
                experience = phpRound(post.experience),
                dedication = phpRound(post.dedication),
                meta = post.meta,
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
                gold = logic.gold,
                rice = logic.rice,
                level = logic.level,
                typeCode = logic.typeCode,
                meta = logic.meta,
            )

        /**
         * Map the resolver's post-state logic [City] back onto the engine [City]. The engine City has
         * no `trust` column — `trust` lives in `meta["trust"]` (the inverse of [PerTurnOverlay.toLogicCity]).
         */
        private fun applyCityPatch(engine: City, post: LogicCity): City {
            val nextMeta = if (post.trust != (engine.meta["trust"] as? Number)?.toDouble()) {
                val m = LinkedHashMap(engine.meta); m["trust"] = post.trust; m
            } else {
                engine.meta
            }
            return engine.copy(
                level = post.level,
                commerce = post.commerce,
                commerceMax = post.commerceMax,
                agriculture = post.agriculture,
                agricultureMax = post.agricultureMax,
                supplyState = post.supplyState,
                frontState = post.frontState,
                nationId = post.nationId,
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

        /** Wrap a line as a `general` history [LogEntryDraft] (pushGeneralHistoryLog). */
        private fun historyLog(general: TurnGeneral, text: String): LogEntryDraft = LogEntryDraft(
            scope = "general",
            category = "history",
            text = text,
            generalId = general.id,
            nationId = general.nationId,
        )

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
            date: String,
        ): LogEntryDraft = LogEntryDraft(
            scope = "general",
            category = "action",
            text = "${definition.name} 실패: $reason <1>$date</>",
            generalId = general.id,
            nationId = general.nationId,
        )
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

    /** killturn<=0 (not possession) — kill() tombstoned the row (the F3 4-table delete). */
    KILLED,

    /** age>=retirementYear human — rebirth() in-place UPDATE, turntime advanced, NOT deleted. */
    REBIRTHED,
}
