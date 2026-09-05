package opensamguk.gameapi.dto

import com.fasterxml.jackson.annotation.JsonInclude
import opensamguk.logic.world.HanStrategicRouteProjection
import opensamguk.logic.world.StrategicWaterGeometry

data class StrategicTopologyBinding(
    val worldId: Int,
    val mapCode: String,
    val topologyRevision: String,
    val topologyHash: String,
    val baseTilesSha256: String,
    val cols: Int,
    val rows: Int,
) {
    companion object {
        fun from(worldId: Int, projection: HanStrategicRouteProjection): StrategicTopologyBinding {
            val display = requireNotNull(projection.presentation) { "Validated strategic presentation is missing" }
            return StrategicTopologyBinding(worldId, "han-world-v3", projection.topology.topologyRevision,
                projection.topology.contentHash, display.baseTilesSha256, display.cols, display.rows)
        }
    }
}

data class StrategicWaterZoneDto(
    val id: String, val kind: String, val geometryRef: String, val connectionStatus: String,
    val confidence: String, val seasonalAvailability: String,
)

data class StrategicTraversalEdgeDto(
    val id: String, val from: String, val to: String, val mode: String,
    val movementCost: Int, val capacity: Int, val seasonalAvailability: String, val supplyAllowed: Boolean,
)

data class StrategicRiverBarrierDto(val id: String, val firstLandProvinceId: String, val secondLandProvinceId: String)
data class StrategicPortDto(val edgeId: String, val landProvinceId: String, val waterZoneId: String)

data class StrategicMapTopologyDto(
    val landProvinceIds: List<String>,
    val geometries: List<StrategicWaterGeometry>,
    val waterZones: List<StrategicWaterZoneDto>,
    val traversalEdges: List<StrategicTraversalEdgeDto>,
    val riverBarriers: List<StrategicRiverBarrierDto>,
    val ports: List<StrategicPortDto>,
    val activationBlockerCodes: List<String>,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class StrategicWaterControlDto(
    val waterZoneId: String,
    val status: String,
    /** Decimal strings preserve bigint identity and revisions in JavaScript. */
    val controllingNationId: String? = null,
    val contestingNationIds: List<String> = emptyList(),
    val revision: String? = null,
)

data class StrategicTopologyResponse(
    val binding: StrategicTopologyBinding,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val topology: StrategicMapTopologyDto?,
    val controlVisibility: String,
    val controls: List<StrategicWaterControlDto>,
)
