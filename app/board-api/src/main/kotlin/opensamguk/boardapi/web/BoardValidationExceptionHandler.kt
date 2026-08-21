package opensamguk.boardapi.web

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class BoardValidationExceptionHandler {
    @ExceptionHandler(IllegalArgumentException::class)
    fun illegalArgument(e: IllegalArgumentException): ResponseEntity<ApiError> =
        ResponseEntity.badRequest().body(ApiError(e.message ?: "잘못된 요청입니다.", 400))

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun validation(e: MethodArgumentNotValidException): ResponseEntity<ApiError> {
        val message = e.bindingResult.fieldErrors.firstOrNull()
            ?.let { "${it.field}: ${it.defaultMessage}" }
            ?: "입력값이 올바르지 않습니다."
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiError(message, 400))
    }
}
