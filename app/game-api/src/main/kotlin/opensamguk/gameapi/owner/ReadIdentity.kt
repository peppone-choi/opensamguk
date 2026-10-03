package opensamguk.gameapi.owner

import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

/** Public views may omit identity; a supplied general ID only confirms the verified caller's body. */
fun resolveReadIdentity(
    resolver: GeneralResolver,
    userId: Long?,
    requestedGeneralId: Int?,
): GeneralResolver.ResolvedGeneral? {
    if (userId == null) {
        if (requestedGeneralId != null) throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        return null
    }
    if (userId <= 0) throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
    val resolved = resolver.resolve(userId)
    if (requestedGeneralId != null && requestedGeneralId != resolved?.general?.id) {
        throw ResponseStatusException(HttpStatus.FORBIDDEN)
    }
    return resolved
}
