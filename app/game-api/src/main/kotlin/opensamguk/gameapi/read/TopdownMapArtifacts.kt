package opensamguk.gameapi.read

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.infra.seed.ResolvedWorldArtifacts
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

data class TopdownMapAsset(val bytes: ByteArray, val contentType: MediaType)

/** Immutable bake files; the active world's selection is checked separately on every preview. */
@Component
class TopdownMapArtifacts(
    @Value("\${TOPDOWN_MAP_ROOT:data/map/topdown}") private val directory: String,
    @Value("\${TOPDOWN_BAKE_ID:}") private val selectedBakeId: String,
    private val mapper: ObjectMapper,
) {
    private data class Entry(val file: String, val sha256: String, val bytes: Long, val rawSha256: String)
    private data class Bundle(val root: Path, val manifest: JsonNode, val bytes: ByteArray, val files: Map<String, Entry>)

    fun binding(artifacts: ResolvedWorldArtifacts): String? {
        val bundle = load(selectedBakeId) ?: return null
        val manifest = bundle.manifest
        if (manifest.path("partial").asBoolean(true) || !manifest.path("inputFingerprint").path("region").isNull) return null
        if (manifest.path("mapRelease").asText() != artifacts.variant.artifactId) return null
        val sources = mapOf(
            "hanTilesSha256" to "data/map/han-tiles.json",
            "worldJsonSha256" to "infra/src/main/resources/map/han-world-v3.json",
            "roadsSha256" to "data/map/han-land-roads-v1.json",
        )
        val same = runCatching {
            sources.all { (key, file) ->
                manifest.path("inputFingerprint").path(key).asText() == sha256(artifacts.artifactBytes(file))
            }
        }.getOrDefault(false)
        return selectedBakeId.takeIf { same }
    }

    fun asset(bakeId: String, requested: String): TopdownMapAsset? {
        val name = requested.removePrefix("/")
        if (name != "manifest.json" && !FILE.matches(name)) return null
        val bundle = load(bakeId) ?: return null
        if (name == "manifest.json") return TopdownMapAsset(bundle.bytes, MediaType.APPLICATION_JSON)
        val entry = bundle.files[name] ?: return null
        val bytes = runCatching {
            val path = bundle.root.resolve(name).toRealPath()
            require(path.startsWith(bundle.root) && Files.isRegularFile(path))
            require(Files.size(path) == entry.bytes && entry.bytes <= MAX_FILE_BYTES)
            Files.readAllBytes(path).also { bytes ->
                require(bytes.size.toLong() == entry.bytes && sha256(bytes) == entry.sha256)
                val raw = if (name.endsWith(".gz")) GZIPInputStream(ByteArrayInputStream(bytes)).use {
                    it.readNBytes(MAX_FILE_BYTES.toInt() + 1)
                } else bytes
                require(raw.size <= MAX_FILE_BYTES && sha256(raw) == entry.rawSha256)
                if (name.startsWith("grid/L0/")) require(raw.size == 4 * 256 * 256)
                if (name == "grid/L2.bin.gz") {
                    val overview = bundle.manifest.path("overview")
                    require(raw.size.toLong() == 4L * overview.path("cols").asLong() * overview.path("rows").asLong())
                }
            }
        }.getOrNull() ?: return null
        return TopdownMapAsset(bytes, if (name.endsWith(".json")) MediaType.APPLICATION_JSON else MediaType.APPLICATION_OCTET_STREAM)
    }

    private fun load(bakeId: String): Bundle? = runCatching {
        require(SHA.matches(bakeId))
        val root = Path.of(directory).toRealPath()
        val bundleRoot = root.resolve(bakeId).toRealPath()
        require(bundleRoot.startsWith(root) && bundleRoot != root)
        val path = bundleRoot.resolve("manifest.json").toRealPath()
        require(path.startsWith(bundleRoot) && Files.isRegularFile(path) && Files.size(path) <= MAX_MANIFEST_BYTES)
        val bytes = Files.readAllBytes(path)
        val manifest = mapper.readTree(bytes)
        require(manifest.path("schemaVersion").asInt() == 1 && manifest.path("formatVersion").asInt() == 1)
        require(manifest.path("artifactId").asText() == "topdown-bake" && manifest.path("bakeId").asText() == bakeId)
        require(manifest.path("chunkSize").asInt() == 256 && manifest.path("undrawnTile").asInt() == 65535)
        require(manifest.path("shape").path("cols").asInt() > 0 && manifest.path("shape").path("rows").asInt() > 0)
        require(manifest.path("files").isArray && manifest.path("chunks").isArray)
        require(identityHash(manifest, mapper) == bakeId)
        val entries = manifest.path("files").map { item ->
            require(item.path("bytes").isIntegralNumber)
            val entry = Entry(item.path("file").asText(), item.path("sha256").asText(), item.path("bytes").asLong(), item.path("rawSha256").asText())
            require(FILE.matches(entry.file) && SHA.matches(entry.sha256) && entry.bytes in 0..MAX_FILE_BYTES)
            require(SHA.matches(item.path("rawSha256").asText()))
            require(item.path("compression").asText() == if (entry.file.endsWith(".gz")) "gzip" else "none")
            entry
        }
        require(entries.isNotEmpty() && entries.map { it.file }.distinct().size == entries.size)
        val publicEntries = manifest.path("chunks").filter { it.has("file") } +
            listOf("overview", "places", "defects").map { manifest.path(it) }
        require(publicEntries.size == entries.size)
        val files = entries.associateBy { it.file }
        require(publicEntries.all { item ->
            val entry = files[item.path("file").asText()]
            entry != null && entry.sha256 == item.path("sha256").asText() && entry.bytes == item.path("bytes").asLong(-1)
                && entry.rawSha256 == item.path("rawSha256").asText()
        })
        Bundle(bundleRoot, manifest, bytes, files)
    }.getOrNull()

    companion object {
        private val SHA = Regex("[0-9a-f]{64}")
        private val FILE = Regex("(?:grid/L0/[0-9]+_[0-9]+\\.bin\\.gz|grid/L2\\.bin\\.gz|places\\.json\\.gz|defects\\.json)")
        private const val MAX_FILE_BYTES = 16L * 1024 * 1024
        private const val MAX_MANIFEST_BYTES = 2L * 1024 * 1024

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

        internal fun identityHash(manifest: JsonNode, mapper: ObjectMapper): String {
            val identity = mapper.createObjectNode()
            for (key in listOf("inputFingerprint", "mapRelease", "kitVersion", "formatVersion")) {
                require(manifest.has(key))
                identity.set<JsonNode>(key, manifest.get(key))
            }
            fun sorted(node: JsonNode): JsonNode = when {
                node.isObject -> mapper.createObjectNode().also { target ->
                    node.fieldNames().asSequence().sorted().forEach { key ->
                        require(key.all { it.code in 32..126 })
                        target.set<JsonNode>(key, sorted(node.get(key)))
                    }
                }
                node.isArray -> mapper.createArrayNode().also { target -> node.forEach { target.add(sorted(it)) } }
                else -> node
            }
            return sha256(mapper.writeValueAsBytes(sorted(identity)))
        }
    }
}
