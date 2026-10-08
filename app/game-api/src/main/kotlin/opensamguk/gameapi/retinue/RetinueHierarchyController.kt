package opensamguk.gameapi.retinue

import opensamguk.gameapi.web.guardCampaignRead
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class RetinueHierarchyController(private val query: RetinueHierarchyQuery) {
    @GetMapping("/api/retinue/hierarchy")
    fun hierarchy(@AuthenticationPrincipal userId: Long?, @RequestParam generalId: Int): ResponseEntity<Any> =
        guardCampaignRead(userId) { query.hierarchy(generalId, it) }
}
