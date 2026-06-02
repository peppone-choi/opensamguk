package opensamguk.gateway.security

import io.jsonwebtoken.Claims
import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.Date
import javax.crypto.SecretKey

/**
 * JWT 토큰 생성/검증.
 *
 * Access token: 짧은 만료 (기본 15분)
 * Refresh token: 긴 만료 (기본 7일)
 */
@Component
class JwtTokenProvider(
    @Value("\${jwt.secret}")
    secretKey: String,
    @Value("\${jwt.access-expiration:900000}")
    private val accessExpirationMs: Long,
    @Value("\${jwt.refresh-expiration:604800000}")
    private val refreshExpirationMs: Long,
) {
    private val key: SecretKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secretKey))

    fun generateAccessToken(userId: Long, username: String, role: String): String =
        buildToken(userId, username, role, accessExpirationMs)

    fun generateRefreshToken(userId: Long): String =
        buildToken(userId, null, null, refreshExpirationMs)

    private fun buildToken(userId: Long, username: String?, role: String?, expirationMs: Long): String {
        val now = Date()
        val builder = Jwts.builder()
            .subject(userId.toString())
            .issuedAt(now)
            .expiration(Date(now.time + expirationMs))
            .signWith(key)
        if (username != null) builder.claim("username", username)
        if (role != null) builder.claim("role", role)
        return builder.compact()
    }

    fun validateToken(token: String): Boolean =
        try {
            parseClaims(token)
            true
        } catch (e: ExpiredJwtException) {
            false
        } catch (e: Exception) {
            false
        }

    fun getUserIdFromToken(token: String): Long? =
        parseClaims(token)?.subject?.toLongOrNull()

    fun getUsernameFromToken(token: String): String? =
        parseClaims(token)?.get("username", String::class.java)

    fun getRoleFromToken(token: String): String? =
        parseClaims(token)?.get("role", String::class.java)

    fun getExpirationDate(token: String): Date? =
        parseClaims(token)?.expiration

    private fun parseClaims(token: String): Claims? =
        Jwts.parser()
            .verifyWith(key)
            .build()
            .parseSignedClaims(token)
            .payload
}
