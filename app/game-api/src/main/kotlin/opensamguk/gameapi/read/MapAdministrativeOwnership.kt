package opensamguk.gameapi.read

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

data class LiveCityOwnership(
    val cityId: Int,
    val provinceIndex: Int,
    val nationId: Int,
)

data class ProvinceOccupancyProjection(
    val provinceRecordId: String,
    val provinceIndex: Int,
    val nationId: Int,
)

data class JurisdictionOwnershipProjection(
    val jurisdictionId: String,
    val nationId: Int,
)

data class CommanderyControlProjection(
    val commanderyId: String,
    val nationId: Int,
)

data class AdministrativeOwnershipSnapshot(
    val provinceOccupancy: List<ProvinceOccupancyProjection>,
    val jurisdictionOwnership: List<JurisdictionOwnershipProjection>,
    val commanderyControl: List<CommanderyControlProjection>,
)

/**
 * Projects the canonical scenario province assignments onto the administrative hierarchy.
 *
 * Spatial occupancy, jurisdiction ownership and commandery control deliberately remain three
 * separate values. In particular, mixed direct province assignments are never collapsed into a
 * representative province colour. A live city only overrides the political owner of its unique
 * jurisdiction and the occupancy of that jurisdiction's canonical seat province.
 */
@Component
class MapAdministrativeOwnership(
    private val objectMapper: ObjectMapper,
    @Value("\${HAN_MAP_FILE:data/map/han-tiles.json}") private val mapPath: String,
    @Value("\${HAN_SCENARIO_PROVINCE_OWNERSHIP_FILE:data/map/han-scenario-province-ownership-v1.json}")
    private val ownershipPath: String,
    @Value("\${HAN_SCENARIO_JURISDICTION_CONFLICT_ALLOWLIST_FILE:data/map/han-scenario-jurisdiction-conflict-allowlist-v1.json}")
    private val conflictAllowlistPath: String,
) {
    @Volatile
    private var cached: CachedCanonicalData? = null

    fun project(
        scenarioCode: String,
        liveCities: List<LiveCityOwnership>,
    ): AdministrativeOwnershipSnapshot {
        val canonical = canonicalData()
        val numericScenarioCode = Regex("^(?:scenario_)?([1-9][0-9]*)$").matchEntire(scenarioCode)
            ?.groupValues?.get(1)?.toIntOrNull()
            ?: error("Unsupported Han scenario code: $scenarioCode")
        val directOwners = canonical.scenarioOwners[numericScenarioCode]
            ?: error("No canonical province ownership for scenario $numericScenarioCode")

        val liveCityByJurisdiction = linkedMapOf<String, LiveCityOwnership>()
        liveCities.sortedBy { it.cityId }.forEach { city ->
            val province = canonical.provinces.getOrNull(city.provinceIndex)
                ?: error("Runtime city ${city.cityId} has unknown province index ${city.provinceIndex}")
            val previous = liveCityByJurisdiction.putIfAbsent(province.jurisdictionId, city)
            if (previous != null) {
                error(
                    "Runtime cities ${previous.cityId}, ${city.cityId} resolve to the same " +
                        "jurisdiction ${province.jurisdictionId}",
                )
            }
        }

        val jurisdictionOwners = canonical.jurisdictions.associate { jurisdiction ->
            val liveOwner = liveCityByJurisdiction[jurisdiction.id]?.nationId
            jurisdiction.id to (liveOwner ?: directOwners.getValue(jurisdiction.seatProvinceId))
        }

        val seatOwnerOverrides = liveCityByJurisdiction.mapKeys { (jurisdictionId, _) ->
            canonical.jurisdictionById.getValue(jurisdictionId).seatProvinceId
        }.mapValues { (_, city) -> city.nationId }

        val provinceOccupancy = canonical.provinces.mapIndexed { index, province ->
            ProvinceOccupancyProjection(
                provinceRecordId = province.id,
                provinceIndex = index,
                nationId = seatOwnerOverrides[province.id] ?: directOwners.getValue(province.id),
            )
        }
        val jurisdictionOwnership = canonical.jurisdictions.map { jurisdiction ->
            JurisdictionOwnershipProjection(
                jurisdictionId = jurisdiction.id,
                nationId = jurisdictionOwners.getValue(jurisdiction.id),
            )
        }
        val commanderyControl = canonical.commanderies.map { commandery ->
            val ownerCounts = commandery.jurisdictionIds
                .map(jurisdictionOwners::getValue)
                .groupingBy { it }
                .eachCount()
            val seatOwner = jurisdictionOwners.getValue(commandery.seatJurisdictionId)
            val controller = resolveCommanderyController(ownerCounts, seatOwner)
            CommanderyControlProjection(commandery.id, controller)
        }

        return AdministrativeOwnershipSnapshot(
            provinceOccupancy = provinceOccupancy,
            jurisdictionOwnership = jurisdictionOwnership,
            commanderyControl = commanderyControl,
        )
    }

    private fun canonicalData(): CanonicalData {
        val mapFile = Path.of(mapPath)
        val ownershipFile = Path.of(ownershipPath)
        val allowlistFile = Path.of(conflictAllowlistPath)
        val signature = CanonicalSignature(
            mapSize = Files.size(mapFile),
            mapModifiedMillis = Files.getLastModifiedTime(mapFile).toMillis(),
            ownershipSize = Files.size(ownershipFile),
            ownershipModifiedMillis = Files.getLastModifiedTime(ownershipFile).toMillis(),
            allowlistSize = Files.size(allowlistFile),
            allowlistModifiedMillis = Files.getLastModifiedTime(allowlistFile).toMillis(),
        )
        cached?.takeIf { it.signature == signature }?.let { return it.data }
        return synchronized(this) {
            cached?.takeIf { it.signature == signature }?.data
                ?: loadCanonicalData(mapFile, ownershipFile, allowlistFile)
                .also { cached = CachedCanonicalData(signature, it) }
        }
    }

    private fun loadCanonicalData(mapFile: Path, ownershipFile: Path, allowlistFile: Path): CanonicalData {
        val mapRoot = objectMapper.readTree(mapFile.toFile())
        val provinces = mapRoot.requiredArray("provinceRecords").map { node ->
            CanonicalProvince(node.requiredText("id"), node.requiredText("jurisdictionId"))
        }
        requireUnique(provinces.map { it.id }, "province record")

        val ownerGrid by lazy { decodeOwnerGrid(mapRoot, provinces.size) }
        val seatPlaces = mapRoot.path("cities")
            .takeIf(JsonNode::isArray)
            ?.associateBy { it.requiredText("id") }
            .orEmpty()
        val jurisdictions = mapRoot.requiredArray("jurisdictionRecords").map { node ->
            val id = node.requiredText("id")
            val seatPlaceId = node.requiredText("seatPlaceId")
            val provinceIds = node.requiredArray("provinceIds").map(JsonNode::asText)
            val seatProvinceId = if (seatPlaceId in provinceIds) {
                seatPlaceId
            } else {
                val seat = seatPlaces[seatPlaceId]
                    ?: error("Jurisdiction $id references unknown seat place $seatPlaceId")
                val cols = mapRoot.path("_meta").requiredInt("cols")
                val col = seat.requiredInt("col")
                val row = seat.requiredInt("row")
                val provinceIndex = ownerGrid.getOrNull(row * cols + col)
                    ?: error("Jurisdiction $id seat $seatPlaceId is outside the map grid")
                provinces.getOrNull(provinceIndex)?.id
                    ?: error("Jurisdiction $id seat $seatPlaceId is outside playable provinces")
            }
            CanonicalJurisdiction(
                id = id,
                commanderyId = node.requiredText("commanderyId"),
                seatPlaceId = seatPlaceId,
                seatProvinceId = seatProvinceId,
                provinceIds = provinceIds,
            )
        }
        requireUnique(jurisdictions.map { it.id }, "jurisdiction")
        val jurisdictionById = jurisdictions.associateBy { it.id }
        val provinceById = provinces.associateBy { it.id }
        jurisdictions.forEach { jurisdiction ->
            check(jurisdiction.seatProvinceId in jurisdiction.provinceIds) {
                "Jurisdiction ${jurisdiction.id} seat ${jurisdiction.seatPlaceId} resolves outside its provinces"
            }
            jurisdiction.provinceIds.forEach { provinceId ->
                val province = provinceById[provinceId]
                    ?: error("Jurisdiction ${jurisdiction.id} references unknown province $provinceId")
                check(province.jurisdictionId == jurisdiction.id) {
                    "Province $provinceId belongs to ${province.jurisdictionId}, not ${jurisdiction.id}"
                }
            }
        }
        provinces.forEach { province ->
            val jurisdiction = jurisdictionById[province.jurisdictionId]
                ?: error("Province ${province.id} references unknown jurisdiction ${province.jurisdictionId}")
            check(province.id in jurisdiction.provinceIds) {
                "Province ${province.id} is missing from jurisdiction ${jurisdiction.id} provinceIds"
            }
        }

        val commanderies = mapRoot.requiredArray("commanderyRecords").map { node ->
            CanonicalCommandery(
                id = node.requiredText("id"),
                seatJurisdictionId = node.requiredText("seatJurisdictionId"),
                jurisdictionIds = node.requiredArray("jurisdictionIds").map(JsonNode::asText),
            )
        }
        requireUnique(commanderies.map { it.id }, "commandery")
        val commanderyById = commanderies.associateBy { it.id }
        commanderies.forEach { commandery ->
            check(commandery.seatJurisdictionId in commandery.jurisdictionIds) {
                "Commandery ${commandery.id} seat ${commandery.seatJurisdictionId} is not a member jurisdiction"
            }
            commandery.jurisdictionIds.forEach { jurisdictionId ->
                val jurisdiction = jurisdictionById[jurisdictionId]
                    ?: error("Commandery ${commandery.id} references unknown jurisdiction $jurisdictionId")
                check(jurisdiction.commanderyId == commandery.id) {
                    "Jurisdiction $jurisdictionId belongs to ${jurisdiction.commanderyId}, not ${commandery.id}"
                }
            }
        }
        jurisdictions.forEach { jurisdiction ->
            val commandery = commanderyById[jurisdiction.commanderyId]
                ?: error("Jurisdiction ${jurisdiction.id} references unknown commandery ${jurisdiction.commanderyId}")
            check(jurisdiction.id in commandery.jurisdictionIds) {
                "Jurisdiction ${jurisdiction.id} is missing from commandery ${commandery.id} jurisdictionIds"
            }
        }

        val allowlistRoot = objectMapper.readTree(allowlistFile.toFile())
        val conflictAllowlist = allowlistRoot.requiredArray("entries").associate { entry ->
            val scenarioCode = entry.requiredInt("scenarioCode")
            val jurisdictionId = entry.requiredText("jurisdictionId")
            val ownerNationIds = entry.requiredArray("ownerNationIds").map(JsonNode::asInt).toSet()
            val reason = entry.requiredText("reason")
            val evidenceIds = entry.requiredArray("evidenceIds").map(JsonNode::asText).toSet()
            check(ownerNationIds.size > 1 && evidenceIds.isNotEmpty() && reason.isNotBlank()) {
                "Invalid conflict allowlist entry $scenarioCode:$jurisdictionId"
            }
            (scenarioCode to jurisdictionId) to ConflictAllowance(
                effectiveYear = entry.requiredInt("effectiveYear"),
                ownerNationIds = ownerNationIds,
                evidenceIds = evidenceIds,
            )
        }
        check(conflictAllowlist.size == allowlistRoot.requiredArray("entries").size) {
            "Duplicate jurisdiction conflict allowlist entry"
        }

        val ownershipRoot = objectMapper.readTree(ownershipFile.toFile())
        val usedAllowances = mutableSetOf<Pair<Int, String>>()
        val scenarioOwners = ownershipRoot.requiredArray("scenarios").associate { scenario ->
            val scenarioCode = scenario.requiredInt("scenarioCode")
            val effectiveYear = scenario.requiredInt("effectiveYear")
            val assignments = scenario.requiredArray("assignments")
            val duplicateIds = assignments.map { it.requiredText("provinceId") }
                .groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            check(duplicateIds.isEmpty()) {
                "Scenario $scenarioCode has duplicate province assignments: ${duplicateIds.sorted().joinToString()}"
            }
            val owners = assignments.associate { assignment ->
                val ownerNode = assignment.get("ownerNationId")
                check(ownerNode == null || ownerNode.isNull || ownerNode.isIntegralNumber) {
                    "Invalid integer field ownerNationId"
                }
                assignment.requiredText("provinceId") to (ownerNode?.takeUnless(JsonNode::isNull)?.asInt() ?: 0)
            }
            val missing = provinceById.keys - owners.keys
            val unknown = owners.keys - provinceById.keys
            check(missing.isEmpty() && unknown.isEmpty()) {
                "Scenario $scenarioCode ownership coverage mismatch; " +
                    "missing=${missing.sorted().joinToString()} unknown=${unknown.sorted().joinToString()}"
            }
            val evidenceByProvince = assignments.associate { assignment ->
                assignment.requiredText("provinceId") to assignment.requiredArray("evidenceIds").map(JsonNode::asText).toSet()
            }
            jurisdictions.forEach { jurisdiction ->
                val ownersInJurisdiction = jurisdiction.provinceIds.map(owners::getValue).toSet()
                if (ownersInJurisdiction.size <= 1) return@forEach
                val key = scenarioCode to jurisdiction.id
                val allowance = conflictAllowlist[key]
                    ?: error(
                        "Scenario $scenarioCode jurisdiction ${jurisdiction.id} has unexplained owners " +
                            ownersInJurisdiction.sorted().joinToString(),
                    )
                val citedEvidence = jurisdiction.provinceIds.flatMap { evidenceByProvince.getValue(it) }.toSet()
                check(allowance.effectiveYear == effectiveYear
                    && allowance.ownerNationIds == ownersInJurisdiction
                    && allowance.evidenceIds.all(citedEvidence::contains)) {
                    "Scenario $scenarioCode jurisdiction ${jurisdiction.id} conflict allowance does not match evidence"
                }
                usedAllowances += key
            }
            scenarioCode to owners
        }
        val unusedAllowances = conflictAllowlist.keys - usedAllowances
        check(unusedAllowances.isEmpty()) {
            "Unused jurisdiction conflict allowances: " +
                unusedAllowances.sortedWith(compareBy<Pair<Int, String>> { it.first }.thenBy { it.second })
                    .joinToString { "${it.first}:${it.second}" }
        }

        return CanonicalData(
            provinces = provinces,
            jurisdictions = jurisdictions,
            jurisdictionById = jurisdictionById,
            commanderies = commanderies,
            scenarioOwners = scenarioOwners,
        )
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

    private fun requireUnique(values: List<String>, label: String) {
        val duplicates = values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        check(duplicates.isEmpty()) { "Duplicate $label ids: ${duplicates.sorted().joinToString()}" }
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

    private data class CanonicalProvince(val id: String, val jurisdictionId: String)

    private data class CanonicalJurisdiction(
        val id: String,
        val commanderyId: String,
        val seatPlaceId: String,
        val seatProvinceId: String,
        val provinceIds: List<String>,
    )

    private data class CanonicalCommandery(
        val id: String,
        val seatJurisdictionId: String,
        val jurisdictionIds: List<String>,
    )

    private data class CanonicalData(
        val provinces: List<CanonicalProvince>,
        val jurisdictions: List<CanonicalJurisdiction>,
        val jurisdictionById: Map<String, CanonicalJurisdiction>,
        val commanderies: List<CanonicalCommandery>,
        val scenarioOwners: Map<Int, Map<String, Int>>,
    )

    private data class CanonicalSignature(
        val mapSize: Long,
        val mapModifiedMillis: Long,
        val ownershipSize: Long,
        val ownershipModifiedMillis: Long,
        val allowlistSize: Long,
        val allowlistModifiedMillis: Long,
    )

    private data class ConflictAllowance(
        val effectiveYear: Int,
        val ownerNationIds: Set<Int>,
        val evidenceIds: Set<String>,
    )

    private data class CachedCanonicalData(val signature: CanonicalSignature, val data: CanonicalData)
}

internal fun resolveCommanderyController(ownerCounts: Map<Int, Int>, seatOwner: Int): Int {
    val highestCount = ownerCounts.values.maxOrNull() ?: 0
    val tiedOwners = ownerCounts.filterValues { it == highestCount }.keys
    return when {
        seatOwner in tiedOwners -> seatOwner
        else -> tiedOwners.filter { it > 0 }.minOrNull() ?: 0
    }
}
