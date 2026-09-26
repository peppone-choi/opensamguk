package opensamguk.gameapi.read

import opensamguk.gameapi.dto.*
import opensamguk.infra.seed.StrategicTopologyJson
import opensamguk.logic.world.StrategicRouteProjection
import opensamguk.logic.world.StrategicNodeRef
import opensamguk.logic.world.TraversalMode
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/** Both map endpoints consume this one immutable, fully verified artifact snapshot. */
@Component
class StrategicTopologyReadSource(private val loader: () -> StrategicRouteProjection) {
    @Autowired
    constructor() : this(StrategicTopologyJson::loadDefault)

    val projection: StrategicRouteProjection by lazy(loader)

    fun binding(worldId: Int): StrategicTopologyBinding = StrategicTopologyBinding.from(worldId, projection)

    val presentation: StrategicMapTopologyDto by lazy {
        presentationFor(projection)
    }

    fun presentationFor(projection: StrategicRouteProjection): StrategicMapTopologyDto {
        val topology = projection.topology
        val display = requireNotNull(projection.presentation)
        return StrategicMapTopologyDto(
            topology.landProvinceIds.sorted(), display.geometries,
            topology.waterZones.sortedBy { it.id }.map { zone -> StrategicWaterZoneDto(
                zone.id, zone.kind.name, zone.geometryRef, display.zoneConnections.getValue(zone.id),
                zone.confidence.name, zone.seasonalAvailability.name,
            ) },
            topology.traversalEdges.sortedBy { it.id }.map { edge -> StrategicTraversalEdgeDto(
                edge.id, edge.from.canonicalKey, edge.to.canonicalKey, edge.mode.name,
                edge.movementCost, edge.capacity, edge.seasonalAvailability.name, edge.supplyAllowed,
                edge.initiallyOpen, edge.routeWeightPermille,
            ) },
            topology.riverBarriers.sortedBy { it.id }.map { StrategicRiverBarrierDto(it.id, it.firstLandProvinceId, it.secondLandProvinceId) },
            topology.traversalEdges.filter { it.mode in setOf(TraversalMode.EMBARK, TraversalMode.DISEMBARK) }
                .sortedBy { it.id }.map { edge ->
                    val land = listOf(edge.from, edge.to).filterIsInstance<StrategicNodeRef.LandProvince>().single()
                    val water = listOf(edge.from, edge.to).filterIsInstance<StrategicNodeRef.WaterZone>().single()
                    StrategicPortDto(edge.id, land.id, water.id)
                },
            projection.activationBlockerCodes.sorted(),
            display.roadGates,
        )
    }
}
