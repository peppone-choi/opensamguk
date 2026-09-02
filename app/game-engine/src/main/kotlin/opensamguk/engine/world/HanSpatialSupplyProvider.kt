package opensamguk.engine.world

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.logic.world.SpatialSupplyNetwork
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Path

data class SpatialSupplyCity(
    val cityId: Int,
    val provinceIndex: Int,
    val nationId: Int,
)

/**
 * Loads the canonical Han province topology and scenario occupancy used by monthly supply.
 *
 * The projection deliberately matches MapAdministrativeOwnership: scenario direct occupancy is the
 * base, and each mapped runtime city overrides only its jurisdiction's canonical seat province.
 */
@Component
class HanSpatialSupplyProvider(
    private val objectMapper: ObjectMapper,
    @Value("\${HAN_MAP_FILE:data/map/han-tiles.json}") private val mapPath: String,
    @Value("\${HAN_SCENARIO_PROVINCE_OWNERSHIP_FILE:data/map/han-scenario-province-ownership-v1.json}")
    private val ownershipPath: String,
) {
    @Volatile
    private var cached: CanonicalSpatialSupply? = null

    fun network(
        scenarioCode: Int,
        liveCities: List<SpatialSupplyCity>,
    ): SpatialSupplyNetwork {
        val canonical = canonical()
        val owners = canonical.scenarioOwners[scenarioCode]?.clone()
            ?: error("No canonical province ownership for scenario $scenarioCode")

        val cityByJurisdiction = linkedMapOf<String, SpatialSupplyCity>()
        for (city in liveCities.sortedBy { it.cityId }) {
            val jurisdictionId = canonical.provinceJurisdictions.getOrNull(city.provinceIndex)
                ?: error("Runtime city ${city.cityId} has unknown province index ${city.provinceIndex}")
            val previous = cityByJurisdiction.putIfAbsent(jurisdictionId, city)
            check(previous == null) {
                "Runtime cities ${previous?.cityId}, ${city.cityId} resolve to the same jurisdiction $jurisdictionId"
            }
        }
        cityByJurisdiction.forEach { (jurisdictionId, city) ->
            owners[canonical.jurisdictionSeatProvince.getValue(jurisdictionId)] = city.nationId
        }

        return SpatialSupplyNetwork(
            provinceOwners = owners,
            provinceAdjacency = canonical.adjacency.map(IntArray::clone),
            cityProvinceIndices = liveCities.associate { it.cityId to it.provinceIndex },
        )
    }

    private fun canonical(): CanonicalSpatialSupply = cached ?: synchronized(this) {
        cached ?: loadCanonical().also { cached = it }
    }

    private fun loadCanonical(): CanonicalSpatialSupply {
        val mapRoot = objectMapper.readTree(Path.of(mapPath).toFile())
        val provinces = mapRoot.requiredArray("provinceRecords")
        val provinceIds = provinces.map { it.requiredText("id") }
        requireUnique(provinceIds, "province record")
        val provinceIndexById = provinceIds.withIndex().associate { it.value to it.index }
        val provinceJurisdictions = provinces.map { it.requiredText("jurisdictionId") }
        val ownerGrid by lazy { decodeOwnerGrid(mapRoot, provinces.size) }
        val seatPlaceRecords = mapRoot.requiredArray("cities")
        requireUnique(seatPlaceRecords.map { it.requiredText("id") }, "seat place")
        val seatPlaces = seatPlaceRecords.associateBy { it.requiredText("id") }

        val jurisdictionRecords = mapRoot.requiredArray("jurisdictionRecords")
        val jurisdictionIds = jurisdictionRecords.map { it.requiredText("id") }
        requireUnique(jurisdictionIds, "jurisdiction")
        val jurisdictionByProvinceId = linkedMapOf<String, String>()
        jurisdictionRecords.forEach { jurisdiction ->
            val jurisdictionId = jurisdiction.requiredText("id")
            val memberIds = jurisdiction.requiredArray("provinceIds").map(JsonNode::asText)
            requireUnique(memberIds, "jurisdiction $jurisdictionId province")
            memberIds.forEach { provinceId ->
                val provinceIndex = provinceIndexById[provinceId]
                    ?: error("Jurisdiction $jurisdictionId references unknown province $provinceId")
                check(provinceJurisdictions[provinceIndex] == jurisdictionId) {
                    "Jurisdiction $jurisdictionId contains province $provinceId assigned to ${provinceJurisdictions[provinceIndex]}"
                }
                val previous = jurisdictionByProvinceId.putIfAbsent(provinceId, jurisdictionId)
                check(previous == null) {
                    "Province $provinceId belongs to both $previous and $jurisdictionId"
                }
            }
        }
        check(jurisdictionByProvinceId.keys == provinceIndexById.keys) {
            val missing = provinceIndexById.keys - jurisdictionByProvinceId.keys
            "Jurisdiction membership does not cover canonical provinces; missing: ${missing.sorted().joinToString()}"
        }

        val jurisdictionSeatProvince = jurisdictionRecords.associate { jurisdiction ->
            val jurisdictionId = jurisdiction.requiredText("id")
            val seatPlaceId = jurisdiction.requiredText("seatPlaceId")
            val provinceIdsInJurisdiction = jurisdiction.requiredArray("provinceIds").map(JsonNode::asText)
            val seatProvinceId = if (seatPlaceId in provinceIdsInJurisdiction) {
                seatPlaceId
            } else {
                val seat = seatPlaces[seatPlaceId]
                    ?: error("Jurisdiction $jurisdictionId references unknown seat place $seatPlaceId")
                val cols = mapRoot.path("_meta").requiredInt("cols")
                val cellIndex = seat.requiredInt("row") * cols + seat.requiredInt("col")
                val provinceIndex = ownerGrid.getOrNull(cellIndex)
                    ?: error("Jurisdiction $jurisdictionId seat $seatPlaceId is outside the map grid")
                provinceIds.getOrNull(provinceIndex)
                    ?: error("Jurisdiction $jurisdictionId seat $seatPlaceId is outside playable provinces")
            }
            check(seatProvinceId in provinceIdsInJurisdiction) {
                "Jurisdiction $jurisdictionId seat $seatPlaceId resolves outside its provinces"
            }
            jurisdictionId to provinceIndexById.getValue(seatProvinceId)
        }
        provinceJurisdictions.forEachIndexed { provinceIndex, jurisdictionId ->
            check(jurisdictionId in jurisdictionSeatProvince) {
                "Province ${provinceIds[provinceIndex]} references unknown jurisdiction $jurisdictionId"
            }
        }

        val adjacency = MutableList(provinces.size) { mutableListOf<Int>() }
        val edges = linkedSetOf<Pair<Int, Int>>()
        mapRoot.path("adjacency").requiredArray("county").forEach { edge ->
            val a = edge.requiredInt("a")
            val b = edge.requiredInt("b")
            require(a in provinces.indices && b in provinces.indices && a != b) {
                "Invalid spatial adjacency $a-$b"
            }
            val canonicalEdge = minOf(a, b) to maxOf(a, b)
            require(edges.add(canonicalEdge)) { "Duplicate spatial adjacency ${canonicalEdge.first}-${canonicalEdge.second}" }
            adjacency[a] += b
            adjacency[b] += a
        }
        val validatedAdjacency = adjacency.map { it.toIntArray() }
        validateSpatialAdjacency(validatedAdjacency, provinces.size)

        val scenarios = objectMapper.readTree(Path.of(ownershipPath).toFile()).requiredArray("scenarios")
        val scenarioCodes = scenarios.map { it.requiredInt("scenarioCode") }
        requireUnique(scenarioCodes, "scenario code")
        val scenarioOwners = scenarios.associate { scenario ->
                val scenarioCode = scenario.requiredInt("scenarioCode")
                val assignments = scenario.requiredArray("assignments")
                requireUnique(assignments.map { it.requiredText("provinceId") }, "scenario $scenarioCode province")
                val ownersById = assignments.associate { assignment ->
                    val provinceId = assignment.requiredText("provinceId")
                    val ownerNode = assignment.get("ownerNationId")
                    check(ownerNode == null || ownerNode.isNull || ownerNode.isIntegralNumber) {
                        "Scenario $scenarioCode province $provinceId has invalid ownerNationId"
                    }
                    provinceId to (ownerNode?.takeUnless(JsonNode::isNull)?.asInt() ?: 0)
                }
                check(ownersById.keys == provinceIndexById.keys) {
                    "Scenario $scenarioCode ownership coverage does not match canonical provinces"
                }
                scenarioCode to IntArray(provinces.size) { ownersById.getValue(provinceIds[it]) }
            }

        return CanonicalSpatialSupply(
            provinceJurisdictions = provinceJurisdictions,
            jurisdictionSeatProvince = jurisdictionSeatProvince,
            adjacency = validatedAdjacency,
            scenarioOwners = scenarioOwners,
        )
    }

    private fun decodeOwnerGrid(mapRoot: JsonNode, provinceCount: Int): IntArray {
        val cols = mapRoot.path("_meta").requiredInt("cols")
        val rows = mapRoot.path("_meta").requiredInt("rows")
        val grid = IntArray(cols * rows)
        var cursor = 0
        mapRoot.requiredArray("owner").forEach { run ->
            check(run.isArray && run.size() == 2) { "Invalid owner run-length record" }
            val provinceIndex = run[0].asInt()
            val count = run[1].asInt()
            check(provinceIndex == -1 || provinceIndex in 0 until provinceCount) {
                "Owner grid references unknown province index $provinceIndex"
            }
            check(count > 0 && cursor + count <= grid.size) { "Invalid owner grid run length $count" }
            grid.fill(provinceIndex, cursor, cursor + count)
            cursor += count
        }
        check(cursor == grid.size) { "Owner grid covers $cursor cells, expected ${grid.size}" }
        return grid
    }

    private fun JsonNode.requiredArray(field: String): List<JsonNode> {
        val value = get(field)
        check(value != null && value.isArray) { "Missing array field $field" }
        return value.toList()
    }

    private fun JsonNode.requiredText(field: String): String {
        val value = get(field)
        check(value != null && value.isTextual && value.asText().isNotBlank()) { "Missing text field $field" }
        return value.asText()
    }

    private fun JsonNode.requiredInt(field: String): Int {
        val value = get(field)
        check(value != null && value.isIntegralNumber) { "Missing integer field $field" }
        return value.asInt()
    }

    private fun <T : Comparable<T>> requireUnique(values: List<T>, label: String) {
        val duplicates = values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        check(duplicates.isEmpty()) { "Duplicate $label ids: ${duplicates.sorted().joinToString()}" }
    }

    private data class CanonicalSpatialSupply(
        val provinceJurisdictions: List<String>,
        val jurisdictionSeatProvince: Map<String, Int>,
        val adjacency: List<IntArray>,
        val scenarioOwners: Map<Int, IntArray>,
    )
}

internal fun validateSpatialAdjacency(adjacency: List<IntArray>, provinceCount: Int) {
    require(adjacency.size == provinceCount) {
        "Spatial adjacency size ${adjacency.size} does not match province count $provinceCount"
    }
    adjacency.forEachIndexed { provinceIndex, neighbors ->
        require(neighbors.toSet().size == neighbors.size) {
            "Spatial province $provinceIndex has duplicate adjacency"
        }
        neighbors.forEach { neighbor ->
            require(neighbor in 0 until provinceCount && neighbor != provinceIndex) {
                "Spatial province $provinceIndex has invalid neighbor $neighbor"
            }
        }
    }
    adjacency.forEachIndexed { provinceIndex, neighbors ->
        neighbors.forEach { neighbor ->
            require(provinceIndex in adjacency[neighbor]) {
                "Spatial adjacency is asymmetric: $provinceIndex -> $neighbor"
            }
        }
    }
}
