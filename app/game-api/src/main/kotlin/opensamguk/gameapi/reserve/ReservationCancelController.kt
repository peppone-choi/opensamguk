package opensamguk.gameapi.reserve

import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

@RestController
class ReservationCancelController(private val service: ReservationCancelService) {
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun invalidQuery(): ResponseEntity<Any> = ResponseEntity.badRequest().body(mapOf(
        "status" to "BLOCKED", "code" to "INVALID_ARGUMENT", "accepted" to false,
        "receiptRecorded" to false, "retryable" to false))

    @DeleteMapping("/api/reserved-commands")
    fun cancel(
        @AuthenticationPrincipal userId: Long?,
        @RequestParam(required = false) generalId: Int?,
        @RequestParam(required = false) turnIdx: Int?,
        @RequestParam(required = false) revision: String?,
        @RequestHeader(name = "Idempotency-Key", required = false) key: String?,
    ): ResponseEntity<Any> = try {
        ResponseEntity.ok(service.cancel(userId, generalId, turnIdx, revision, key))
    } catch (denied: ReservationCancelRejected) {
        ResponseEntity.status(denied.httpStatus).body(mapOf("status" to "BLOCKED", "code" to denied.code,
            "accepted" to false, "receiptRecorded" to false, "retryable" to denied.retryable))
    }
}
