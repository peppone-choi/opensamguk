package opensamguk.engine.turn

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.ConstraintMode
import opensamguk.logic.constraints.ConstraintResult
import opensamguk.logic.constraints.evaluateConstraints
import opensamguk.logic.domain.WorldEnv
import opensamguk.logic.statview.WorldEnvBuilder
import opensamguk.logic.util.phpRound
import opensamguk.logic.domain.City as LogicCity
import opensamguk.logic.domain.General as LogicGeneral

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
    )

    /**
     * Handle ONE reserved turn for [generalId].
     *
     * @param actionCode the reserved action code (from the `general_turn` ring / enqueued command).
     * @param year the game year (RNG seed component 3 + cost env).
     * @param month the game month (RNG seed component 4).
     * @param date the turn-time `HH:MM` for the action log `<1>date</>` suffix.
     */
    fun handle(generalId: Int, actionCode: String, year: Int, month: Int, date: String): HandledTurn {
        val general = world.getGeneralById(generalId)
            ?: error("ReservedTurnHandler: general $generalId not in world")
        val cityId = general.cityId
        val nationId = general.nationId

        // ONE env, built by THE shared env-builder (same call as E2 precheck — cannot drift).
        val env: Map<String, Any?> = WorldEnvBuilder.envMap(year, startYear)
        val worldEnv: WorldEnv = WorldEnvBuilder.worldEnv(year, startYear)

        val definition = registry.resolve(actionCode)

        // --- FULL-mode constraints over the live world (the SAME :logic constraint library) ---
        val overlay = PerTurnOverlay(world)
        val ctx = ConstraintContext(
            actorId = generalId,
            cityId = cityId,
            nationId = nationId,
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
        val resolveCtx = GeneralActionResolveContext(draft, rng, worldEnv, month, date)
        definition.resolve(resolveCtx)

        // --- ChangeRecorder = the SINGLE dirty source ---
        recorder.diffGeneral(preGeneral, draft.general)
        recorder.diffCity(preCity, draft.city)

        // --- dirty-free apply: write the post-state engine rows; ChangeRecorder owns dirtiness ---
        world.applyGeneralDirtyFree(applyGeneralPatch(general, draft.general))
        world.applyCityDirtyFree(applyCityPatch(world.getCityById(cityId)!!, draft.city))

        // --- logs ---
        for (line in resolveCtx.logs()) world.pushLog(actionLog(general, line))

        return HandledTurn(
            generalId = generalId,
            definition = definition,
            fellBack = false,
            denyReason = null,
            logs = resolveCtx.logs(),
            env = env,
        )
    }

    /** The recorder is the lone dirty source; exposed so the flush (F4)/tests can read its patches. */
    val recorder: ChangeRecorder = ChangeRecorder()

    companion object {
        /** Deny reason when the full-mode evaluator can't resolve a requirement (shouldn't occur in FULL). */
        const val UNKNOWN_DENY_REASON = "처리할 수 없습니다."

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
