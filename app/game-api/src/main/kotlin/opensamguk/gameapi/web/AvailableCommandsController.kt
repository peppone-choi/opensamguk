package opensamguk.gameapi.web

import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.precheck.CommandPrecheckService
import opensamguk.gameapi.precheck.PrecheckResult
import opensamguk.common.constants.GameConst
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.actions.GeneralActionDefinition
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * F2 Wave 6 — `GET /api/commands/available?generalId=` (spec §5). The command catalog the W5
 * `CommandModal` drives: category tabs → command grid → per-command availability + arg-form routing.
 *
 * Mirrors legacy `GetCommandTable` / core2026 `buildTurnCommandTable`: a `commandTable` of
 * `{category, values:[{value, simpleName, title, category, compensation, possible, reqArg, argType?,
 * reason?}]}`. EVERY field is sourced from the SHARED `:logic` [CommandRegistry] definition — never
 * fabricated:
 *   * `value`        = `definition.key` (the reserve action-code).
 *   * `simpleName`   = `definition.name`.
 *   * `title`        = `definition.name` (the registry carries NO separate title/description string;
 *                       the W5 modal renders title as a subtext — flagged: a real per-command title
 *                       needs the legacy `getName()`/description, not present in the Kotlin model).
 *   * `category`     = `definition.category`.
 *   * `compensation` = `0` (the registry carries NO compensation ▲/▼ value; flagged — legacy
 *                       `getCompensation()` is not yet ported into [GeneralActionDefinition]).
 *   * `reqArg`       = `definition.argsSchema.isNotEmpty()`.
 *   * `argType`      = derived from `argsSchema` keys (destCityID→city, destNationID→nation,
 *                       destGeneralID→general, amount→amount); null when no recognized arg key.
 *   * `possible`/`reason` = the REAL precheck outcome ([CommandPrecheckService.precheckAll], precheck
 *                       == full): the SAME constraint library the daemon runs, evaluated read-only
 *                       over the last-flushed DB rows. NOT a default — when the actor row is absent
 *                       (no general) we fall back to a registry-only catalog (`possible=true`).
 *
 * Identity: the verified JWT principal resolves the caller's own general; the `?generalId=` query
 * param is the F2 transition fallback (gated OFF — 403 — when it does not match the principal's
 * owned general, Task 4 hardening).
 */
@RestController
@RequestMapping("/api")
class AvailableCommandsController(
    private val resolver: GeneralResolver,
    private val precheck: CommandPrecheckService,
    private val registry: CommandRegistry,
) {

    /** One catalog row (matches web/game `AvailableCommand`). */
    data class AvailableCommand(
        val value: String,
        val simpleName: String,
        val title: String,
        val category: String,
        val compensation: Int,
        val possible: Boolean,
        val reqArg: Boolean,
        val argType: String? = null,
        val reason: String? = null,
    )

    /** A category bucket (matches web/game `AvailableCommandCategory`). */
    data class CommandCategory(val category: String, val values: List<AvailableCommand>)

    /** The envelope (matches web/game `AvailableCommandsResponse`). */
    data class AvailableCommandsResponse(
        val result: Boolean,
        val commandTable: List<CommandCategory>,
    )

    @GetMapping("/commands/available")
    fun availableCommands(
        @AuthenticationPrincipal userId: Long?,
        @RequestParam(required = false) generalId: Int?,
    ): ResponseEntity<Any> {
        // Identity: principal first; else the transition ?generalId= fallback.
        // Task 4 hardening — when authenticated, the passed generalId MUST be the caller's own.
        val resolvedId = userId?.let { resolver.resolveGeneralId(it) }
        if (userId != null && generalId != null && generalId != resolvedId) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }
        val effectiveId = resolvedId ?: generalId

        // Resolve every reservable general command through the SHARED registry (definitions, not codes).
        val catalog: List<Pair<String, GeneralActionDefinition>> = GameConst.availableGeneralCommand
            .flatMap { (category, codes) -> codes.map { code -> category to registry.resolve(code) } }
        val definitions = catalog.map { it.second }

        // Real precheck (possible/reason) when we have an actor; registry-only (possible=true) otherwise.
        val results: Map<String, PrecheckResult>? =
            effectiveId?.let { precheck.precheckAll(it, definitions) }

        val table = catalog
            .groupBy({ it.first }, { (category, def) -> toRow(def, results?.get(def.key), category) })
            .map { (category, values) -> CommandCategory(category, values) }
        return ResponseEntity.ok(AvailableCommandsResponse(result = true, commandTable = table))
    }

    private fun toRow(def: GeneralActionDefinition, result: PrecheckResult?, legacyCategory: String): AvailableCommand {
        val reqArg = def.argsSchema.isNotEmpty()
        val (possible, reason) = when (result) {
            null -> true to null // no actor → registry-only catalog (possible defaults true, no deny reason)
            PrecheckResult.Available -> true to null
            is PrecheckResult.Blocked -> false to result.reason
            is PrecheckResult.Unknown -> {
                // PRECHECK hit an absent requirement (e.g. a target row not loaded): reqArg commands
                // simply need a target chosen (possible — the modal opens the picker); a non-arg
                // command with missing inputs is genuinely undeterminable here.
                if (reqArg) true to null else false to UNKNOWN_REASON
            }
        }
        return AvailableCommand(
            value = def.key,
            simpleName = def.name,
            title = def.name,
            category = legacyCategory,
            compensation = 0,
            possible = possible,
            reqArg = reqArg,
            argType = argTypeOf(def.argsSchema.keys),
            reason = reason,
        )
    }

    /** Derive the modal field-type from the command's declared `argsSchema` keys (faithful, not guessed). */
    private fun argTypeOf(keys: Set<String>): String? = when {
        "nationName" in keys && "nationType" in keys && "colorType" in keys -> "founding"
        "crewType" in keys && "amount" in keys -> "recruit"
        "destCityID" in keys -> "city"
        "destNationID" in keys -> "nation"
        "destGeneralID" in keys -> "general"
        "amount" in keys -> "amount"
        else -> null
    }

    companion object {
        private const val UNKNOWN_REASON = "정보 부족"
    }
}
