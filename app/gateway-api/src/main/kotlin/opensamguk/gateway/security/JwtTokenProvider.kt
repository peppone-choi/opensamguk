package opensamguk.gateway.security

import io.jsonwebtoken.Claims
import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
import opensamguk.common.auth.GatewayJwtClaims
import opensamguk.common.auth.GatewayProfileClaims
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

    fun generateAccessToken(profile: GatewayProfileClaims): String {
        val now = Date()
        val builder = Jwts.builder()
            .subject(profile.userId.toString())
            .issuedAt(now)
            .expiration(Date(now.time + accessExpirationMs))
            .claim(GatewayJwtClaims.TOKEN_TYPE, GatewayJwtClaims.ACCESS_TOKEN)
            .claim(GatewayJwtClaims.USERNAME, profile.username)
            .claim(GatewayJwtClaims.ROLE, profile.role)
            .claim(GatewayJwtClaims.GRADE, profile.grade)
            .claim(GatewayJwtClaims.IMAGE_SERVER, profile.imageServer)
            .signWith(key)
        profile.nickname?.let { builder.claim(GatewayJwtClaims.NICKNAME, it) }
        profile.picture?.let { builder.claim(GatewayJwtClaims.PICTURE, it) }
        return builder.compact()
    }

    fun generateRefreshToken(userId: Long): String =
        buildToken(userId, GatewayJwtClaims.REFRESH_TOKEN, refreshExpirationMs)

    private fun buildToken(userId: Long, tokenType: String, expirationMs: Long): String {
        val now = Date()
        return Jwts.builder()
            .subject(userId.toString())
            .issuedAt(now)
            .expiration(Date(now.time + expirationMs))
            .claim(GatewayJwtClaims.TOKEN_TYPE, tokenType)
            .signWith(key)
            .compact()
    }

    fun validateAccessToken(token: String): Boolean =
        validateTokenType(token, GatewayJwtClaims.ACCESS_TOKEN)

    fun validateRefreshToken(token: String): Boolean =
        validateTokenType(token, GatewayJwtClaims.REFRESH_TOKEN)

    private fun validateTokenType(token: String, expectedType: String): Boolean =
        try {
            parseClaims(token)?.get(GatewayJwtClaims.TOKEN_TYPE, String::class.java) == expectedType
        } catch (e: ExpiredJwtException) {
            false
        } catch (e: Exception) {
            false
        }

    fun getUserIdFromToken(token: String): Long? =
        parseClaims(token)?.subject?.toLongOrNull()

    fun getUsernameFromToken(token: String): String? =
        parseClaims(token)?.get(GatewayJwtClaims.USERNAME, String::class.java)

    fun getRoleFromToken(token: String): String? =
        parseClaims(token)?.get(GatewayJwtClaims.ROLE, String::class.java)

    fun getProfileFromAccessToken(token: String): GatewayProfileClaims? {
        if (!validateAccessToken(token)) return null
        val claims = parseClaims(token) ?: return null
        val userId = claims.subject?.toLongOrNull() ?: return null
        val username = claims.get(GatewayJwtClaims.USERNAME, String::class.java) ?: return null
        val role = claims.get(GatewayJwtClaims.ROLE, String::class.java) ?: return null
        val grade = (claims[GatewayJwtClaims.GRADE] as? Number)?.toInt() ?: return null
        val imageServer = (claims[GatewayJwtClaims.IMAGE_SERVER] as? Number)?.toInt() ?: return null
        return GatewayProfileClaims(
            userId = userId,
            username = username,
            role = role,
            nickname = claims.get(GatewayJwtClaims.NICKNAME, String::class.java),
            grade = grade,
            picture = claims.get(GatewayJwtClaims.PICTURE, String::class.java),
            imageServer = imageServer,
        )
    }

    fun getExpirationDate(token: String): Date? =
        parseClaims(token)?.expiration

    private fun parseClaims(token: String): Claims? =
        Jwts.parser()
            .verifyWith(key)
            .build()
            .parseSignedClaims(token)
            .payload
}
