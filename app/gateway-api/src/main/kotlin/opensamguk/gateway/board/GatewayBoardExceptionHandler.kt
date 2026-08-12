package opensamguk.gateway.board

import opensamguk.gateway.web.ApiError
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

@RestControllerAdvice(assignableTypes = [GatewayBoardController::class])
class GatewayBoardExceptionHandler {
    @ExceptionHandler(GatewayBoardNotFoundException::class)
    fun notFound(e: GatewayBoardNotFoundException): ResponseEntity<ApiError> = error(HttpStatus.NOT_FOUND, e.message)

    @ExceptionHandler(GatewayBoardForbiddenException::class)
    fun forbidden(e: GatewayBoardForbiddenException): ResponseEntity<ApiError> = error(HttpStatus.FORBIDDEN, e.message)

    @ExceptionHandler(GatewayBoardConflictException::class)
    fun conflict(e: GatewayBoardConflictException): ResponseEntity<ApiError> = error(HttpStatus.CONFLICT, e.message)

    @ExceptionHandler(MethodArgumentTypeMismatchException::class, HttpMessageNotReadableException::class)
    fun malformedRequest(e: Exception): ResponseEntity<ApiError> =
        error(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다.")

    private fun error(status: HttpStatus, message: String?): ResponseEntity<ApiError> =
        ResponseEntity.status(status).body(ApiError(message ?: "요청을 처리할 수 없습니다.", status.value()))
}
