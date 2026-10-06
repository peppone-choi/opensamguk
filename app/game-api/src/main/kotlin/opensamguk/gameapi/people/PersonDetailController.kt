package opensamguk.gameapi.people

import opensamguk.gameapi.web.guardCampaignRead
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class PersonDetailController(private val query: PersonDetailQuery) {
    @GetMapping("/api/people/{targetGeneralId}")
    fun person(@AuthenticationPrincipal userId: Long?, @PathVariable targetGeneralId: Int,
               @RequestParam generalId: Int): ResponseEntity<Any> =
        guardCampaignRead(userId) { query.person(targetGeneralId, generalId, it) }
}
