package opensamguk.gameapi.controller

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gameapi.read.ActiveWorldArtifactResolver
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
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

/** Hash-bound 州 lookup. Historical terrain bytes and their province-image pin remain intact. */
@RestController
@RequestMapping("/api/map")
class JuMapController(
    private val worlds: ActiveWorldArtifactResolver,
    @Value("\${HAN_JU_INDEX_FILE:data/map/han-ju-index-v1.json}") private val indexFile: String,
) {
    private val mapper = ObjectMapper()

    @GetMapping("/ju")
    fun ju(
        @RequestParam(defaultValue = "han-world-v3") mapCode: String,
        @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) ifNoneMatch: String?,
    ): ResponseEntity<ByteArray> {
        if (mapCode != "han-world-v3") return ResponseEntity.notFound().build()
        val selected = worlds.resolve()?.artifacts ?: return ResponseEntity.notFound().build()
        val sourceHash = sha256(selected.artifactBytes("data/map/han-tiles.json"))
        val file = Path.of(indexFile)
        if (!Files.isRegularFile(file)) return ResponseEntity.notFound().build()
        val rows = mapper.readTree(Files.readAllBytes(file)).path("byTerrainSha256").path(sourceHash)
        if (!rows.isArray) return ResponseEntity.notFound().build()
        val body = mapper.writeValueAsBytes(mapOf("sourceSha256" to sourceHash, "juByParent" to rows))
        val tag = "\"sha256-${sha256(body)}\""
        val response = if (ifNoneMatch == tag) ResponseEntity.status(304) else ResponseEntity.ok()
        return response.eTag(tag).cacheControl(CacheControl.noCache().cachePrivate().mustRevalidate())
            .varyBy(HttpHeaders.COOKIE, HttpHeaders.AUTHORIZATION)
            .contentType(MediaType.APPLICATION_JSON).body(if (ifNoneMatch == tag) null else body)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
