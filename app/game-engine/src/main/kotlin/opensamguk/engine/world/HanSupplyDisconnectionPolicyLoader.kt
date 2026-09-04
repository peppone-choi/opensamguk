package opensamguk.engine.world

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.logic.world.SupplyDisconnectionDecision
import opensamguk.logic.world.SupplyFallbackPolicy
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Path

/** Strict loader for reviewed Han supply-disconnection decisions. */
@Component
class HanSupplyDisconnectionPolicyLoader(
    private val objectMapper: ObjectMapper,
    @Value("\${HAN_SUPPLY_DISCONNECTION_LEDGER_FILE:data/curated/han/supply-disconnection-adjudications-v1.json}")
    private val ledgerPath: String,
    @Value("\${HAN_MAP_FILE:data/map/han-tiles.json}") private val mapPath: String,
    @Value("\${HAN_RUNTIME_MAP_FILE:classpath:map/han.json}") private val runtimeMapPath: String,
    @Value("\${HAN_SUPPLY_SOURCE_LEDGER_FILE:data/curated/han/territory-disconnection-adjudications-v1.json}")
    private val sourceLedgerPath: String,
) {
    @Volatile
    private var cached: CanonicalPolicies? = null

    fun load(scenarioCode: Int, liveCityProvinceIndices: Map<Int, Int>): Map<Int, SupplyFallbackPolicy> {
        val canonical = canonical()
        val active = canonical.rows.filter { scenarioCode in it.effectiveFrom..it.effectiveTo }
        val duplicates = active.groupingBy { it.cityId }.eachCount().filterValues { it > 1 }.keys
        check(duplicates.isEmpty()) {
            "Han supply ledger has duplicate active rows for scenario $scenarioCode: ${duplicates.sorted()}"
        }
        val result = linkedMapOf<Int, SupplyFallbackPolicy>()
        for (row in active.sortedBy { it.cityId }) {
            val liveProvince = liveCityProvinceIndices[row.cityId]
                ?: error("Han supply ledger active city ${row.cityId} is absent from live spatial mapping")
            check(liveProvince == row.provinceIndex) {
                "Han supply ledger city ${row.cityId} live province drift: $liveProvince != ${row.provinceIndex}"
            }
            result[row.cityId] = SupplyFallbackPolicy(row.decision, row.sourceLedgerRow)
        }
        return result
    }

    private fun canonical(): CanonicalPolicies = cached ?: synchronized(this) {
        cached ?: loadCanonical().also { cached = it }
    }

    private fun loadCanonical(): CanonicalPolicies {
        try {
            val tiles = readTree(mapPath)
            val provinces = tiles.requiredArray("provinceRecords")
            val jurisdictionIds = tiles.requiredArray("jurisdictionRecords")
                .map { it.requiredText("id") }.toSet()
            val runtimeCities = readTree(runtimeMapPath)
                .requiredArray("cities")
            val runtimeById = runtimeCities.associateBy { it.requiredInt("id") }
            check(runtimeById.size == runtimeCities.size) { "Han runtime map contains duplicate city ids" }

            val sourceRoot = readTree(sourceLedgerPath)
            check(sourceRoot.requiredInt("schemaVersion") == 1) { "Han source ledger schemaVersion must be 1" }
            val sourceRows = sourceRoot.requiredArray("adjudications")
            val sourceByKey = sourceRows.associateBy { it.requiredText("componentKey") }
            check(sourceByKey.size == sourceRows.size) { "Han source ledger contains duplicate component keys" }

            val root = readTree(ledgerPath)
            check(root.requiredInt("schemaVersion") == 1) { "Han supply ledger schemaVersion must be 1" }
            val rows = root.requiredArray("decisions").mapIndexed { index, node ->
                val cityId = node.requiredInt("runtimeCityId")
                val runtime = runtimeById[cityId]
                    ?: error("Han supply ledger decision[$index] references unknown runtime city $cityId")
                val physicalPlaceId = node.requiredText("physicalPlaceId")
                check(runtime.requiredText("physicalPlaceId") == physicalPlaceId) {
                    "Han supply ledger city $cityId physicalPlaceId drift"
                }
                val provinceIndex = runtime.requiredInt("provinceId")
                check(provinceIndex in provinces.indices) {
                    "Han supply ledger city $cityId has invalid runtime province $provinceIndex"
                }
                val jurisdictionId = node.requiredText("jurisdictionId")
                check(jurisdictionId in jurisdictionIds) {
                    "Han supply ledger city $cityId references unknown jurisdiction $jurisdictionId"
                }
                check(provinces[provinceIndex].requiredText("jurisdictionId") == jurisdictionId) {
                    "Han supply ledger city $cityId jurisdictionId drift"
                }
                val decisionText = node.requiredText("decision")
                val decision = runCatching { SupplyDisconnectionDecision.valueOf(decisionText) }
                    .getOrElse { error("Han supply ledger city $cityId has unknown decision $decisionText") }
                val sourceLedgerRow = node.requiredText("sourceLedgerRow")
                val source = sourceByKey[sourceLedgerRow]
                    ?: error("Han supply ledger city $cityId references unknown sourceLedgerRow $sourceLedgerRow")
                val parentRegionId = provinces[provinceIndex].requiredText("parentRegionId")
                val sourceMemberIds = source.requiredArray("memberIds").map { member ->
                    check(member.isTextual && member.asText().isNotBlank()) {
                        "Han source ledger row $sourceLedgerRow has invalid memberId"
                    }
                    member.asText()
                }
                check(source.requiredText("unitId") == parentRegionId && jurisdictionId in sourceMemberIds) {
                    "Han supply ledger city $cityId sourceLedgerRow $sourceLedgerRow does not cover " +
                        "parent $parentRegionId jurisdiction $jurisdictionId"
                }
                val expectedSourceVerdict = SOURCE_VERDICT_BY_DECISION.getValue(decision)
                check(source.requiredText("verdict") == expectedSourceVerdict) {
                    "Han supply ledger city $cityId decision $decision does not match source verdict " +
                        source.requiredText("verdict")
                }
                node.requiredText("rationale")
                check(node.requiredText("expectedCurrentReachability") == "CITY_ONLY") {
                    "Han supply ledger city $cityId expectedCurrentReachability must be CITY_ONLY"
                }
                val effectiveFrom = node.requiredInt("effectiveScenarioFrom")
                val effectiveTo = node.requiredInt("effectiveScenarioTo")
                check(effectiveFrom <= effectiveTo) {
                    "Han supply ledger city $cityId has invalid effective range $effectiveFrom..$effectiveTo"
                }
                PolicyRow(
                    cityId = cityId,
                    provinceIndex = provinceIndex,
                    decision = decision,
                    sourceLedgerRow = sourceLedgerRow,
                    effectiveFrom = effectiveFrom,
                    effectiveTo = effectiveTo,
                )
            }
            return CanonicalPolicies(rows)
        } catch (error: IllegalStateException) {
            throw error
        } catch (error: Exception) {
            throw IllegalStateException("Invalid Han supply disconnection ledger", error)
        }
    }

    private fun readTree(location: String): JsonNode {
        if (!location.startsWith("classpath:")) {
            return objectMapper.readTree(Path.of(location).toFile())
        }
        val resourcePath = location.removePrefix("classpath:").removePrefix("/")
        val stream = Thread.currentThread().contextClassLoader.getResourceAsStream(resourcePath)
            ?: javaClass.classLoader.getResourceAsStream(resourcePath)
            ?: error("Missing classpath resource $resourcePath")
        return stream.use(objectMapper::readTree)
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

    private data class PolicyRow(
        val cityId: Int,
        val provinceIndex: Int,
        val decision: SupplyDisconnectionDecision,
        val sourceLedgerRow: String,
        val effectiveFrom: Int,
        val effectiveTo: Int,
    )

    private data class CanonicalPolicies(val rows: List<PolicyRow>)

    private companion object {
        val SOURCE_VERDICT_BY_DECISION = mapOf(
            SupplyDisconnectionDecision.PROTECT_GEOMETRY_DEFECT to "GEOMETRY_DEFECT",
            SupplyDisconnectionDecision.PROTECT_PARENT_MISASSIGNMENT to "PARENT_MISASSIGNMENT",
            SupplyDisconnectionDecision.UPHOLD_WATER_ROUTE_ONLY to "WATER_SEPARATED",
            SupplyDisconnectionDecision.UPHOLD_HISTORICAL_EXCLAVE to "HISTORICAL_EXCLAVE",
        )
    }
}
