package opensamguk.gameapi.security

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
import opensamguk.common.auth.GatewayJwtClaims
import opensamguk.common.auth.GatewayProfileClaims
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import javax.crypto.SecretKey

/**
 * F2 Wave 1 — VERIFY-ONLY JWT helper for game-api.
 *
 * game-api is read + precheck + intake; it never ISSUES tokens. It only verifies the gateway-api
 * `sam_access` access token that web/game proxies through as `Authorization: Bearer ...` (auth bridge
 * Option A). The verification key is the SAME HS256 secret gateway-api signs with — supplied via the
 * shared env `JWT_SECRET` (BASE64-encoded), wired identically to gateway-api's
 * [opensamguk.gateway.security.JwtTokenProvider]. Sharing the secret (not depending on the gateway-api
 * module) keeps game-api decoupled from the auth service while still trusting its signatures.
 *
 * The token subject = the gateway `users.id` (a Long), exactly as gateway-api sets it
 * (`Jwts.builder().subject(userId.toString())`). [getUserId] returns that as the verified principal.
 */
@Component
class GameApiJwtVerifier(
    @Value("\${jwt.secret}")
    secretKey: String,
) {
    private val key: SecretKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secretKey))

    fun verifyAccessToken(token: String): GatewayProfileClaims? {
        return try {
            val claims = parseClaims(token)
            if (claims.get(GatewayJwtClaims.TOKEN_TYPE, String::class.java) != GatewayJwtClaims.ACCESS_TOKEN) {
                return null
            }
            val userId = claims.subject?.toLongOrNull() ?: return null
            val username = claims.get(GatewayJwtClaims.USERNAME, String::class.java) ?: return null
            val role = claims.get(GatewayJwtClaims.ROLE, String::class.java)
                ?.takeIf { it == "USER" || it == "ADMIN" }
                ?: return null
            val grade = (claims[GatewayJwtClaims.GRADE] as? Number)?.toInt()
                ?.takeIf { it in 0..9 }
                ?: return null
            val imageServer = (claims[GatewayJwtClaims.IMAGE_SERVER] as? Number)?.toInt()
                ?.takeIf { it in 0..1 }
                ?: return null
            GatewayProfileClaims(
                userId = userId,
                username = username,
                role = role,
                nickname = claims.get(GatewayJwtClaims.NICKNAME, String::class.java),
                grade = grade,
                picture = claims.get(GatewayJwtClaims.PICTURE, String::class.java),
                imageServer = imageServer,
            )
        } catch (e: Exception) {
            null
        }
    }

    fun isValid(token: String): Boolean = verifyAccessToken(token) != null

    /** The verified user id (JWT subject). Null if the token is invalid or the subject is not numeric. */
    fun getUserId(token: String): Long? = verifyAccessToken(token)?.userId

    fun getUsername(token: String): String? = verifyAccessToken(token)?.username

    fun getRole(token: String): String? = verifyAccessToken(token)?.role

    private fun parseClaims(token: String): Claims =
        Jwts.parser()
            .verifyWith(key)
            .build()
            .parseSignedClaims(token)
            .payload
}
