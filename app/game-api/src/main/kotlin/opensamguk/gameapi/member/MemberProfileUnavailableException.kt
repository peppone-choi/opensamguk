package opensamguk.gameapi.member

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ResponseStatus

@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
class MemberProfileUnavailableException(cause: Throwable? = null) :
    RuntimeException("gateway member profile is unavailable", cause)
