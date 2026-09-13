package opensamguk.gameapi.controller

import opensamguk.gameapi.read.ActiveWorldArtifactResolver
import java.nio.file.Files
import java.nio.file.Path
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * V3 terrain comes from the active world's verified historical artifact set.
 * Its exact-byte ETag is privately revalidated on every request, including after resets.
 * Legacy maps retain the configured runtime-file source and return 404 when absent.
 */
@RestController
@RequestMapping("/api/map")
class TerrainMapController(
    @Value("\${HAN_MAP_FILE:data/map/han-tiles.json}") private val mapFile: String,
    private val worlds: ActiveWorldArtifactResolver,
) {

    @GetMapping("/terrain")
    fun terrain(
        @RequestParam(defaultValue = "han") mapCode: String,
        @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) ifNoneMatch: String?,
    ): ResponseEntity<ByteArray> {
        if (!MAP_CODE.matches(mapCode)) return ResponseEntity.notFound().build()
        if (mapCode == "han-world-v3") {
            val selected = worlds.resolve()?.artifacts ?: return ResponseEntity.notFound().build()
            val bytes = selected.artifactBytes("data/map/han-tiles.json")
            val tag = "\"sha256-${java.security.MessageDigest.getInstance("SHA-256")
                .digest(bytes).joinToString("") { "%02x".format(it) }}\""
            val response = if (ifNoneMatch == tag) ResponseEntity.status(304) else ResponseEntity.ok()
            return response.eTag(tag).cacheControl(CacheControl.noCache().cachePrivate().mustRevalidate())
                .varyBy(HttpHeaders.COOKIE, HttpHeaders.AUTHORIZATION)
                .contentType(MediaType.APPLICATION_JSON).body(if (ifNoneMatch == tag) null else bytes)
        }
        val configured = Path.of(mapFile)
        val path: Path = if (mapCode == "han") configured else configured.resolveSibling("$mapCode-tiles.json")
        return servePath(path, MediaType.APPLICATION_JSON, ifNoneMatch, strongHash = mapCode == "han-world-v3")
    }

    @GetMapping("/provinces")
    fun provinces(
        @RequestParam(defaultValue = "han") mapCode: String,
        @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) ifNoneMatch: String?,
    ): ResponseEntity<ByteArray> {
        if (mapCode != "han-world-v3") return serveMapFile(mapCode, "provinces.png", MediaType.IMAGE_PNG, ifNoneMatch)
        val selected = worlds.resolve()?.artifacts ?: return ResponseEntity.notFound().build()
        val imagePath = Path.of(mapFile).resolveSibling("$mapCode-provinces.png")
        val metadataPath = Path.of(mapFile).resolveSibling("$mapCode-provinces.meta.json")
        if (!Files.isRegularFile(imagePath) || !Files.isRegularFile(metadataPath)) return ResponseEntity.notFound().build()
        val bytes = Files.readAllBytes(imagePath)
        val metadata = com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readAllBytes(metadataPath))
        require(metadata.path("sourceSha256").asText() == sha256(selected.artifactBytes("data/map/han-tiles.json"))) {
            "Province image source differs from selected historical terrain"
        }
        val imageHash = sha256(bytes)
        require(metadata.path("pngSha256").asText() == imageHash) { "Province image content differs from build metadata" }
        val tag = "\"sha256-$imageHash\""
        return (if (ifNoneMatch == tag) ResponseEntity.status(304) else ResponseEntity.ok())
            .eTag(tag).cacheControl(CacheControl.noCache().cachePrivate().mustRevalidate())
            .varyBy(HttpHeaders.COOKIE, HttpHeaders.AUTHORIZATION).contentType(MediaType.IMAGE_PNG)
            .body(if (ifNoneMatch == tag) null else bytes)
    }

    private fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun serveMapFile(
        mapCode: String,
        fileName: String,
        contentType: MediaType,
        ifNoneMatch: String?,
    ): ResponseEntity<ByteArray> {
        if (!MAP_CODE.matches(mapCode)) return ResponseEntity.notFound().build()
        val path = Path.of(mapFile).resolveSibling("$mapCode-$fileName")
        return servePath(path, contentType, ifNoneMatch)
    }

    private fun servePath(
        path: Path,
        contentType: MediaType,
        ifNoneMatch: String?,
        strongHash: Boolean = false,
    ): ResponseEntity<ByteArray> {
        if (!Files.isRegularFile(path)) return ResponseEntity.notFound().build()

        // V3 topology consumers must compare the exact bytes they receive, not file metadata.
        val exactBytes = if (strongHash) Files.readAllBytes(path) else null
        val tag = if (exactBytes != null) "\"sha256-${java.security.MessageDigest.getInstance("SHA-256")
            .digest(exactBytes).joinToString("") { "%02x".format(it) }}\""
        else "\"${Files.size(path)}-${Files.getLastModifiedTime(path).toMillis()}\""
        if (ifNoneMatch == tag) {
            return ResponseEntity.status(304).eTag(tag).build()
        }
        return ResponseEntity.ok()
            .eTag(tag)
            .cacheControl(CacheControl.maxAge(java.time.Duration.ofHours(1)).cachePublic())
            .contentType(contentType)
            .body(exactBytes ?: Files.readAllBytes(path))
    }

    private companion object {
        val MAP_CODE = Regex("[a-z0-9]+(?:[a-z0-9_-]*[a-z0-9])?")
    }
}
