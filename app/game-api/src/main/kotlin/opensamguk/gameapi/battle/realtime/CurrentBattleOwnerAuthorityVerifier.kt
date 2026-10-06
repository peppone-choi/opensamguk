package opensamguk.gameapi.battle.realtime

import jakarta.servlet.http.HttpServletRequest
import opensamguk.gameapi.security.JwtVerifyFilter
import opensamguk.logic.battle.realtime.BattleOwnerAuthorityBinding
import opensamguk.logic.battle.realtime.BattleOwnerAuthorityCurrentSource
import org.springframework.security.core.context.SecurityContextHolder

/** Read verification only. The ticket writer must still serialize its INSERT with the authority writer. */
class CurrentBattleOwnerAuthorityVerifier(
    private val fixedSource: BattleOwnerAuthorityCurrentSource? = null,
) {
    fun verify(request: HttpServletRequest, expected: BattleOwnerAuthorityBinding): BattleOwnerAuthorityBinding {
        val source = fixedSource ?: denied()
        val account = actualAccount(request)
        if (account != expected.accountId) denied()
        return try {
            val first = source.read(expected) ?: denied()
            if (!expected.matches(first) || actualAccount(request) != account) denied()
            val current = source.read(expected) ?: denied()
            if (!expected.matches(current) || !first.matches(current) || actualAccount(request) != account) denied()
            current
        } catch (_: Exception) {
            denied()
        }
    }

    private fun actualAccount(request: HttpServletRequest): Int {
        val principal = JwtVerifyFilter.principal(request) ?: denied()
        val authentication = SecurityContextHolder.getContext().authentication ?: denied()
        if (!authentication.isAuthenticated || authentication.principal != principal.userId ||
            principal.userId !in 1L..Int.MAX_VALUE.toLong() ||
            authentication.authorities.none { it.authority == "ROLE_${principal.role}" }) denied()
        return principal.userId.toInt()
    }

    private fun denied(): Nothing = throw SecurityException("battle owner authority unavailable")
}
