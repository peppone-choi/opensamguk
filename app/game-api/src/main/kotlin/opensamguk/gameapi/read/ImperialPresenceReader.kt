package opensamguk.gameapi.read

import com.fasterxml.jackson.annotation.JsonInclude
import opensamguk.logic.imperial.ImperialLineStatus
import opensamguk.logic.imperial.ImperialPresenceProjection
import opensamguk.logic.imperial.ImperialWorldCodec
import opensamguk.logic.world.StrategicNodeRef
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

data class ImperialPresenceResponse(
    val status: String,
    val badges: List<ImperialPresenceBadgeResponse>,
)

data class ImperialPresenceBadgeResponse(
    val lineCode: String,
    val lineName: String,
    val emperorGeneralId: Int,
    val emperorNodeKind: String,
    val emperorNodeId: String,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val emperorCityId: Int?,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val courtCityId: Int?,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val emperorName: String?,
)

/** Read imperial state and the emperor's validated spatial position from one database snapshot. */
@Component
class ImperialPresenceReader(
    private val worlds: WorldStateReadRepository,
    private val generals: GeneralReadRepository,
    private val artifacts: ActiveWorldArtifactResolver,
    private val spatial: SpatialStateReadRepository,
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun read(): ImperialPresenceResponse {
        val world = worlds.findProcessWorld()
            ?: return ImperialPresenceResponse("STATE_UNAVAILABLE", emptyList())
        return try {
            val imperial = ImperialWorldCodec.read(world.meta)
                ?: return ImperialPresenceResponse("NOT_SEEDED", emptyList())
            val selected = requireNotNull(artifacts.resolve())
            require(selected.world.id == world.id)
            val projection = requireNotNull(selected.artifacts).projection
            val positions = spatial.readSnapshot(world.id, projection.topology).generalPositionSnapshot
            val emperorGenerals = imperial.houses.asSequence()
                .filter { it.status == ImperialLineStatus.ACTIVE }
                .mapNotNull { it.holderGeneralId }
                .distinct()
                .mapNotNull { id -> generals.findById(id).orElse(null)?.takeIf { it.worldId == world.id }
                    ?.let { id to it } }
                .toMap()
            val referenceCities = emperorGenerals.mapValues { (_, general) -> general.cityId }
            val cityProvinces = projection.bindingsByCityId.mapNotNull { (cityId, binding) ->
                binding.landProvinceId?.let { cityId to it }
            }.toMap()
            val badges = ImperialPresenceProjection.badges(imperial, referenceCities,
                positions.statesByGeneralId, cityProvinces).map {
                val kind = when (it.emperorNode) {
                    is StrategicNodeRef.LandProvince -> "LAND_PROVINCE"
                    is StrategicNodeRef.WaterZone -> "WATER_ZONE"
                }
                val nodeId = when (val node = it.emperorNode) {
                    is StrategicNodeRef.LandProvince -> node.id
                    is StrategicNodeRef.WaterZone -> node.id
                }
                ImperialPresenceBadgeResponse(it.lineCode, it.lineName, it.emperorGeneralId,
                    kind, nodeId, it.emperorCityId, it.courtCityId,
                    emperorGenerals.getValue(it.emperorGeneralId).name.takeIf { name -> name.isNotBlank() })
            }
            ImperialPresenceResponse("READY", badges)
        } catch (_: IllegalArgumentException) {
            ImperialPresenceResponse("STATE_UNAVAILABLE", emptyList())
        } catch (_: IllegalStateException) {
            ImperialPresenceResponse("STATE_UNAVAILABLE", emptyList())
        }
    }
}
