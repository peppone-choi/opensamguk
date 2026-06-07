package opensamguk.gameapi.security

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
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

    /** True iff [token] is a well-formed, unexpired, correctly-signed access token. */
    fun isValid(token: String): Boolean =
        try {
            parseClaims(token)
            true
        } catch (e: Exception) {
            false
        }

    /** The verified user id (JWT subject). Null if the token is invalid or the subject is not numeric. */
    fun getUserId(token: String): Long? =
        try {
            parseClaims(token).subject?.toLongOrNull()
        } catch (e: Exception) {
            null
        }

    /** The `username` claim, if present (gateway-api sets it on the access token). */
    fun getUsername(token: String): String? =
        try {
            parseClaims(token).get("username", String::class.java)
        } catch (e: Exception) {
            null
        }

    /**
     * `role` 클레임(gateway-api가 access token에 함께 실음: `generateAccessToken(userId, username, role)`).
     * 토큰이 무효/role 부재면 null. 어드민 read 게이트(0.9.0 단일 ADMIN 롤)가 `"ADMIN"`인지 확인하는 데 쓴다.
     * 가산 헬퍼 — 기존 getUserId/getUsername와 동형. (gateway 토큰은 role 클레임을 항상 싣지만 refresh
     * 토큰엔 없으므로 안전하게 nullable.)
     */
    fun getRole(token: String): String? =
        try {
            parseClaims(token).get("role", String::class.java)
        } catch (e: Exception) {
            null
        }

    private fun parseClaims(token: String): Claims =
        Jwts.parser()
            .verifyWith(key)
            .build()
            .parseSignedClaims(token)
            .payload
}
