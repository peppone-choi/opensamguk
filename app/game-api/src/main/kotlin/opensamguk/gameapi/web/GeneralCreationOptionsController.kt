package opensamguk.gameapi.web

import opensamguk.gameapi.creation.CreationOptionsUnavailable
import opensamguk.gameapi.creation.GeneralCreationOptionsService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class GeneralCreationOptionsController(private val optionsService: GeneralCreationOptionsService) {
    @GetMapping("/api/generals/creation/options")
    fun options(@AuthenticationPrincipal accountId: Long?): ResponseEntity<Any> {
        if (accountId == null) return error(HttpStatus.FORBIDDEN, "AUTH_REQUIRED", "로그인이 필요합니다.")
        return try {
            ResponseEntity.ok(optionsService.options())
        } catch (_: CreationOptionsUnavailable) {
            error(HttpStatus.SERVICE_UNAVAILABLE, "CREATION_POLICY_UNAVAILABLE",
                "장수 만들기가 아직 열리지 않았습니다. 잠시 후 다시 확인해 주세요.")
        }
    }

    private fun error(status: HttpStatus, code: String, message: String): ResponseEntity<Any> =
        ResponseEntity.status(status).body(mapOf("error" to mapOf("code" to code, "message" to message)))
}
