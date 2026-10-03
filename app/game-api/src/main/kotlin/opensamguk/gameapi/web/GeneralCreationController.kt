package opensamguk.gameapi.web

import opensamguk.gameapi.creation.CreationAdmissionException
import opensamguk.gameapi.creation.CreationResultProjection
import opensamguk.gameapi.creation.GeneralCreationResultService
import opensamguk.gameapi.creation.GeneralCreationService
import opensamguk.gameapi.creation.GeneralCreationCatalog
import opensamguk.gameapi.creation.CreationErrorMessages
import opensamguk.gameapi.dto.GeneralCreationErrorDto
import opensamguk.gameapi.dto.GeneralCreationErrorResponseDto
import opensamguk.gameapi.dto.GeneralCreationRequestDto
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/generals/creation")
class GeneralCreationController(
    private val creation: GeneralCreationService,
    private val results: GeneralCreationResultService,
    private val catalog: GeneralCreationCatalog,
) {
    @PostMapping
    fun create(@AuthenticationPrincipal accountId: Long?,
        @RequestBody request: GeneralCreationRequestDto): ResponseEntity<Any> {
        if (accountId == null) return error(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED")
        return try {
            ResponseEntity.status(HttpStatus.ACCEPTED).body(creation.submit(accountId, request))
        } catch (denied: CreationAdmissionException) {
            val status = when (denied.code) {
                "WORLD_CHANGED", "GENERAL_ALREADY_OWNED", "REQUEST_ID_REUSED", "NAME_ALREADY_USED" -> HttpStatus.CONFLICT
                "INVALID_NAME", "INVALID_NATIVE_COUNTY", "INVALID_STATS", "INVALID_IDEOLOGY", "INVALID_TRAIT",
                "HISTORICAL_PERSON_NOT_APPEARED", "HISTORICAL_PERSON_UNAVAILABLE" -> HttpStatus.UNPROCESSABLE_ENTITY
                "CREATION_POLICY_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE
                else -> HttpStatus.BAD_REQUEST
            }
            error(status, denied.code)
        }
    }

    @GetMapping("/{requestId}")
    fun result(@AuthenticationPrincipal accountId: Long?, @PathVariable requestId: String): ResponseEntity<Any> {
        if (accountId == null) return error(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED")
        return when (val view = results.read(accountId, requestId)) {
            CreationResultProjection.NotFound -> error(HttpStatus.NOT_FOUND, "CREATION_REQUEST_NOT_FOUND")
            is CreationResultProjection.Visible -> ResponseEntity.ok(view.result)
        }
    }

    @GetMapping("/options")
    fun options(@AuthenticationPrincipal accountId: Long?): ResponseEntity<Any> {
        if (accountId == null) return error(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED")
        return try {
            ResponseEntity.ok(catalog.options())
        } catch (denied: CreationAdmissionException) {
            error(HttpStatus.SERVICE_UNAVAILABLE, denied.code)
        }
    }

    @GetMapping("/historical")
    fun historical(
        @AuthenticationPrincipal accountId: Long?,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) nation: Int?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) sort: String?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false, defaultValue = "50") limit: Int,
    ): ResponseEntity<Any> {
        if (accountId == null) return error(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED")
        return try {
            ResponseEntity.ok(catalog.historical(q, nation, status, sort, cursor, limit))
        } catch (denied: CreationAdmissionException) {
            error(if (denied.code == "INVALID_REQUEST") HttpStatus.BAD_REQUEST else HttpStatus.SERVICE_UNAVAILABLE,
                denied.code)
        }
    }

    private fun error(status: HttpStatus, code: String): ResponseEntity<Any> =
        ResponseEntity.status(status).body(GeneralCreationErrorResponseDto(
            GeneralCreationErrorDto(code, CreationErrorMessages.forCode(code))))
}
