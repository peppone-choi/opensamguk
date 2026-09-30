package opensamguk.gameapi.controller

import opensamguk.gameapi.read.TopdownMapArtifacts
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.time.Duration

@RestController
class TopdownMapController(private val artifacts: TopdownMapArtifacts) {
    @GetMapping("/api/map/topdown/{bakeId}/{*path}")
    fun asset(
        @PathVariable("bakeId") bakeId: String,
        @PathVariable("path") path: String,
        @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) ifNoneMatch: String?,
    ): ResponseEntity<ByteArray> {
        val asset = artifacts.asset(bakeId, path) ?: return ResponseEntity.notFound().build()
        val tag = "\"sha256-${TopdownMapArtifacts.sha256(asset.bytes)}\""
        val response = if (tag == ifNoneMatch) ResponseEntity.status(304) else ResponseEntity.ok()
        return response.eTag(tag)
            .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
            .contentType(asset.contentType)
            // .gz is a binary file the client decompresses explicitly, not HTTP content encoding.
            .body(if (tag == ifNoneMatch) null else asset.bytes)
    }
}
