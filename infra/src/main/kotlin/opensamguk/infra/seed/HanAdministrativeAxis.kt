package opensamguk.infra.seed

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.security.MessageDigest

enum class AdministrativeCoverage {
    CANONICAL,
    OUTSIDE_CANON,
    UNRESOLVED_PARENT,
}

data class AdministrativeCounty(
    val cityId: Int,
    val administrativeUnitId: String?,
    val commanderyId: String?,
    val zhouId: String?,
    val coverage: AdministrativeCoverage,
)

/** Read-only 州→郡國→縣 projection; missing source parents never become appointable jurisdiction. */
class HanAdministrativeAxis private constructor(
    val counties: Map<Int, AdministrativeCounty>,
    val commanderyToZhou: Map<String, String>,
) {
    fun county(cityId: Int): AdministrativeCounty? = counties[cityId]

    fun commanderyFor(cityId: Int): String? = counties[cityId]?.commanderyId

    fun zhouFor(cityId: Int): String? = counties[cityId]?.zhouId

    companion object {
        private val mapper = ObjectMapper()
        private val canonicalUnit = Regex("^hhs:([0-9]+):([^:]+):([0-9]+)$")

        fun loadPinned(): HanAdministrativeAxis {
            val world = resource("/map/han-world-v3.json")
            val axis = resource("/administration/administrative-zhou-axis.json")
            val pin = resource("/administration/administrative-axis-pin.json")
            return fromPinned(world, axis, pin)
        }

        fun fromPinned(world: ByteArray, axis: ByteArray, pinBytes: ByteArray): HanAdministrativeAxis {
            val pin = mapper.readTree(pinBytes)
            require(sha256(world) == pin.text("worldSha256")) { "administrative axis world pin mismatch" }
            require(sha256(axis) == pin.text("zhouAxisSha256")) { "administrative axis source pin mismatch" }
            val projection = project(world, axis)
            require(projection.commanderyToZhou.size == pin["sourceAxisRows"].asInt()) { "administrative axis row count changed" }
            val counts = projection.counties.values.groupingBy { it.coverage }.eachCount()
            require(counts[AdministrativeCoverage.CANONICAL] == pin["knownCountyRows"].asInt()) { "canonical county count changed" }
            require(counts[AdministrativeCoverage.OUTSIDE_CANON] == pin["outsideCanonRows"].asInt()) { "outside-canon count changed" }
            require(counts[AdministrativeCoverage.UNRESOLVED_PARENT] == pin["unresolvedParentRows"].asInt()) { "unresolved-parent count changed" }
            return projection
        }

        fun project(worldBytes: ByteArray, axisBytes: ByteArray): HanAdministrativeAxis {
            val world = mapper.readTree(worldBytes)
            val axis = mapper.readTree(axisBytes)
            val groups = linkedMapOf<String, String>()
            axis["rows"].forEach { row ->
                val group = row.text("administrativeGroupId")
                val zhou = row.text("zhouId")
                require(group.startsWith("hhs-group:") && zhou.startsWith("zhou:")) { "invalid administrative axis row" }
                require(groups.putIfAbsent(group, zhou) == null) { "duplicate commandery $group" }
            }
            val counties = linkedMapOf<Int, AdministrativeCounty>()
            val units = mutableSetOf<String>()
            world["cities"].forEach { city ->
                val cityId = city["id"].asInt()
                require(cityId > 0 && cityId !in counties) { "duplicate city $cityId" }
                val unit = city["administrativeUnitId"]?.takeUnless { it.isNull }?.asText()
                val county = if (unit != null) {
                    val match = requireNotNull(canonicalUnit.matchEntire(unit)) { "unsupported administrative unit $unit" }
                    require(units.add(unit)) { "duplicate administrative unit $unit" }
                    val group = "hhs-group:${match.groupValues[1]}:${match.groupValues[2]}"
                    val zhou = requireNotNull(groups[group]) { "missing 州 for 郡國 $group" }
                    AdministrativeCounty(cityId, unit, group, zhou, AdministrativeCoverage.CANONICAL)
                } else {
                    val coverage = if (city["meta"]?.get("ju")?.asText() == "동이") {
                        AdministrativeCoverage.OUTSIDE_CANON
                    } else {
                        AdministrativeCoverage.UNRESOLVED_PARENT
                    }
                    AdministrativeCounty(cityId, null, null, null, coverage)
                }
                counties[cityId] = county
            }
            require(groups.keys.all { group -> counties.values.any { it.commanderyId == group } }) {
                "administrative axis contains an unrepresented 郡國"
            }
            return HanAdministrativeAxis(counties, groups)
        }

        private fun resource(path: String): ByteArray = requireNotNull(HanAdministrativeAxis::class.java.getResourceAsStream(path)) {
            "missing administrative resource $path"
        }.use { it.readBytes() }

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }

        private fun JsonNode.text(field: String) = get(field)?.asText()?.takeIf { it.isNotBlank() }
            ?: error("missing administrative field $field")
    }
}
