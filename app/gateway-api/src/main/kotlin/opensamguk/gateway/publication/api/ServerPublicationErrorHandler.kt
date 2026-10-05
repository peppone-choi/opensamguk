package opensamguk.gateway.publication.api

import jakarta.servlet.http.HttpServletRequest
import opensamguk.gateway.publication.application.ServerPublicationRegistrationMissing
import opensamguk.gateway.publication.domain.ServerPublicationSourceUnavailable
import opensamguk.gateway.publication.domain.ServerPublicationConflict
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice(assignableTypes = [ServerPublicationController::class, AdminServerPublicationController::class])
class ServerPublicationErrorHandler {
    @ExceptionHandler(ServerPublicationSourceUnavailable::class)
    fun unavailable(request: HttpServletRequest): ResponseEntity<Map<String, Any>> {
        val publicList = request.method == "GET" && request.requestURI.removePrefix(request.contextPath) == "/servers"
        val body: Map<String, Any> = if (publicList) mapOf("error" to mapOf("code" to "SERVER_LIST_UNAVAILABLE"))
            else mapOf("code" to "PUBLICATION_SOURCE_UNAVAILABLE")
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore()).body(body)
    }

    @ExceptionHandler(ServerPublicationRegistrationMissing::class)
    fun missing(): ResponseEntity<Map<String, String>> = ResponseEntity.status(HttpStatus.NOT_FOUND)
        .cacheControl(CacheControl.noStore()).body(mapOf("code" to "SERVER_NOT_REGISTERED"))

    @ExceptionHandler(ServerPublicationConflict::class)
    fun conflict(): ResponseEntity<Map<String, String>> = ResponseEntity.status(HttpStatus.CONFLICT)
        .cacheControl(CacheControl.noStore()).body(mapOf("code" to "PUBLICATION_CONFLICT"))

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(): ResponseEntity<Map<String, String>> = ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .cacheControl(CacheControl.noStore()).body(mapOf("code" to "INVALID_PUBLICATION_REQUEST"))
}
