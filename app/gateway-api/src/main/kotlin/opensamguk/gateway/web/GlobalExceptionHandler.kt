package opensamguk.gateway.web

import opensamguk.gateway.security.SelfPeerProtectionException
import opensamguk.gateway.profile.ProfileIconChangedTodayException
import opensamguk.gateway.profile.ProfileIconPayloadTooLargeException
import opensamguk.gateway.profile.ProfileIconPersistenceException
import opensamguk.gateway.profile.ProfileIconStorageException
import opensamguk.gateway.service.NicknameAlreadyInUseException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.core.AuthenticationException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.multipart.MaxUploadSizeExceededException

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

    @ExceptionHandler(opensamguk.gateway.service.NoticeNotFoundException::class)
    fun noticeNotFound(e: opensamguk.gateway.service.NoticeNotFoundException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ApiError(e.message ?: "공지를 찾을 수 없습니다.", HttpStatus.NOT_FOUND.value()))

    @ExceptionHandler(IllegalArgumentException::class)
    fun illegalArg(e: IllegalArgumentException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ApiError(e.message ?: "잘못된 요청입니다.", HttpStatus.BAD_REQUEST.value()))

    /**
     * 아이디·닉네임·이메일 중복은 서비스에서 먼저 걸러지지만, 검사와 INSERT 사이에 다른
     * 요청이 끼어들면 유니크 인덱스가 마지막 문지기가 된다. 그 경우를 500 으로 흘리면
     * 사용자는 이유를 알 수 없다.
     */
    @ExceptionHandler(DataIntegrityViolationException::class)
    fun duplicateKey(e: DataIntegrityViolationException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ApiError("이미 사용 중인 값입니다.", HttpStatus.CONFLICT.value()))

    @ExceptionHandler(NicknameAlreadyInUseException::class)
    fun nicknameAlreadyInUse(e: NicknameAlreadyInUseException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ApiError(e.message ?: "이미 사용 중인 닉네임입니다.", HttpStatus.CONFLICT.value()))

    @ExceptionHandler(ProfileIconChangedTodayException::class)
    fun profileIconChangedToday(e: ProfileIconChangedTodayException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ApiError("프로필 아이콘은 하루에 한 번만 변경할 수 있습니다.", HttpStatus.CONFLICT.value()))

    @ExceptionHandler(ProfileIconPayloadTooLargeException::class, MaxUploadSizeExceededException::class)
    fun profileIconTooLarge(e: Exception): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
            .body(ApiError("프로필 아이콘은 50KB 이하여야 합니다.", HttpStatus.PAYLOAD_TOO_LARGE.value()))

    @ExceptionHandler(ProfileIconStorageException::class, ProfileIconPersistenceException::class)
    fun profileIconInternal(e: Exception): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiError("프로필 아이콘 변경을 완료할 수 없습니다.", HttpStatus.INTERNAL_SERVER_ERROR.value()))

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
