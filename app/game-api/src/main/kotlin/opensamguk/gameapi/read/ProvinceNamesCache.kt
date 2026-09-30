package opensamguk.gameapi.read

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gameapi.dto.ProvinceNameDto
import opensamguk.gameapi.dto.ProvinceNamesDto
import opensamguk.infra.seed.ResolvedWorldArtifacts
import java.security.MessageDigest
import java.util.Collections

/** Prepared projection only: HTTP/public security wiring requires the geographic DTO contract review. */
internal class ProvinceNamesRepresentation(val dto: ProvinceNamesDto, bytes: ByteArray) {
    private val payload = bytes.copyOf()
    val etag: String = "\"sha256-${provinceNamesSha256(payload)}\""
    fun body(): ByteArray = payload.copyOf()
}

/**
 * Cache immutable responses, never active-world selection. The caller must resolve the current world
 * and persisted spatial pins on every request, before consulting this cache or a conditional ETag.
 * No Spring bean or HTTP endpoint is registered by this preparation.
 */
internal class ProvinceNamesCache(mapper: ObjectMapper = ObjectMapper()) {
    private val mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
    private data class Key(val worldId: Int, val release: String, val revision: String, val topology: String, val source: String)
    private val cache = LinkedHashMap<Key, ProvinceNamesRepresentation>(4, 0.75f, true)

    @Synchronized
    fun get(worldId: Int, artifacts: ResolvedWorldArtifacts): ProvinceNamesRepresentation {
        require(worldId > 0)
        val topology = artifacts.projection.topology
        val source = requireNotNull(topology.artifactHashes[TILES_PATH]) { "Province names require a pinned terrain source" }
        require(SHA.matches(source) && SHA.matches(topology.contentHash)) { "Invalid province names fingerprint" }
        val key = Key(worldId, artifacts.variant.artifactId, topology.topologyRevision, topology.contentHash, source)
        cache[key]?.let { return it }
        val bytes = artifacts.artifactBytes(TILES_PATH)
        require(provinceNamesSha256(bytes) == source) { "Province names source does not match selected terrain pin" }
        val names = decode(bytes, topology.landProvinceIds.toSet())
        val dto = ProvinceNamesDto(worldId, key.release, key.revision, key.topology, key.source, names)
        val response = ProvinceNamesRepresentation(dto, mapper.writeValueAsBytes(dto))
        cache[key] = response
        if (cache.size > MAX_ENTRIES) cache.remove(cache.keys.first())
        return response
    }

    private fun decode(bytes: ByteArray, expectedIds: Set<String>): List<ProvinceNameDto> {
        val names = mutableListOf<ProvinceNameDto>()
        var found = false
        mapper.factory.createParser(bytes).use { parser ->
            require(parser.nextToken() == JsonToken.START_OBJECT) { "Province names source must be an object" }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                require(parser.currentToken == JsonToken.FIELD_NAME) { "Malformed province names source" }
                val field = parser.currentName
                val token = parser.nextToken()
                if (field != "provinceRecords") { parser.skipChildren(); continue }
                require(!found && token == JsonToken.START_ARRAY) { "Missing or repeated province records" }
                found = true
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    require(parser.currentToken == JsonToken.START_OBJECT) { "Invalid province name row" }
                    var id: String? = null
                    var name: String? = null
                    while (parser.nextToken() != JsonToken.END_OBJECT) {
                        require(parser.currentToken == JsonToken.FIELD_NAME)
                        val key = parser.currentName
                        val value = parser.nextToken()
                        when (key) {
                            "id" -> { require(value == JsonToken.VALUE_STRING); id = parser.text }
                            "displayName" -> { require(value == JsonToken.VALUE_STRING); name = parser.text }
                            else -> parser.skipChildren()
                        }
                    }
                    names += ProvinceNameDto(requireNotNull(id).also { require(it.isNotBlank()) },
                        requireNotNull(name).also { require(it.isNotBlank()) })
                    require(names.size <= expectedIds.size) { "Unexpected province name count" }
                }
            }
            require(parser.nextToken() == null) { "Trailing province name source" }
        }
        require(found && names.map { it.provinceId }.toSet().size == names.size &&
            names.map { it.provinceId }.toSet() == expectedIds) { "Province names do not match the selected topology" }
        return Collections.unmodifiableList(names.sortedBy { it.provinceId })
    }

    companion object {
        private const val MAX_ENTRIES = 4
        private val SHA = Regex("[0-9a-f]{64}")
        const val TILES_PATH = "data/map/han-tiles.json"
    }
}

private fun provinceNamesSha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }
