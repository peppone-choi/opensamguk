package opensamguk.common.auth

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.RSAKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Clock
import java.time.Instant
import java.util.Date

object GatewayJwtContract {
    const val ISSUER = "opensamguk-gateway"
    const val GATEWAY_API_AUDIENCE = "gateway-api"
    const val GAME_API_AUDIENCE = "game-api"
    const val BOARD_API_AUDIENCE = "board-api"

    val ACCESS_AUDIENCES: Set<String> = linkedSetOf(
        GATEWAY_API_AUDIENCE,
        GAME_API_AUDIENCE,
        BOARD_API_AUDIENCE,
    )
}

object GatewayJwtKeys {
    fun rsaPrivateKey(base64Pkcs8: String): PrivateKey =
        KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(decode(base64Pkcs8, "private")))
            .also(::requireStrongRsaKey)

    fun rsaPublicKey(base64X509: String): PublicKey =
        KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(decode(base64X509, "public")))
            .also(::requireStrongRsaKey)

    fun rsaPublicKeyFingerprint(base64X509: String): String =
        rsaPublicKeyFingerprint(rsaPublicKey(base64X509))

    fun rsaPublicKeyFingerprint(publicKey: PublicKey): String =
        MessageDigest.getInstance("SHA-256")
            .digest(publicKey.encoded)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    fun requireMatchingKeyPair(privateKey: PrivateKey, publicKey: PublicKey) {
        val probe = "opensamguk-jwt-key-pair".toByteArray()
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey)
            update(probe)
            sign()
        }
        require(Signature.getInstance("SHA256withRSA").run {
            initVerify(publicKey)
            update(probe)
            verify(signature)
        }) { "JWT RSA private and public keys do not match" }
    }

    private fun decode(value: String, label: String): ByteArray {
        require(value.isNotBlank()) { "JWT RSA $label key is required" }
        return try {
            Decoders.BASE64.decode(value.trim())
        } catch (e: Exception) {
            throw IllegalArgumentException("JWT RSA $label key must be Base64 DER", e)
        }
    }

    private fun requireStrongRsaKey(key: java.security.Key) {
        require(key is RSAKey && key.modulus.bitLength() >= 2048) {
            "JWT RSA keys must be at least 2048 bits"
        }
    }
}

/**
 * Verifies gateway access tokens without possessing the gateway signing key.
 *
 * The optional HS256 path exists only for the bounded consumer-first rollout. Both legacy values must
 * be supplied together, the wall clock must remain before the absolute cutoff, and the token itself
 * may not outlive that cutoff. Removing the values leaves a public-key-only verifier.
 */
class GatewayAccessTokenVerifier(
    publicKeyBase64: String?,
    private val audience: String,
    legacySecret: String? = null,
    private val legacyAcceptUntil: Instant? = null,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val publicKey = publicKeyBase64
        ?.takeIf(String::isNotBlank)
        ?.let(GatewayJwtKeys::rsaPublicKey)
    val publicKeyFingerprint: String? = publicKey?.let(GatewayJwtKeys::rsaPublicKeyFingerprint)
    private val legacyKey = legacySecret
        ?.takeIf { it.isNotBlank() }
        ?.let { Keys.hmacShaKeyFor(Decoders.BASE64.decode(it)) }

    init {
        require(audience.isNotBlank()) { "JWT audience is required" }
        require((legacyKey == null) == (legacyAcceptUntil == null)) {
            "JWT legacy secret and cutoff must be configured together"
        }
        require(publicKey != null || legacyKey != null) { "JWT public key is required" }
    }

    fun verifyAccessToken(token: String): GatewayPrincipal? {
        val modernClaims = try {
            modernParser().parseSignedClaims(token).payload
        } catch (_: Exception) {
            null
        }
        if (modernClaims != null) return modernClaims.toPrincipal()

        return verifyLegacy(token)
    }

    private fun verifyLegacy(token: String): GatewayPrincipal? {
        val key = legacyKey ?: return null
        val cutoff = legacyAcceptUntil ?: return null
        if (!clock.instant().isBefore(cutoff)) return null

        val claims = try {
            Jwts.parser()
                .clock { Date.from(clock.instant()) }
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .payload
        } catch (_: Exception) {
            return null
        }
        if (claims.issuer != null || !claims.audience.isNullOrEmpty()) return null
        if (claims.expiration?.toInstant()?.isAfter(cutoff) != false) return null
        return claims.toPrincipal()
    }

    private fun modernParser() = Jwts.parser()
        .clock { Date.from(clock.instant()) }
        .verifyWith(requireNotNull(publicKey))
        .requireIssuer(GatewayJwtContract.ISSUER)
        .requireAudience(audience)
        .build()

    private fun Claims.toPrincipal(): GatewayPrincipal? {
        if (get(GatewayJwtClaims.TOKEN_TYPE, String::class.java) != GatewayJwtClaims.ACCESS_TOKEN) return null
        if (expiration == null) return null
        val userId = subject?.toLongOrNull()?.takeIf { it > 0 } ?: return null
        val role = get(GatewayJwtClaims.ROLE, String::class.java)
            ?.takeIf { it == "USER" || it == "ADMIN" }
            ?: return null
        return GatewayPrincipal(userId, role)
    }
}
