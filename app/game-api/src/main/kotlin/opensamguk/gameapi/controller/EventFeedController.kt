package opensamguk.gameapi.controller

import opensamguk.gameapi.dto.GameEventPage
import opensamguk.gameapi.read.EventFeedReader
import opensamguk.logic.record.EventSection
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
class EventFeedController(private val reader: EventFeedReader) {
    @GetMapping("/api/events")
    fun privateFeed(@AuthenticationPrincipal userId: Long?, @RequestParam section: String,
                    @RequestParam(required = false) before: String?,
                    @RequestParam(required = false) cityId: String?,
                    @RequestParam(defaultValue = "30") limit: Int): GameEventPage {
        if (userId == null) throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val category = EventSection.entries.find { it.name == section && it != EventSection.WORLD }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid event section")
        val parsedCityId = cityId?.toIntOrNull()?.takeIf { it > 0 }
        if (cityId != null && parsedCityId == null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid cityId")
        }
        return reader.privateFeed(userId, category, before, limit, parsedCityId)
    }

    @GetMapping("/api/world-events")
    fun publicFeed(@RequestParam(required = false) before: String?,
                   @RequestParam(defaultValue = "30") limit: Int): GameEventPage = reader.publicFeed(before, limit)
}
