package opensamguk.infra.seed

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.logic.world.WorldMapVariant
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** Immutable release inputs; never falls back to mutable current-world files. */
internal object Han848Artifacts {
    private const val CATALOG_SHA256 = "d99603c818dec50d632e8c4fc53b868c1369ad7f4584e6c8a88925b155e55a51"
    private val ownershipPaths = setOf(
        "data/map/han-scenario-province-ownership-v1.json",
        "data/map/han-scenario-jurisdiction-conflict-allowlist-v1.json",
        "data/map/han-commandery-supply-links-v1.json",
        "data/curated/han/territory-disconnection-adjudications-v1.json",
        "data/curated/han/supply-disconnection-adjudications-v3.json",
    )
    private val mapper = ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    fun load(root: Path): ResolvedWorldArtifacts {
        val variant = WorldMapVariant.V3_848
        val directory = root.resolve("data/map/han-world-v3-848-artifacts-v1")
        val raw = Files.readAllBytes(directory.resolve("catalog.json"))
        require(sha(raw) == CATALOG_SHA256) { "848 release catalog hash mismatch" }
        val catalog = mapper.readTree(raw)
        require(catalog.path("schemaVersion").asInt() == 1 &&
            catalog.path("artifactId").asText() == variant.artifactId &&
            catalog.path("logicalMapName").asText() == "han-world-v3" &&
            catalog.path("cityCount").asInt() == variant.cityCount) { "848 release identity mismatch" }
        val entries = catalog.path("files").toList()
        val paths = StrategicTopologyJson.artifactPaths() + ownershipPaths
        require(entries.size == paths.size && entries.map { it.path("path").asText() }.toSet() == paths) {
            "848 release artifact path set mismatch"
        }
        val bytes = entries.associate { entry ->
            val hash = entry.path("sha256").asText()
            require(hash.matches(Regex("[a-f0-9]{64}")))
            val blob = "blobs/$hash.json.gz"
            require(entry.path("blob").asText() == blob)
            val compressed = Files.readAllBytes(directory.resolve(blob))
            require(sha(compressed) == entry.path("compressedSha256").asText()) { "848 compressed artifact hash mismatch" }
            val length = entry.path("bytes").asInt()
            require(length in 1..30_000_000)
            val data = GZIPInputStream(compressed.inputStream()).use { it.readNBytes(length + 1) }
            require(data.size == length && sha(data) == hash) { "848 artifact hash/length mismatch" }
            entry.path("path").asText() to data
        }
        val projection = StrategicTopologyJson.loadVersion("han-world-v3", variant.cityCount, bytes::getValue)
        return ResolvedWorldArtifacts(variant, projection, bytes)
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
