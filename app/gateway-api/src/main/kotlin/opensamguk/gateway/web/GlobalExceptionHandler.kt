package opensamguk.gateway.web

import opensamguk.gateway.security.SelfPeerProtectionException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.core.AuthenticationException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** 클라이언트에 노출하는 에러 본문 — Next.js route handler가 `message`를 그대로 surface. */
data class ApiError(val message: String, val status: Int)

/**
 * 인증/검증 예외를 사용자용 한글 메시지 + 적절한 상태코드로 변환.
 *
 * 기본 Spring 동작(메시지 없는 500/403)으로는 프론트가 로그인/회원가입 실패 사유를
 * 표시할 수 없어 에러 패러티가 깨진다. 서비스 계층에서 던지는 예외만 다룬다
 * (보안 필터 단계 미인증은 별개로 anyRequest().authenticated()가 처리).
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(BadCredentialsException::class)
    fun badCredentials(e: BadCredentialsException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(ApiError("아이디나 비밀번호가 올바르지 않습니다.", HttpStatus.UNAUTHORIZED.value()))

    @ExceptionHandler(AuthenticationException::class)
    fun authFailed(e: AuthenticationException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(ApiError("인증에 실패했습니다.", HttpStatus.UNAUTHORIZED.value()))

    @ExceptionHandler(IllegalArgumentException::class)
    fun illegalArg(e: IllegalArgumentException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ApiError(e.message ?: "잘못된 요청입니다.", HttpStatus.BAD_REQUEST.value()))

    /**
     * 회원관리 self/peer 보호 위반(B2d) — 어드민이 자기 자신/다른 ADMIN을 변경하려 함.
     * legacy `j_set_userlist:162,274`의 거부에 대응 → 403 + 한글 사유.
     */
    @ExceptionHandler(SelfPeerProtectionException::class)
    fun selfPeer(e: SelfPeerProtectionException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(ApiError(e.message ?: "변경할 수 없습니다.", HttpStatus.FORBIDDEN.value()))

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun validation(e: MethodArgumentNotValidException): ResponseEntity<ApiError> {
        val msg = e.bindingResult.fieldErrors.firstOrNull()
            ?.let { "${it.field}: ${it.defaultMessage}" }
            ?: "입력값이 올바르지 않습니다."
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiError(msg, HttpStatus.BAD_REQUEST.value()))
    }
}
