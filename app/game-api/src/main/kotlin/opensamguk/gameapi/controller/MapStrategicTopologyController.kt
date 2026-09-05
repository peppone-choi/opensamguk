package opensamguk.gameapi.controller

import opensamguk.gameapi.read.StrategicTopologyReadSource
import opensamguk.gameapi.read.WaterControlReadRepository
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.gameapi.read.ActiveWorldMap
import opensamguk.gameapi.dto.StrategicTopologyResponse
import opensamguk.gameapi.dto.StrategicWaterControlDto
import org.springframework.dao.DataAccessException
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/map")
class MapStrategicTopologyController(
    private val world: WorldStateReadRepository,
    private val controls: WaterControlReadRepository,
    private val source: StrategicTopologyReadSource,
) {
    /** Public immutable geography; until water FOW exists, only verified administrators read control. */
    @GetMapping("/strategic-topology")
    fun topology(@RequestParam(required = false) knownTopologyHash: String?): ResponseEntity<*> {
        val activeWorld = world.findProcessWorld() ?: return ResponseEntity.notFound().build<Any>()
        try {
            if (ActiveWorldMap.requireName(activeWorld) != "han-world-v3") return ResponseEntity.notFound().build<Any>()
            val projection = source.projection
            val binding = source.binding(activeWorld.id)
            val auth = SecurityContextHolder.getContext().authentication
            val principalId = auth?.principal as? Long
            val mayReadControl = auth?.isAuthenticated == true && principalId != null && principalId > 0
                && auth.authorities.any { it.authority == "ROLE_ADMIN" }
            // Do not query private rows for redacted requests, even to calculate a revision/count/hash.
            val snapshot = if (mayReadControl) controls.readSnapshot(activeWorld.id, projection.topology) else null
            val rows = projection.topology.waterZones.sortedBy { it.id }.map { zone ->
                val state = snapshot?.stateFor(zone.id)
                StrategicWaterControlDto(zone.id, state?.blockadeState?.name ?: "UNKNOWN",
                    state?.controllingNationId?.toString(), state?.contestingNationIds?.map(Long::toString).orEmpty(),
                    state?.revision?.toString())
            }
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(StrategicTopologyResponse(
                binding, if (knownTopologyHash == binding.topologyHash) null else source.presentation,
                if (mayReadControl) "VISIBLE" else "REDACTED", rows,
            ))
        } catch (_: IllegalArgumentException) {
            return invalidState()
        } catch (_: IllegalStateException) {
            return invalidState()
        } catch (_: DataAccessException) {
            return invalidState()
        }
    }

    private fun invalidState(): ResponseEntity<*> = ResponseEntity.status(409).cacheControl(CacheControl.noStore())
        .body(mapOf("code" to "STRATEGIC_STATE_INVALID", "reason" to "수역 상태를 검증할 수 없습니다."))
}
