package opensamguk.gameapi.county

import opensamguk.gameapi.web.guardCampaignRead
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class CountyDetailController(private val query: CountyDetailQuery) {
    @GetMapping("/api/counties/{cityId}")
    fun county(@AuthenticationPrincipal userId: Long?, @PathVariable cityId: Int,
               @RequestParam generalId: Int): ResponseEntity<Any> =
        guardCampaignRead(userId) { query.county(cityId, generalId, it) }
}
