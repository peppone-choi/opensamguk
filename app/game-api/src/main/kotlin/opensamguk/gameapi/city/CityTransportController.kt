package opensamguk.gameapi.city

import opensamguk.gameapi.command.CityCommandPrecheckService

import opensamguk.gameapi.command.validateCommandArguments
import opensamguk.gameapi.command.commandError
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.gameapi.dto.IntakeAcceptedResponse
import opensamguk.gameapi.reserve.CommandReserveService
import opensamguk.infra.sandbox.SandboxGate
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import opensamguk.logic.command.CommandAvailability
import opensamguk.logic.command.CityTransportArgs
import opensamguk.logic.world.ResolvedStrategicPath
import com.fasterxml.jackson.annotation.JsonInclude

data class CityTransportRoutePreview(
    val status: String,
    val code: String? = null,
    val reason: String? = null,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val route: CityTransportRoute? = null,
    val worldId: Int? = null,
)

data class CityTransportRoute(
    val nodeKeys: List<String>,
    val edgeIds: List<String>,
    val modes: List<String>,
    val totalCost: Long,
    val capacity: Int,
    val topologyRevision: String,
    val topologyHash: String,
    val pathHash: String,
) {
    companion object {
        fun from(path: ResolvedStrategicPath): CityTransportRoute = CityTransportRoute(
            path.nodeKeys, path.edgeIds, path.modes.map { it.name }, path.totalCost, path.capacity,
            path.topologyRevision, path.topologyHash, path.pathHash,
        )
    }
}

/**
 * OPENSAM-154 (v2 R5) — v2 도시 자원 수송 인테이크 엔드포인트. **v2 샌드박스 전용, 새 파일**
 * (v1 인테이크 분류기를 확장하지 않는다). 게이트·소유권 가드·202 회신 규약은
 * [GarrisonRecruitController](R4)와 동형이다.
 */
@RestController
@Profile(SandboxGate.PROFILE)
@ConditionalOnProperty(name = [SandboxGate.PROPERTY], havingValue = "true", matchIfMissing = false)
@RequestMapping("/api/v2/city-transport")
class CityTransportController(
    private val reserve: CommandReserveService,
    private val resolver: GeneralResolver,
    private val contextual: CityCommandPrecheckService,
    processWorld: GameApiProcessWorld,
) {
    private val worldId = processWorld.worldId.value
    /** Read-only authoritative preview; never reserves a command or changes a ledger. */
    @PostMapping("/route")
    fun route(
        @AuthenticationPrincipal userId: Long?,
        @RequestParam generalId: Int,
        @RequestBody(required = false) argJson: String? = null,
    ): ResponseEntity<CityTransportRoutePreview> {
        if (userId == null || userId <= 0 || userId > Int.MAX_VALUE.toLong()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }
        if (generalId != resolver.resolveGeneralId(userId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }
        val available = validateCommandArguments("v2CityTransport", argJson)
        val preview = when (available) {
            is CommandAvailability.Available -> contextual.previewTransport(generalId, available.args as CityTransportArgs)
            is CommandAvailability.Blocked -> CityTransportRoutePreview("BLOCKED", available.code, available.reason)
            is CommandAvailability.NeedsInput -> CityTransportRoutePreview("BLOCKED", "INVALID_ARGUMENTS", "수송 인자가 부족합니다.")
            is CommandAvailability.Unknown -> CityTransportRoutePreview("BLOCKED", available.code, "수송 명령을 찾을 수 없습니다.")
        }
        return ResponseEntity.ok(preview.copy(worldId = worldId))
    }

    /** `POST /api/v2/city-transport?generalId=` — `{fromCityId, toCityId, gold, rice, garrison}` 본문. */
    @PostMapping
    fun transport(
        @AuthenticationPrincipal userId: Long?,
        @RequestParam generalId: Int,
        @RequestBody(required = false) argJson: String? = null,
        @RequestParam(required = false) expectedWorldId: Int? = null,
    ): ResponseEntity<Any> {
        if (userId == null || userId <= 0 || userId > Int.MAX_VALUE.toLong()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }
        if (generalId != resolver.resolveGeneralId(userId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }
        // A proxy server may be reset to another world with the same immutable topology.
        // Optional only for existing clients; scoped preview consumers must echo the origin.
        if (expectedWorldId != null && expectedWorldId != worldId) {
            return CommandAvailability.Blocked(
                "ROUTE_WORLD_STALE", "세계가 변경되었습니다. 수송 경로를 다시 확인해주세요.",
            ).commandError("v2CityTransport")
        }
        val availability = validateCommandArguments("v2CityTransport", argJson)
        if (availability !is CommandAvailability.Available) return availability.commandError("v2CityTransport")
        val checked = contextual.precheck(generalId, availability)
        if (checked !is CommandAvailability.Available) return checked.commandError("v2CityTransport")
        val reserved = reserve.reserveForOwner(
            generalId = generalId,
            actionCode = "v2CityTransport",
            turnIdx = 0,
            argJson = argJson,
            ownerUserId = Math.toIntExact(userId),
        )
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(
                IntakeAcceptedResponse(
                    status = "AVAILABLE",
                    requestId = reserved.requestId,
                    code = "v2CityTransport",
                ),
            )
    }
}
