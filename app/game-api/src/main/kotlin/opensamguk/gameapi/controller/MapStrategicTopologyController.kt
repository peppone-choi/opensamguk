package opensamguk.gameapi.controller

import opensamguk.gameapi.read.StrategicTopologyReadSource
import opensamguk.gameapi.read.WaterControlReadRepository
import opensamguk.gameapi.read.ActiveWorldArtifactResolver
import opensamguk.gameapi.read.GameKvReadRepository
import opensamguk.gameapi.dto.StrategicTopologyBinding
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import opensamguk.gameapi.read.ActiveWorldMap
import opensamguk.gameapi.dto.StrategicTopologyResponse
import opensamguk.gameapi.dto.StrategicWaterControlDto
import opensamguk.logic.input.HwihaLandPassageState
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.dao.DataAccessException
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/map")
class MapStrategicTopologyController(
    private val worlds: ActiveWorldArtifactResolver,
    private val controls: WaterControlReadRepository,
    private val source: StrategicTopologyReadSource,
    private val gameKv: GameKvReadRepository? = null,
) {
    /** Public immutable geography; until water FOW exists, only verified administrators read control. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    @GetMapping("/strategic-topology")
    fun topology(@RequestParam(required = false) knownTopologyHash: String?): ResponseEntity<*> {
        val selected = worlds.resolve() ?: return ResponseEntity.notFound().build<Any>()
        val activeWorld = selected.world
        if (ActiveWorldMap.requireName(activeWorld) != "han-world-v3") return ResponseEntity.notFound().build<Any>()
        val projection = requireNotNull(selected.artifacts).projection
        val binding = StrategicTopologyBinding.from(activeWorld.id, projection)
        val auth = SecurityContextHolder.getContext().authentication
        val principalId = auth?.principal as? Long
        val mayReadControl = auth?.isAuthenticated == true && principalId != null && principalId > 0
            && auth.authorities.any { it.authority == "ROLE_ADMIN" }
        // Identity pins are validated above; ownership and blockade state remain admin-only.
        val snapshot = if (mayReadControl) controls.readSnapshot(activeWorld.id, projection.topology) else null
        val rows = projection.topology.waterZones.sortedBy { it.id }.map { zone ->
            val state = snapshot?.stateFor(zone.id)
            StrategicWaterControlDto(zone.id, state?.blockadeState?.name ?: "UNKNOWN",
                state?.controllingNationId?.toString(), state?.contestingNationIds?.map(Long::toString).orEmpty(),
                state?.revision?.toString())
        }
        val roadGates = projection.presentation?.roadGates.orEmpty()
        val roads = if (roadGates.isNotEmpty()) {
            val raw = gameKv?.findByTableAndNamespaceAndKey("game_env", "game_env", HwihaLandPassageState.META_KEY)?.value
            val state = if (raw == null) null else {
                val decoded = ObjectMapper().readValue(raw, Map::class.java)
                HwihaLandPassageState.read(mapOf(HwihaLandPassageState.META_KEY to decoded), projection.topology)
            }
            (state?.edgeStates?.filter { it.value.active }?.keys ?: roadGates
                .filter { it.initiallyBuilt }.map { it.edgeId }).sorted()
        } else null
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(StrategicTopologyResponse(
            binding, if (knownTopologyHash == binding.topologyHash) null else source.presentationFor(projection),
            if (mayReadControl) "VISIBLE" else "REDACTED", rows,
            roads,
        ))
    }
}

/** Convert validation failures only after the read transaction has rolled back. */
@RestControllerAdvice(assignableTypes = [MapStrategicTopologyController::class, TerrainMapController::class, MapPreviewController::class])
class MapStrategicTopologyErrors {
    @ExceptionHandler(IllegalArgumentException::class, IllegalStateException::class, DataAccessException::class)
    fun invalidState(): ResponseEntity<*> = ResponseEntity.status(409).cacheControl(CacheControl.noStore())
        .body(mapOf("code" to "STRATEGIC_STATE_INVALID", "reason" to "수역 상태를 검증할 수 없습니다."))
}
