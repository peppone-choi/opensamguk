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
}

data class SupplyReachabilityRow(
    val cityId: Int,
    val cityGraphSupplied: Boolean,
    val spatialGraphSupplied: Boolean,
    val verdict: SupplyReachabilityVerdict,
    val policy: SupplyFallbackPolicy? = null,
)

data class SupplyReachabilityEvaluation(
    val suppliedCityIds: Set<Int>,
    val rows: List<SupplyReachabilityRow>,
)

/**
 * Evaluate the historical CityConst graph and projected spatial graph as independent evidence.
 * A destructive city-only spatial cut requires an exact reviewed UPHOLD decision. Unknown runtime
 * disagreements therefore fail safe. A reviewed PROTECT decision can also preserve a city whose
 * geometry disconnects both graphs, but only while the policy's reachability expectation matches.
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
            val verdict = when {
                byCity && bySpatial -> SupplyReachabilityVerdict.BOTH_SUPPLIED
                byCity && applicablePolicy?.upholdsSpatialCut == true -> SupplyReachabilityVerdict.SPATIAL_CUT_UPHELD
                byCity -> SupplyReachabilityVerdict.CITY_ONLY_PROTECTED
                bySpatial -> SupplyReachabilityVerdict.SPATIAL_ONLY_SUPPLIED
                applicablePolicy?.protectsDestructiveDisconnection == true ->
                    SupplyReachabilityVerdict.BOTH_UNSUPPLIED_PROTECTED
                else -> SupplyReachabilityVerdict.BOTH_UNSUPPLIED
            }
            SupplyReachabilityRow(cityId, byCity, bySpatial, verdict, applicablePolicy)
        }
        .toList()

    val destructive = setOf(
        SupplyReachabilityVerdict.BOTH_UNSUPPLIED,
        SupplyReachabilityVerdict.SPATIAL_CUT_UPHELD,
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
