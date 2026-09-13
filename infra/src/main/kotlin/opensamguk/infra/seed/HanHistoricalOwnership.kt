package opensamguk.infra.seed

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** Exact historical ownership inputs; compressed storage does not change the original-byte identity. */
internal object HanHistoricalOwnership {
    private const val CATALOG_SHA256 = "97a1420f872e4fcfe08e1a5e635051cbfe2632bcaf68c1aeeee00aa5764c071e"
    private val paths = setOf("data/map/han-scenario-province-ownership-v1.json",
        "data/map/han-scenario-jurisdiction-conflict-allowlist-v1.json",
        "data/map/han-commandery-supply-links-v1.json",
        "data/curated/han/territory-disconnection-adjudications-v1.json",
        "data/curated/han/supply-disconnection-adjudications-v3.json")
    private val mapper = ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    fun load(directory: Path, variantId: String, sourceCommit: String): Map<String, ByteArray> {
        val raw = Files.readAllBytes(directory.resolve("ownership-catalog.json"))
        require(sha(raw) == CATALOG_SHA256) { "Historical ownership catalog hash mismatch" }
        val catalog = mapper.readTree(raw)
        require(catalog.path("schemaVersion").asInt() == 1)
        val variant = catalog.path("variants").single { it.path("variantId").asText() == variantId }
        require(variant.path("sourceCommit").asText() == sourceCommit) { "Historical ownership source mismatch" }
        val files = variant.path("files").toList()
        require(files.size == paths.size && files.map { it.path("path").asText() }.toSet() == paths)
        return files.associate { entry ->
            val hash = entry.path("sha256").asText()
            require(hash.matches(Regex("[a-f0-9]{64}")))
            val blob = "blobs/$hash.json.gz"
            require(entry.path("blob").asText() == blob)
            val compressed = Files.readAllBytes(directory.resolve(blob))
            require(sha(compressed) == entry.path("compressedSha256").asText()) { "Historical ownership compressed hash mismatch" }
            val length = entry.path("bytes").asInt()
            require(length in 1..30_000_000)
            val bytes = GZIPInputStream(compressed.inputStream()).use { it.readNBytes(length + 1) }
            require(bytes.size == length && sha(bytes) == hash) { "Historical ownership bytes mismatch" }
            entry.path("path").asText() to bytes
        }
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
