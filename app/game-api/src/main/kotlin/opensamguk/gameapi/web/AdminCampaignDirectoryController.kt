package opensamguk.gameapi.web

import opensamguk.gameapi.read.CampaignDirectoryReader
import opensamguk.gameapi.security.GameApiJwtVerifier
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class AdminCampaignDirectoryController(
    private val reader: CampaignDirectoryReader,
    private val verifier: GameApiJwtVerifier,
) {
    @GetMapping("/api/admin/nations")
    fun nations(@RequestHeader(value = "Authorization", required = false) authorization: String?): ResponseEntity<Any> =
        guarded(authorization) { reader.adminNations() }

    @GetMapping("/api/admin/people")
    fun people(@RequestHeader(value = "Authorization", required = false) authorization: String?,
               @RequestParam(defaultValue = "") q: String, @RequestParam(defaultValue = "ID") sort: String,
               @RequestParam(required = false) cursor: String?, @RequestParam(defaultValue = "50") limit: Int): ResponseEntity<Any> =
        guarded(authorization) { reader.adminPeople(q, sort, cursor, limit) }

    private fun guarded(authorization: String?, read: () -> Any): ResponseEntity<Any> {
        val token = authorization?.takeIf { it.startsWith("Bearer ") }?.substring(7)?.takeIf { it.isNotBlank() }
        if (token == null || !verifier.isValid(token))
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).cacheControl(CacheControl.noStore()).build()
        if (verifier.getRole(token) != "ADMIN")
            return ResponseEntity.status(HttpStatus.FORBIDDEN).cacheControl(CacheControl.noStore()).build()
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(read())
    }
}
