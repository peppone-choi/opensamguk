package opensamguk.boardapi.security

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
import opensamguk.common.auth.GatewayJwtClaims
import opensamguk.common.auth.GatewayPrincipal
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import javax.crypto.SecretKey

@Component
class BoardApiJwtVerifier(
    @Value("\${jwt.secret}") secretKey: String,
) {
    private val key: SecretKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secretKey))

    fun verifyAccessToken(token: String): GatewayPrincipal? =
        try {
            val claims = parseClaims(token)
            if (claims.get(GatewayJwtClaims.TOKEN_TYPE, String::class.java) != GatewayJwtClaims.ACCESS_TOKEN) {
                null
            } else {
                val userId = claims.subject?.toLongOrNull()
                val role = claims.get(GatewayJwtClaims.ROLE, String::class.java)
                    ?.takeIf { it == "USER" || it == "ADMIN" }
                if (userId == null || role == null) null else GatewayPrincipal(userId, role)
            }
        } catch (_: Exception) {
            null
        }

    private fun parseClaims(token: String): Claims =
        Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload
}
