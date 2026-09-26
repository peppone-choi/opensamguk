package opensamguk.infra.seed

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.logic.world.StrategicRouteProjection
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Reads a reviewed historical loader input set without changing the logical map
 * name or falling back to current files. Selection by a complete world roster
 * and runtime wiring are deliberately separate from this archive reader.
 */
object HistoricalArtifacts {
    internal const val CATALOG_SHA256 = "bbf8efcb3691a4670ea15801bc379486926fbdc34528d07c0d0a9c0e7b17dd53"
    private val versions = mapOf(
        "han-world-v3-832" to ("cf5a77806212c1d8d08d617b292a6fb5fd7cc496" to 832),
        "han-world-v3-835" to ("91fad09734e472b72a3b8720b0bce9f5a7ac3ae5" to 835),
    )
    private val mapper = ObjectMapper()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    fun loadFromDirectory(root: Path, variantId: String): StrategicRouteProjection =
        loadBundleFromDirectory(root, variantId).projection

    internal fun loadBundleFromDirectory(root: Path, variantId: String): ResolvedWorldArtifacts {
        val (commit, count) = requireNotNull(versions[variantId]) { "Unknown Han artifact set: $variantId" }
        try {
            val directory = root.resolve("data/map/han-world-artifacts-v1")
            val catalogPath = directory.resolve("catalog.json")
            RepositoryInputTrace.file(catalogPath)
            val raw = Files.readAllBytes(catalogPath)
            require(sha(raw) == CATALOG_SHA256) { "Historical Han catalog hash mismatch" }
            val catalog = mapper.readTree(raw)
            require(catalog.path("schemaVersion").asInt() == 1 &&
                catalog.path("catalogId").asText() == "han-world-strategic-artifact-sets-v1")
            val variants = catalog.path("variants").toList()
            require(variants.map { it.path("variantId").asText() } == versions.keys.toList()) { "Historical variant registry drift" }
            val variant = variants.single { it.path("variantId").asText() == variantId }
            require(variant.path("sourceCommit").asText() == commit &&
                variant.path("cityCount").asInt() == count &&
                variant.path("logicalMapName").asText() == "han-world-v3") { "Historical source domain mismatch" }
            val files = variant.path("files").toList()
            val paths = files.map { it.path("path").asText() }
            require(paths.size == StrategicTopologyJson.artifactPaths().size &&
                paths.toSet() == StrategicTopologyJson.artifactPaths()) { "Historical artifact path set mismatch" }
            val bytes = files.associate { entry ->
                val hash = entry.path("sha256").asText()
                require(hash.matches(Regex("[a-f0-9]{64}"))) { "Invalid historical blob digest" }
                val blob = "blobs/$hash.json"
                require(entry.path("blob").asText() == blob) { "Invalid historical blob path" }
                val blobPath = directory.resolve(blob)
                RepositoryInputTrace.file(blobPath)
                val data = Files.readAllBytes(blobPath)
                require(data.size == entry.path("bytes").asInt() && sha(data) == hash) { "Historical blob hash/length mismatch" }
                entry.path("path").asText() to data
            }
            // Retain all existing manifest, identity, terrain and connectivity checks.
            val runtimeVariant = opensamguk.logic.world.WorldMapVariant.entries.single { it.artifactId == variantId }
            val projection = StrategicTopologyJson.loadVersion("han-world-v3", count, bytes::getValue)
            val ownership = HistoricalOwnership.load(directory, variantId, commit)
            return ResolvedWorldArtifacts(runtimeVariant, projection, bytes + ownership)
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Exception) {
            throw IllegalArgumentException("Unable to load historical Han artifact set $variantId", error)
        }
    }

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
