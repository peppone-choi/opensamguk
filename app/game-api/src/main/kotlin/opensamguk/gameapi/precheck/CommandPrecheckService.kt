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
    fun precheck(generalId: Int, actionCode: String): PrecheckResult {
        val state = stateViewFactory.build(generalId)
            ?: return PrecheckResult.Unknown(listOf(RequirementKey.General(generalId)))
        return evaluate(state, registry.resolve(actionCode))
    }

    /**
     * Catalog-mode precheck — builds the actor state ONCE (one DB read pass) and evaluates EACH
     * [GeneralActionDefinition] in [definitions] against it, returning the per-command
     * [PrecheckResult]. Used by the available-commands catalog so the `possible`/`reason` flags are
     * the REAL `:logic` constraint outcome (precheck == full), not a fabricated default.
     *
     * Returns `null` when the actor general row is absent (the whole catalog is then unavailable);
     * the caller decides whether to surface a registry-only catalog with `possible=true`.
     */
    fun precheckAll(generalId: Int, definitions: List<GeneralActionDefinition>): Map<String, PrecheckResult>? {
        val state = stateViewFactory.build(generalId) ?: return null
        return definitions.associate { def -> def.key to evaluate(state, def) }
    }

    private fun evaluate(
        state: PrecheckStateViewFactory.PrecheckState,
        definition: GeneralActionDefinition,
    ): PrecheckResult {
        val actor = state.actor
        val ctx = ConstraintContext(
            actorId = actor.id,
            cityId = actor.cityId,
            nationId = actor.nationId,
            env = state.env,
            mode = ConstraintMode.PRECHECK,
        )
        val constraints = definition.buildConstraints(ctx)
        return when (val r = evaluateConstraints(constraints, ctx, state.view)) {
            ConstraintResult.Allow -> PrecheckResult.Available
            is ConstraintResult.Deny -> PrecheckResult.Blocked(r.reason, r.constraintName)
            is ConstraintResult.Unknown -> PrecheckResult.Unknown(r.missing)
        }
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
