package opensamguk.gameapi.web

import opensamguk.gameapi.read.ImperialPresenceReader
import opensamguk.gameapi.read.ImperialPresenceResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class ImperialPresenceController(private val reader: ImperialPresenceReader) {
    @GetMapping("/api/imperial/presence")
    fun presence(): ResponseEntity<ImperialPresenceResponse> {
        val response = reader.read()
        val status = if (response.status == "STATE_UNAVAILABLE") HttpStatus.CONFLICT else HttpStatus.OK
        return ResponseEntity.status(status).body(response)
    }
}
