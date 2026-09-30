package opensamguk.gameapi.precheck

import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.ConstraintMode
import opensamguk.logic.constraints.ConstraintResult
import opensamguk.logic.constraints.RequirementKey
import opensamguk.logic.constraints.evaluateConstraints
import opensamguk.logic.stats.GeneralActionPipeline
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service

/**
 * Step 1 of the 8-step flow — the outcome class the Next.js client + the E3 controller read.
 *
 * Mirrors `ConstraintResult` (Allow/Deny/Unknown) one-for-one; there is NO `needsInput` in the slice.
 */
sealed interface PrecheckResult {
    /** All constraints passed → the command is reservable. */
    data object Available : PrecheckResult

    /** A constraint denied → carries the PHP-faithful reason string (+ the failing constraint name). */
    data class Blocked(val reason: String, val constraintName: String? = null) : PrecheckResult

    /** PRECHECK mode hit an absent requirement (a row not loaded) → the missing keys. */
    data class Unknown(val missing: List<RequirementKey>) : PrecheckResult
}

/**
 * Task E2 — `CommandPrecheckService`. Loads the actor's general (via [PrecheckStateViewFactory]),
 * derives `cityId`/`nationId` from it, builds a `ConstraintContext(mode=PRECHECK)` over the
 * precheck [opensamguk.logic.statview.MemoryStateView], resolves the command through the SHARED
 * `:logic` [CommandRegistry], and runs the SAME `buildConstraints` + `evaluateConstraints` the daemon
 * runs in full mode. **No constraint logic is re-implemented here** — this is the proof of the single
 * shared constraint library (precheck == full).
 */
@Service
class CommandPrecheckService(
    private val stateViewFactory: PrecheckStateViewFactory,
    private val registry: CommandRegistry,
) {
    /** Run the precheck for [generalId] + [actionCode]. */
    fun precheck(generalId: Int, actionCode: String): PrecheckResult =
        precheck(generalId, actionCode, emptyMap())

    fun precheck(generalId: Int, actionCode: String, args: Map<String, Any?>): PrecheckResult {
        val state = stateViewFactory.build(generalId, args, loadAllCities = actionCode == "che_출병")
            ?: return PrecheckResult.Unknown(listOf(RequirementKey.General(generalId)))
        val definition = registry.resolve(actionCode)
        val parsedArgs = runCatching { definition.parseArgsForGeneral(args, generalId) }.getOrNull()
        if (parsedArgs == null || !definition.matchesArgsSchema(parsedArgs)) {
            return PrecheckResult.Blocked(INVALID_ARGS_DENY_REASON)
        }
        return evaluate(state, definition, parsedArgs)
    }

    private fun evaluate(
        state: PrecheckStateViewFactory.PrecheckState,
        definition: GeneralActionDefinition,
        args: Map<String, Any?> = emptyMap(),
    ): PrecheckResult {
        if (state.env["battlefieldPresent"] == true && definition.key !in setOf("che_전장이동", "che_휴식")) {
            return PrecheckResult.Blocked("전장에서 귀환한 뒤 도시 명령을 실행할 수 있습니다.", "BattlefieldLocation")
        }
        val ctx = context(state, args)
        val constraints = definition.buildConstraints(ctx)
        return when (val r = evaluateConstraints(constraints, ctx, state.view)) {
            ConstraintResult.Allow -> PrecheckResult.Available
            is ConstraintResult.Deny -> PrecheckResult.Blocked(r.reason, r.constraintName)
            is ConstraintResult.Unknown -> PrecheckResult.Unknown(r.missing)
        }
    }

    private fun context(
        state: PrecheckStateViewFactory.PrecheckState,
        args: Map<String, Any?> = emptyMap(),
    ): ConstraintContext {
        val actor = state.actor
        return ConstraintContext(
            actorId = actor.id,
            cityId = actor.cityId,
            nationId = actor.nationId,
            destGeneralId = (args["destGeneralID"] as? Number)?.toInt(),
            destCityId = (args["destCityID"] as? Number)?.toInt(),
            destNationId = (args["destNationID"] as? Number)?.toInt(),
            args = args,
            env = state.env,
            mode = ConstraintMode.PRECHECK,
            worldMapVariant = state.worldMapVariant,
        )
    }

    private companion object {
        const val INVALID_ARGS_DENY_REASON = "인자가 올바르지 않습니다."
    }
}

/**
 * Bean wiring for the shared `:logic` command resolution. P1 wires ZERO action-stack modules (the
 * empty-pipeline identity fold — §14), so the [GeneralActionPipeline] is empty here; the daemon side
 * constructs its own registry against the same empty pipeline.
 */
@Configuration
class PrecheckBeans {
    @Bean
    fun generalActionPipeline(): GeneralActionPipeline = GeneralActionPipeline()

    @Bean
    fun commandRegistry(pipeline: GeneralActionPipeline): CommandRegistry = CommandRegistry(pipeline)
}
