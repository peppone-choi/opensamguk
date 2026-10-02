package opensamguk.gateway.service

enum class AuthPolicyFailureCode(val message: String) {
    JOIN_DISABLED("현재는 가입이 금지되어있습니다!"),
    LOGIN_DISABLED("현재는 로그인이 금지되어있습니다!"),
}

class AuthPolicyDeniedException(val code: AuthPolicyFailureCode) : IllegalArgumentException(code.message)

class AuthPolicyUnavailableException(cause: RuntimeException) :
    RuntimeException("가입·로그인 허용 상태를 확인할 수 없습니다.", cause)
