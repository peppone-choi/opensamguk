package opensamguk.gameapi.controller

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gameapi.dto.ProvinceNamesMetadataDto
import opensamguk.gameapi.read.ProvinceNamesReader
import opensamguk.gameapi.read.ProvinceNamesRepresentation
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.UriComponentsBuilder
import org.springframework.util.MultiValueMap
import java.security.MessageDigest
import java.time.Duration

/** Only geographic labels and immutable fingerprints are served by this explicit public GET surface. */
@RestController
class ProvinceNamesController(private val reader: ProvinceNamesReader) {
    private val mapper = ObjectMapper()

    @GetMapping("/api/map/provinces/names")
    fun current(@RequestParam params: MultiValueMap<String, String>,
                @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) ifNoneMatch: String?): ResponseEntity<ByteArray> {
        if (params.isNotEmpty()) return failure(400)
        return selected { response ->
            val pins = pins(response)
            val path = UriComponentsBuilder.fromPath(PINNED_PATH).apply {
                pins.forEach { (key, value) -> queryParam(key, value) }
            }.build().encode().toUriString()
            val dto = response.dto
            val bytes = mapper.writeValueAsBytes(ProvinceNamesMetadataDto(dto.worldId, dto.mapRelease,
                dto.topologyRevision, dto.topologyHash, dto.sourceSha256, response.sha256, path))
            val tag = "\"sha256-${sha256(bytes)}\""
            serve(bytes, tag, ifNoneMatch, CacheControl.noCache().cachePublic().mustRevalidate())
        }
    }

    @GetMapping(PINNED_PATH)
    fun pinned(@RequestParam params: MultiValueMap<String, String>,
               @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) ifNoneMatch: String?): ResponseEntity<ByteArray> {
        if (params.keys != PIN_KEYS || params.values.any { it.size != 1 || it.single().isBlank() }) return failure(400)
        return selected { response ->
            if (params.toSingleValueMap() != pins(response)) failure(409)
            else serve(response.body(), response.etag, ifNoneMatch,
                CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
        }
    }

    private fun selected(action: (ProvinceNamesRepresentation) -> ResponseEntity<ByteArray>): ResponseEntity<ByteArray> =
        try {
            reader.current()?.let(action) ?: failure(404)
        } catch (_: Exception) {
            failure(503)
        }

    private fun pins(response: ProvinceNamesRepresentation): Map<String, String> = with(response.dto) {
        linkedMapOf("worldId" to worldId.toString(), "mapRelease" to mapRelease,
            "topologyRevision" to topologyRevision, "topologyHash" to topologyHash,
            "sourceSha256" to sourceSha256, "representationSha256" to response.sha256)
    }

    private fun serve(bytes: ByteArray, tag: String, condition: String?, cache: CacheControl): ResponseEntity<ByteArray> {
        val matches = condition?.split(',')?.any { it.trim() == "*" || it.trim().removePrefix("W/") == tag } == true
        return (if (matches) ResponseEntity.status(304) else ResponseEntity.ok()).eTag(tag).cacheControl(cache)
            .contentType(MediaType.APPLICATION_JSON).body(if (matches) null else bytes)
    }

    private fun failure(status: Int): ResponseEntity<ByteArray> =
        ResponseEntity.status(status).cacheControl(CacheControl.noStore()).build()

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    companion object {
        const val PINNED_PATH = "/api/map/provinces/names/v1"
        private val PIN_KEYS = setOf("worldId", "mapRelease", "topologyRevision", "topologyHash", "sourceSha256", "representationSha256")
    }
}
