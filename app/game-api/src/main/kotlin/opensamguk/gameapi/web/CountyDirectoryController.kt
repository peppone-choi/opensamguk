package opensamguk.gameapi.web

import opensamguk.gameapi.read.CountyDirectoryReader
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class CountyDirectoryController(private val reader: CountyDirectoryReader) {
    @GetMapping("/api/counties")
    fun counties(@AuthenticationPrincipal userId: Long?, @RequestParam generalId: Int,
                 @RequestParam(defaultValue = "NATION") scope: String,
                 @RequestParam(required = false) commanderyId: String?): ResponseEntity<Any> =
        guardCampaignRead(userId) { reader.counties(generalId, it, scope, commanderyId) }
}
