package opensamguk.logic.world

/** A reviewed response to a supply disagreement or a known geometry disconnection. */
enum class SupplyDisconnectionDecision {
    PROTECT_GEOMETRY_DEFECT,
    PROTECT_PARENT_MISASSIGNMENT,
    UPHOLD_WATER_ROUTE_ONLY,
    UPHOLD_HISTORICAL_EXCLAVE,
}

enum class SupplyReachabilityExpectation {
    CITY_ONLY,
    BOTH_UNSUPPLIED,
}

data class SupplyFallbackPolicy(
    val decision: SupplyDisconnectionDecision,
    val sourceLedgerRow: String,
    val expectedCurrentReachability: SupplyReachabilityExpectation = SupplyReachabilityExpectation.CITY_ONLY,
) {
    init {
        require(sourceLedgerRow.isNotBlank()) { "Supply fallback policy requires sourceLedgerRow" }
    }

    val upholdsSpatialCut: Boolean
        get() = decision == SupplyDisconnectionDecision.UPHOLD_WATER_ROUTE_ONLY ||
            decision == SupplyDisconnectionDecision.UPHOLD_HISTORICAL_EXCLAVE

    val protectsDestructiveDisconnection: Boolean
        get() = decision == SupplyDisconnectionDecision.PROTECT_GEOMETRY_DEFECT ||
            decision == SupplyDisconnectionDecision.PROTECT_PARENT_MISASSIGNMENT
}

enum class SupplyReachabilityVerdict {
    BOTH_SUPPLIED,
    CITY_ONLY_PROTECTED,
    SPATIAL_ONLY_SUPPLIED,
    BOTH_UNSUPPLIED_PROTECTED,
    BOTH_UNSUPPLIED,
    SPATIAL_CUT_UPHELD,
    MILITARY_CUT,
}

/** UNKNOWN records absence of proof, never a guessed enemy or closed road. */
enum class SupplyCutReason { NO_SOURCE, OWNERSHIP_CUT, PASSAGE_CUT, MILITARY_CUT, SIEGE, UNKNOWN }

/** Last evaluated snapshot, carried with the city flag through the same fenced flush. */
fun supplyReasonSnapshot(reason: SupplyCutReason?, cityId: Int, nationId: Int, worldId: Int,
    year: Int, month: Int, phase: Int, mapName: String, topologyHash: String?): Map<String, Any?>? =
    reason?.let { linkedMapOf("version" to 1, "code" to it.name, "cityId" to cityId,
        "nationId" to nationId, "worldId" to worldId, "year" to year, "month" to month,
        "phase" to phase, "mapName" to mapName, "topologyHash" to topologyHash) }

data class SupplyReachabilityRow(
    val cityId: Int,
    val cityGraphSupplied: Boolean,
    val spatialGraphSupplied: Boolean,
    val verdict: SupplyReachabilityVerdict,
    val policy: SupplyFallbackPolicy? = null,
    val cutReason: SupplyCutReason? = null,
)

data class SupplyReachabilityEvaluation(
    val suppliedCityIds: Set<Int>,
    val rows: List<SupplyReachabilityRow>,
)

/** No destructive settlement may guess how a military blockade intersects missing geometry. */
class MilitarySupplyUnavailableException(message: String) : IllegalStateException(message)

/**
 * Evaluate the historical CityConst graph and projected spatial graph as independent evidence.
 * On maps without road construction state, a destructive city-only spatial cut requires an exact
 * reviewed UPHOLD decision; unknown disagreements fail safe. On road-constrained maps, a city-only
 * cut is upheld even without that decision unless a reviewed PROTECT decision applies. PROTECT can
 * also preserve a city whose geometry disconnects both graphs, but only while its expectation matches.
 * The canonical audit is responsible for failing closed on unreviewed map defects.
 */
fun evaluateSupplyReachability(
    cities: List<SupplyCity>,
    capitals: List<SupplyCapital>,
    cityConst: CityConstVariant,
    spatialNetwork: SpatialSupplyNetwork,
): SupplyReachabilityEvaluation {
    val mappedIds = spatialNetwork.cityProvinceIndices.keys
    val mappedCitySupplied = computeSuppliedCities(
        cities = cities.filter { it.id in mappedIds },
        capitals = capitals.filter { it.capitalCityId in mappedIds },
        cityConst = cityConst,
    )
    val legacyCitySupplied = computeSuppliedCities(cities, capitals, cityConst)
        .filterTo(linkedSetOf()) { it !in mappedIds }
    val citySupplied = mappedCitySupplied + legacyCitySupplied
    val spatialSupplied = computeSpatiallySuppliedCities(cities, capitals, spatialNetwork)
    val strategic = spatialNetwork.strategicSupply
    // A release with unbuilt land boundaries deliberately lets roads cut supply.
    // Earlier strategic maps have no construction state and retain the geometry fallback.
    val roadConstrained = strategic?.topology?.traversalEdges?.any {
        it.mode == TraversalMode.LAND && !it.initiallyOpen
    } == true
    val hasMilitaryBlocks = strategic?.militaryBlocksByNation?.values?.any { it.isNotEmpty() } == true
    val beforeMilitary = if (hasMilitaryBlocks) computeSpatiallySuppliedCities(cities, capitals,
        spatialNetwork.copy(strategicSupply = strategic!!.withMilitaryBlocks(emptyMap()))) else spatialSupplied
    val nations = cities.associate { it.id to it.nationId }
    val reasons = strategic?.disconnectionReasons(cities, capitals, spatialNetwork.provinceOwners,
        spatialNetwork.cityProvinceIndices, spatialSupplied).orEmpty()


    val rows = cities.asSequence()
        .filter { it.id in mappedIds }
        .map { it.id }
        .distinct()
        .sorted()
        .map { cityId ->
            val byCity = cityId in citySupplied
            val bySpatial = cityId in spatialSupplied
            val policy = spatialNetwork.fallbackPolicies[cityId]
            val actualExpectation = when {
                byCity && !bySpatial -> SupplyReachabilityExpectation.CITY_ONLY
                !byCity && !bySpatial -> SupplyReachabilityExpectation.BOTH_UNSUPPLIED
                else -> null
            }
            val applicablePolicy = policy?.takeIf { it.expectedCurrentReachability == actualExpectation }
            val blocked = strategic?.militaryBlocksByNation?.get(nations[cityId]).orEmpty()
            val provinceIndex = spatialNetwork.cityProvinceIndices.getValue(cityId)
            val directlyBlocked = strategic?.provinceIds?.get(provinceIndex) in blocked
            val militaryCut = directlyBlocked || (cityId in beforeMilitary && !bySpatial)
            val geometryProtected = applicablePolicy?.protectsDestructiveDisconnection == true ||
                (byCity && !roadConstrained && applicablePolicy?.upholdsSpatialCut != true)
            if (!militaryCut && !bySpatial && geometryProtected && blocked.any { province ->
                    val index = strategic!!.provinceIds.indexOf(province)
                    spatialNetwork.provinceOwners[index] == nations[cityId]
                }) throw MilitarySupplyUnavailableException("Military supply impact is unavailable for city $cityId")
            val verdict = when {
                militaryCut -> SupplyReachabilityVerdict.MILITARY_CUT
                byCity && bySpatial -> SupplyReachabilityVerdict.BOTH_SUPPLIED
                byCity && (applicablePolicy?.upholdsSpatialCut == true || roadConstrained && !geometryProtected) ->
                    SupplyReachabilityVerdict.SPATIAL_CUT_UPHELD
                byCity -> SupplyReachabilityVerdict.CITY_ONLY_PROTECTED
                bySpatial -> SupplyReachabilityVerdict.SPATIAL_ONLY_SUPPLIED
                applicablePolicy?.protectsDestructiveDisconnection == true ->
                    SupplyReachabilityVerdict.BOTH_UNSUPPLIED_PROTECTED
                else -> SupplyReachabilityVerdict.BOTH_UNSUPPLIED
            }
            val cut = verdict in setOf(SupplyReachabilityVerdict.BOTH_UNSUPPLIED,
                SupplyReachabilityVerdict.SPATIAL_CUT_UPHELD, SupplyReachabilityVerdict.MILITARY_CUT)
            val reason = if (!cut) null else if (militaryCut) SupplyCutReason.MILITARY_CUT
                else reasons[cityId] ?: if (capitals.none { cap -> cap.nationId == nations[cityId] &&
                    cities.any { it.id == cap.capitalCityId && it.nationId == cap.nationId } }) SupplyCutReason.NO_SOURCE
                else SupplyCutReason.UNKNOWN
            SupplyReachabilityRow(cityId, byCity, bySpatial, verdict, applicablePolicy, reason)
        }
        .toList()

    val destructive = setOf(
        SupplyReachabilityVerdict.BOTH_UNSUPPLIED,
        SupplyReachabilityVerdict.SPATIAL_CUT_UPHELD,
        SupplyReachabilityVerdict.MILITARY_CUT,
    )
    return SupplyReachabilityEvaluation(
        suppliedCityIds = rows.asSequence()
            .filter { it.verdict !in destructive }
            .mapTo(linkedSetOf()) { it.cityId }
            .also { it += legacyCitySupplied },
        rows = rows,
    )
}

private fun computeSpatiallySuppliedCities(
    cities: List<SupplyCity>,
    capitals: List<SupplyCapital>,
    spatialNetwork: SpatialSupplyNetwork,
): Set<Int> {
    spatialNetwork.strategicSupply?.let {
        return it.suppliedCities(cities, capitals, spatialNetwork.provinceOwners, spatialNetwork.cityProvinceIndices)
    }
    val ownedNation = cities.associate { it.id to it.nationId }
    val reached = BooleanArray(spatialNetwork.provinceOwners.size)
    val queue = ArrayDeque<Int>()

    for (capital in capitals) {
        if (ownedNation[capital.capitalCityId] != capital.nationId) continue
        val provinceIndex = spatialNetwork.cityProvinceIndices[capital.capitalCityId] ?: continue
        if (spatialNetwork.provinceOwners[provinceIndex] != capital.nationId) continue
        if (reached[provinceIndex]) continue
        reached[provinceIndex] = true
        queue.addLast(provinceIndex)
    }

    while (queue.isNotEmpty()) {
        val provinceIndex = queue.removeFirst()
        val nationId = spatialNetwork.provinceOwners[provinceIndex]
        for (neighbor in spatialNetwork.provinceAdjacency[provinceIndex]) {
            if (reached[neighbor]) continue
            if (spatialNetwork.provinceOwners[neighbor] != nationId) continue
            reached[neighbor] = true
            queue.addLast(neighbor)
        }
    }

    return cities.asSequence()
        .filter { city ->
            val provinceIndex = spatialNetwork.cityProvinceIndices[city.id] ?: return@filter false
            spatialNetwork.provinceOwners[provinceIndex] == city.nationId && reached[provinceIndex]
        }
        .mapTo(linkedSetOf()) { it.id }
}
