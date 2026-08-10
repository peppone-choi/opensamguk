package opensamguk.gameapi.web

import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.precheck.CommandPrecheckService
import opensamguk.gameapi.precheck.PrecheckResult
import opensamguk.gameapi.precheck.RecruitCrewTypeAvailability
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

    /** A category bucket (matches web/game `AvailableCommandCategory`). */
    data class CommandCategory(val category: String, val values: List<CommandCatalogRow>)

    /** The envelope (matches web/game `AvailableCommandsResponse`). */
    data class AvailableCommandsResponse(
        val result: Boolean,
        val commandTable: List<CommandCategory>,
    )

    data class RecruitAvailabilityResponse(
        val result: Boolean,
        val unitSet: String?,
        val crewTypes: List<RecruitCrewTypeAvailability>,
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
            .groupBy(
                { it.first },
                { (category, def) -> CommandCatalogRowFactory.create(def, results?.get(def.key), category) },
            )
            .map { (category, values) -> CommandCategory(category, values) }
        return ResponseEntity.ok(AvailableCommandsResponse(result = true, commandTable = table))
    }

    @GetMapping("/commands/recruit/availability")
    fun recruitAvailability(
        @AuthenticationPrincipal userId: Long?,
        @RequestParam(required = false) generalId: Int?,
    ): ResponseEntity<Any> {
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }
        val resolvedId = resolver.resolveGeneralId(userId)
            ?: return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        if (generalId != null && generalId != resolvedId) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }
        val availability = precheck.recruitAvailability(resolvedId)
        return ResponseEntity.ok(
            RecruitAvailabilityResponse(
                result = availability?.supported == true,
                unitSet = availability?.unitSet,
                crewTypes = availability?.crewTypes ?: emptyList(),
            ),
        )
    }
}
