package opensamguk.gameapi.web

import opensamguk.gameapi.read.CampaignDirectoryReader
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class CampaignDirectoryController(private val reader: CampaignDirectoryReader) {
    @GetMapping("/api/people")
    fun people(@AuthenticationPrincipal userId: Long?, @RequestParam(defaultValue = "ALL") scope: String,
               @RequestParam(defaultValue = "") q: String, @RequestParam(defaultValue = "ID") sort: String,
               @RequestParam(required = false) cursor: String?, @RequestParam(defaultValue = "50") limit: Int,
               @RequestParam(defaultValue = "ASC") direction: String): ResponseEntity<Any> =
        guardCampaignRead(userId) { reader.people(it, scope, q, sort, cursor, limit, direction) }

    @GetMapping("/api/nation/summary")
    fun nationSummary(@AuthenticationPrincipal userId: Long?, @RequestParam generalId: Int): ResponseEntity<Any> =
        guardCampaignRead(userId) { reader.nationSummary(generalId, it) }
}
