package opensamguk.logic.world

import java.util.Collections

data class HanStrategicRouteBinding(
    val runtimeCityId: Int,
    val routeNodeKey: String,
    val physicalPlaceRef: String,
    val landProvinceId: String?,
) {
    init {
        require(runtimeCityId > 0 && routeNodeKey.isNotBlank() && physicalPlaceRef.isNotBlank()) {
            "A Han route binding requires runtime, route, and physical identities"
        }
        require(landProvinceId == null || landProvinceId.isNotBlank()) { "Blank land province identity" }
    }
}

/** Immutable runtime identity adapter. An unmapped physical place never acquires an ordinal province. */
class HanStrategicRouteProjection(
    val topology: StrategicTopologySnapshot,
    bindings: List<HanStrategicRouteBinding>,
    activationBlockerCodes: Set<String> = emptySet(),
) {
    val activationBlockerCodes: Set<String> = Collections.unmodifiableSet(activationBlockerCodes.toSortedSet())
    val bindingsByCityId: Map<Int, HanStrategicRouteBinding>
    val bindingsByRouteKey: Map<String, HanStrategicRouteBinding>
    val bindingsByPhysicalPlaceRef: Map<String, HanStrategicRouteBinding>

    init {
        require(bindings.map { it.runtimeCityId }.toSet().size == bindings.size) { "Duplicate runtime city identity" }
        require(bindings.map { it.routeNodeKey }.toSet().size == bindings.size) { "Duplicate route node identity" }
        require(bindings.map { it.physicalPlaceRef }.toSet().size == bindings.size) { "Duplicate physical place identity" }
        require(bindings.all { it.landProvinceId == null || it.landProvinceId in topology.landProvinceIds }) {
            "Route binding references an unknown stable land province"
        }
        val ordered = bindings.sortedBy { it.runtimeCityId }
        bindingsByCityId = Collections.unmodifiableMap(ordered.associateBy { it.runtimeCityId })
        bindingsByRouteKey = Collections.unmodifiableMap(ordered.associateBy { it.routeNodeKey })
        bindingsByPhysicalPlaceRef = Collections.unmodifiableMap(ordered.associateBy { it.physicalPlaceRef })
    }

    fun resolve(
        fromRuntimeCityId: Int,
        toRuntimeCityId: Int,
        requiredCapacity: Int,
        state: StrategicEdgeStateSnapshot,
    ): StrategicPathResult {
        val from = bindingsByCityId[fromRuntimeCityId]?.landProvinceId
            ?: return StrategicPathResult.Denied(PathDenialCode.UNKNOWN_NODE)
        val to = bindingsByCityId[toRuntimeCityId]?.landProvinceId
            ?: return StrategicPathResult.Denied(PathDenialCode.UNKNOWN_NODE)
        return StrategicPathResolver.resolve(
            topology,
            StrategicPathRequest(StrategicNodeRef.LandProvince(from), StrategicNodeRef.LandProvince(to), requiredCapacity),
            state,
        )
    }
}

/** Derive only real dry shared borders, using raster indices locally and stable IDs in every edge. */
fun projectHanDryLandEdges(
    provinceIds: List<String>,
    ownerGrid: IntArray,
    terrainRows: List<String>,
    dryTerrainCodes: Set<Char>,
    riverBarriers: List<RiverBarrier>,
    baseArtifactHash: String,
): List<TraversalEdge> {
    require(provinceIds.none(String::isBlank) && provinceIds.toSet().size == provinceIds.size) { "Invalid stable province IDs" }
    require(terrainRows.isNotEmpty() && terrainRows[0].isNotEmpty()) { "Empty terrain raster" }
    val cols = terrainRows[0].length
    require(terrainRows.all { it.length == cols } && ownerGrid.size.toLong() == terrainRows.size.toLong() * cols) {
        "Owner and terrain raster dimensions differ"
    }
    require(ownerGrid.all { it == -1 || it in provinceIds.indices }) { "Owner raster contains an invalid province index" }
    require(dryTerrainCodes.isNotEmpty() && baseArtifactHash.isNotBlank()) { "Dry land projection requires terrain policy and source hash" }
    val blocked = riverBarriers.mapTo(hashSetOf()) { it.canonicalBoundaryKey }
    val boundaries = sortedMapOf<String, Pair<String, String>>()
    fun visit(firstIndex: Int, secondIndex: Int) {
        val firstOwner = ownerGrid[firstIndex]
        val secondOwner = ownerGrid[secondIndex]
        if (firstOwner < 0 || secondOwner < 0 || firstOwner == secondOwner) return
        if (terrainRows[firstIndex / cols][firstIndex % cols] !in dryTerrainCodes ||
            terrainRows[secondIndex / cols][secondIndex % cols] !in dryTerrainCodes
        ) return
        val ids = listOf(provinceIds[firstOwner], provinceIds[secondOwner]).sorted()
        val key = strategicLandBoundaryKey(ids[0], ids[1])
        if (key !in blocked) boundaries[key] = ids[0] to ids[1]
    }
    for (row in terrainRows.indices) {
        for (col in 0 until cols) {
            val index = row * cols + col
            if (col + 1 < cols) visit(index, index + 1)
            if (row + 1 < terrainRows.size) visit(index, index + cols)
        }
    }
    return Collections.unmodifiableList(boundaries.map { (key, endpoints) ->
        TraversalEdge(
            id = "land-boundary:$key",
            from = StrategicNodeRef.LandProvince(endpoints.first),
            to = StrategicNodeRef.LandProvince(endpoints.second),
            mode = TraversalMode.LAND,
            directed = false,
            movementCost = 1,
            capacity = Int.MAX_VALUE,
            riskBand = RiskBand.LOW,
            seasonalAvailability = SeasonalAvailability.ALWAYS,
            supplyAllowed = true,
            sourceRefs = listOf("han-tiles:$baseArtifactHash#dry-boundary:$key"),
            confidence = EvidenceConfidence.EXACT,
        )
    })
}
