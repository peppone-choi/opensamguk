package opensamguk.gameapi.web

import opensamguk.gameapi.read.CountyDetailReader
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class CountyDetailController(private val reader: CountyDetailReader) {
    @GetMapping("/api/counties/{cityId}")
    fun county(@AuthenticationPrincipal userId: Long?, @PathVariable cityId: Int,
               @RequestParam generalId: Int): ResponseEntity<Any> =
        guardCampaignRead(userId) { reader.county(cityId, generalId, it) }
}
