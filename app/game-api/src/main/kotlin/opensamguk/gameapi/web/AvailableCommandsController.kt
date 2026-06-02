package opensamguk.gameapi.web

import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.precheck.CommandPrecheckService
import opensamguk.gameapi.precheck.PrecheckResult
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
        val definitions: List<GeneralActionDefinition> = GENERAL_COMMAND_CODES.map { registry.resolve(it) }

        // Real precheck (possible/reason) when we have an actor; registry-only (possible=true) otherwise.
        val results: Map<String, PrecheckResult>? =
            effectiveId?.let { precheck.precheckAll(it, definitions) }

        val rows = definitions.map { def -> toRow(def, results?.get(def.key)) }
        val table = rows
            .groupBy { it.category }
            .map { (category, values) -> CommandCategory(category, values) }
        return ResponseEntity.ok(AvailableCommandsResponse(result = true, commandTable = table))
    }

    private fun toRow(def: GeneralActionDefinition, result: PrecheckResult?): AvailableCommand {
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
            category = def.category,
            compensation = 0,
            possible = possible,
            reqArg = reqArg,
            argType = argTypeOf(def.argsSchema.keys),
            reason = reason,
        )
    }

    /** Derive the modal field-type from the command's declared `argsSchema` keys (faithful, not guessed). */
    private fun argTypeOf(keys: Set<String>): String? = when {
        "destCityID" in keys -> "city"
        "destNationID" in keys -> "nation"
        "destGeneralID" in keys -> "general"
        "amount" in keys -> "amount"
        else -> null
    }

    companion object {
        private const val UNKNOWN_REASON = "정보 부족"

        /**
         * The player-reservable general command codes, mirroring `CommandRegistry.resolve`'s general
         * `che_*`/`cr_*` entries (the registry exposes no `listAll()`, so the catalog is enumerated
         * here in game-api). EXCLUDED: the diplomacy-accept triggers (che_불가침수락/che_종전수락/
         * che_불가침파기수락 — fired by message accept, not directly reservable), che_NPC능동 (NPC-only).
         * Nation-internal commands (감축/증축/발령/포상/국호변경/국기변경/천도/무작위수도이전) ride the
         * separate nation_turn ring and are NOT part of the per-general catalog.
         */
        val GENERAL_COMMAND_CODES: List<String> = listOf(
            // 내정 develop
            "che_상업투자", "che_농지개간", "che_성벽보수", "che_수비강화", "che_치안강화",
            "che_기술연구", "che_정착장려", "che_주민선정", "che_물자조달", "che_군량매매",
            // 군사 military
            "che_징병", "che_모병", "che_훈련", "cr_맹훈련", "che_사기진작", "che_소집해제",
            "che_귀환", "che_견문",
            // 인사 personnel / movement
            "che_이동", "che_집합", "che_임관", "che_장수대상임관", "che_하야", "che_방랑",
            "che_랜덤임관", "che_은퇴", "che_등용", "che_인재탐색",
            // 건국/거병 founding
            "che_거병", "che_건국", "cr_건국", "che_무작위건국", "che_해산",
            // 출병 war
            "che_출병",
            // 자원교역 trade
            "che_증여", "che_헌납", "che_장비매매",
            // 외교 diplomacy proposals
            "che_불가침제의", "che_종전제의", "che_불가침파기제의", "che_선전포고",
            // 요양 (dispatcher-direct, gate-exempt)
            "che_요양",
        )
    }
}
