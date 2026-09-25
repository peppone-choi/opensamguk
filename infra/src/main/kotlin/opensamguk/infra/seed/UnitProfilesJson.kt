package opensamguk.infra.seed

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.security.MessageDigest
import opensamguk.logic.war.UnitProfile
import opensamguk.logic.war.UnitProfiles

object UnitProfilesJson {
    private const val RESOURCE = "battle/hwiha-unit-profiles-v1.json"
    private val mapper = ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    fun loadDefault(): UnitProfiles = load(requireNotNull(
        UnitProfilesJson::class.java.classLoader.getResourceAsStream(RESOURCE)) {
        "Missing HWIHA unit profiles resource"
    }.use { it.readBytes() })

    fun load(bytes: ByteArray): UnitProfiles {
        val root = try { mapper.readTree(bytes) } catch (e: java.io.IOException) {
            throw IllegalArgumentException("Malformed HWIHA unit profiles JSON", e)
        }
        require(root != null)
        keys(root, setOf("version", "profiles", "unsupportedCrewTypeIds"))
        require(root["profiles"].isArray && root["unsupportedCrewTypeIds"].isArray)
        val profiles = root["profiles"].map { row ->
            keys(row, setOf("crewTypeId", "movementSteps", "attackRange", "attackPower", "defencePower", "initiative"))
            UnitProfile(integer(row["crewTypeId"]),integer(row["movementSteps"]),integer(row["attackRange"]),
                integer(row["attackPower"]),integer(row["defencePower"]),integer(row["initiative"]))
        }
        val unsupported = root["unsupportedCrewTypeIds"].map(::integer)
        require(unsupported.distinct().size == unsupported.size)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return UnitProfiles(integer(root["version"]),hash,profiles,unsupported.toSet())
    }
    private fun integer(node: JsonNode): Int {
        require(node.isIntegralNumber && node.canConvertToInt()) { "Expected actual Int" }
        return node.intValue()
    }
    private fun keys(node: JsonNode, expected: Set<String>) {
        require(node.isObject && node.fieldNames().asSequence().toSet() == expected) { "Unexpected profile fields" }
    }
}
