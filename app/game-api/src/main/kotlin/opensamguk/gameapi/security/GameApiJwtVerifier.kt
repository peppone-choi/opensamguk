package opensamguk.gameapi.security

import opensamguk.common.auth.GatewayAccessTokenVerifier
import opensamguk.common.auth.GatewayJwtContract
import opensamguk.common.auth.GatewayPrincipal
import org.springframework.boot.actuate.info.Info
import org.springframework.boot.actuate.info.InfoContributor
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * F2 Wave 1 — VERIFY-ONLY JWT helper for game-api.
 *
 * game-api is read + precheck + intake; it never ISSUES tokens. It only verifies the gateway-api
 * `sam_access` access token that web/game proxies through as `Authorization: Bearer ...` (auth bridge
 * Option A). Only the gateway public key crosses this service boundary, so compromise of game-api
 * cannot mint tokens accepted by its siblings.
 *
 * The token subject = the gateway `users.id` (a Long), exactly as gateway-api sets it
 * (`Jwts.builder().subject(userId.toString())`). [getUserId] returns that as the verified principal.
 *
 * OPENSAM-220 — only the verified identity and role cross this verifier. Display values are read
 * from the database even while legacy access tokens temporarily retain profile claims for rollout.
 */
@Component
class GameApiJwtVerifier(
    @Value("\${jwt.public-key}") publicKey: String,
    @Value("\${jwt.legacy-secret:}") legacySecret: String,
    @Value("\${jwt.legacy-accept-until:}") legacyAcceptUntil: String,
) : InfoContributor {
    private val verifier = GatewayAccessTokenVerifier(
        publicKey,
        GatewayJwtContract.GAME_API_AUDIENCE,
        legacySecret = legacySecret.takeIf(String::isNotBlank),
        legacyAcceptUntil = legacyAcceptUntil.takeIf(String::isNotBlank)?.let(Instant::parse),
    )

    fun verifyAccessToken(token: String): GatewayPrincipal? = verifier.verifyAccessToken(token)

    fun isValid(token: String): Boolean = verifyAccessToken(token) != null

    /** The verified user id (JWT subject). Null if the token is invalid or the subject is not numeric. */
    fun getUserId(token: String): Long? = verifyAccessToken(token)?.userId

    fun getRole(token: String): String? = verifyAccessToken(token)?.role

    override fun contribute(builder: Info.Builder) {
        builder.withDetail(
            "jwt",
            mapOf(
                "verifier" to "rsa-audience-v1",
                "publicKeySha256" to (verifier.publicKeyFingerprint ?: "unconfigured"),
            ),
        )
    }
}
