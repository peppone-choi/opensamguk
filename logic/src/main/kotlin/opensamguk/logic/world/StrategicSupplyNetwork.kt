package opensamguk.logic.world

import java.util.Collections

/** Water is a traversal/control domain, not an extra politically owned province. */
class StrategicSupplyNetwork(
    val topology: StrategicTopologySnapshot,
    provinceIds: List<String>,
    val waterControl: WaterControlSnapshot?,
    edgeStatesByNation: Map<Int, StrategicEdgeStateSnapshot> = emptyMap(),
) {
    val provinceIds: List<String> = Collections.unmodifiableList(provinceIds.toList())
    private val provinceIndexById = this.provinceIds.withIndex().associate { it.value to it.index }
    private val edgeStatesByNation = Collections.unmodifiableMap(LinkedHashMap(edgeStatesByNation))
    private val waterById = topology.waterZones.associateBy { it.id }

    init {
        require(this.provinceIds.size == provinceIndexById.size && provinceIndexById.keys == topology.landProvinceIds) {
            "Strategic supply requires the exact ordered spatial province identity domain"
        }
        require(waterControl == null || (waterControl.topologyRevision == topology.topologyRevision &&
            waterControl.topologyHash == topology.contentHash && waterControl.knownWaterZoneIds == waterById.keys)) {
            "Supply water control topology or zone inventory is stale"
        }
        val edgeIds = topology.traversalEdges.mapTo(hashSetOf()) { it.id }
        this.edgeStatesByNation.forEach { (nationId, state) ->
            require(nationId > 0 && state.topologyRevision == topology.topologyRevision &&
                state.topologyHash == topology.contentHash && state.edgeStates.keys.all { it in edgeIds }) {
                "Supply edge state has invalid nation, pins or edge identity"
            }
        }
    }

    fun suppliedCities(
        cities: List<SupplyCity>,
        capitals: List<SupplyCapital>,
        provinceOwners: IntArray,
        cityProvinceIndices: Map<Int, Int>,
    ): Set<Int> {
        require(provinceOwners.size == provinceIds.size)
        require(cityProvinceIndices.values.all { it in provinceIds.indices })
        val cityNations = cities.associate { it.id to it.nationId }
        val supplied = linkedSetOf<Int>()
        val seedsByNation = capitals.filter { it.nationId > 0 && cityNations[it.capitalCityId] == it.nationId }
            .groupBy { it.nationId }.toSortedMap()
        for ((nationId, seeds) in seedsByNation) {
            val live = edgeStatesByNation[nationId] ?: StrategicEdgeStateSnapshot(
                topology.topologyRevision, topology.contentHash, emptyMap())
            val sources = seeds.mapNotNull { cityProvinceIndices[it.capitalCityId] }
                .filter { provinceOwners[it] == nationId }
                .mapTo(linkedSetOf<StrategicNodeRef>()) { StrategicNodeRef.LandProvince(provinceIds[it]) }
            fun nodeAllowed(node: StrategicNodeRef): Boolean = when (node) {
                is StrategicNodeRef.LandProvince -> provinceIndexById[node.id]?.let { provinceOwners[it] == nationId } == true
                is StrategicNodeRef.WaterZone -> waterControl?.stateFor(node.id)?.let {
                    it.controllingNationId == nationId.toLong() && it.blockadeState == WaterBlockadeState.OPEN &&
                        it.contestingNationIds.isEmpty()
                } == true
            }
            val reached = StrategicPathResolver.reachableNodes(topology, sources, live, 1, ::nodeAllowed) { edge ->
                if (!edge.supplyAllowed) false
                else if (edge.mode == TraversalMode.LAND) true
                else {
                    // Static edge capacity describes infrastructure, not available boats/permission.
                    val assessment = live.edgeStates[edge.id]
                    assessment != null && assessment.active && assessment.availableCapacity != null &&
                        listOf(edge.from, edge.to).all { node ->
                            val zone = (node as? StrategicNodeRef.WaterZone)?.let { waterById.getValue(it.id) }
                            zone == null || zone.seasonalAvailability == SeasonalAvailability.ALWAYS ||
                                (zone.seasonalAvailability == SeasonalAvailability.SEASONAL && assessment.seasonOpen)
                        }
                }
            }
            cities.filter { it.nationId == nationId }.sortedBy { it.id }.forEach { city ->
                val index = cityProvinceIndices[city.id] ?: return@forEach
                if (provinceOwners[index] == nationId && StrategicNodeRef.LandProvince(provinceIds[index]) in reached) {
                    supplied += city.id
                }
            }
        }
        return Collections.unmodifiableSet(supplied)
    }
}
