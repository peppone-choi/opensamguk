package opensamguk.gateway.controller

import opensamguk.gateway.profile.LocalProfileIconStorage
import opensamguk.gateway.profile.PortraitBundle
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.nio.file.NoSuchFileException

@RestController
class PortraitVariantController(private val storage: LocalProfileIconStorage) {
    @GetMapping("/profile-icons/{picture}/{variant}.jpg")
    fun variant(@PathVariable picture: String, @PathVariable variant: String): ResponseEntity<ByteArray> {
        if (!PortraitBundle.NAME.matches(picture) || variant !in setOf("hero", "card", "icon")) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }
        val bytes = try {
            storage.readBundle(picture) { PortraitBundle.entry(it, "$variant.jpg") }
        } catch (_: NoSuchFileException) { throw ResponseStatusException(HttpStatus.NOT_FOUND) }
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG)
            .header("Cache-Control", "public, max-age=86400, immutable")
            .header("X-Content-Type-Options", "nosniff")
            .body(bytes)
    }
}
