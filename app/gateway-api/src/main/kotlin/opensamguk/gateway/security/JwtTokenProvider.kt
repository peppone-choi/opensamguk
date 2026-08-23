package opensamguk.gateway.security

import io.jsonwebtoken.Claims
import io.jsonwebtoken.JwtBuilder
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
import opensamguk.common.auth.GatewayJwtClaims
import opensamguk.common.auth.GatewayJwtContract
import opensamguk.common.auth.GatewayJwtKeys
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.actuate.info.Info
import org.springframework.boot.actuate.info.InfoContributor
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.security.PrivateKey
import java.security.PublicKey
import java.time.Clock
import java.time.Instant
import java.util.Date
import javax.crypto.SecretKey

enum class GatewayJwtSigningMode {
    LEGACY_HS256,
    RS256,
}

@Component
@ConfigurationProperties(prefix = "jwt")
class GatewayJwtProperties {
    var privateKey: String = ""
    var publicKey: String = ""
    var legacySecret: String = ""
    var legacyAccessAcceptUntil: String = ""
    var legacyRefreshAcceptUntil: String = ""
    var signingMode: GatewayJwtSigningMode = GatewayJwtSigningMode.LEGACY_HS256
    var accessExpiration: Long = 900_000
    var refreshExpiration: Long = 604_800_000
}

@Component
class JwtTokenProvider @Autowired constructor(
    private val properties: GatewayJwtProperties,
) : InfoContributor {
    private val privateKey: PrivateKey? = properties.privateKey
        .takeIf(String::isNotBlank)
        ?.let(GatewayJwtKeys::rsaPrivateKey)
    private val publicKey: PublicKey? = properties.publicKey
        .takeIf(String::isNotBlank)
        ?.let(GatewayJwtKeys::rsaPublicKey)
    // OPENSAM-220 팔로업: game-api/board-api InfoContributor와 같은 키/포맷으로
    // 발급자·검증자 공개키 지문을 3자 대조할 수 있게 한다. 지문만 노출 — 개인키/공개키 원문 금지.
    private val publicKeyFingerprint: String? = publicKey?.let(GatewayJwtKeys::rsaPublicKeyFingerprint)
    private val legacyKey: SecretKey? = properties.legacySecret
        .takeIf(String::isNotBlank)
        ?.let { Keys.hmacShaKeyFor(Decoders.BASE64.decode(it)) }
    private val legacyAccessAcceptUntil = properties.legacyAccessAcceptUntil.toInstantOrNull()
    private val legacyRefreshAcceptUntil = properties.legacyRefreshAcceptUntil.toInstantOrNull()
    private var clock: Clock = Clock.systemUTC()

    internal constructor(properties: GatewayJwtProperties, clock: Clock) : this(properties) {
        this.clock = clock
    }

    init {
        require(properties.accessExpiration > 0 && properties.refreshExpiration > 0) {
            "JWT expirations must be positive"
        }
        when (properties.signingMode) {
            GatewayJwtSigningMode.LEGACY_HS256 -> {
                require(legacyKey != null) { "JWT legacy signing requires a legacy secret" }
                require(legacyAccessAcceptUntil != null && legacyRefreshAcceptUntil != null) {
                    "JWT legacy signing requires absolute access and refresh cutoffs"
                }
            }
            GatewayJwtSigningMode.RS256 -> {
                require(privateKey != null && publicKey != null) { "JWT RS256 signing requires an RSA key pair" }
                GatewayJwtKeys.requireMatchingKeyPair(privateKey, publicKey)
            }
        }
        require(legacyKey != null || (legacyAccessAcceptUntil == null && legacyRefreshAcceptUntil == null)) {
            "JWT legacy cutoffs require a legacy secret"
        }
        require(legacyKey == null || (legacyAccessAcceptUntil != null && legacyRefreshAcceptUntil != null)) {
            "JWT legacy verification requires absolute access and refresh cutoffs"
        }
    }

    /** OPENSAM-220/#483: 액세스 토큰은 신원(sub=userId)과 인가(role)만 담는다 — 표시 클레임은 발급하지 않는다. */
    fun generateAccessToken(userId: Long, role: String): String {
        val now = clock.instant()
        val builder = tokenBuilder(userId, GatewayJwtClaims.ACCESS_TOKEN, properties.accessExpiration, now)
            .claim(GatewayJwtClaims.ROLE, role)
        return sign(builder)
    }

    fun generateRefreshToken(userId: Long): String =
        sign(tokenBuilder(userId, GatewayJwtClaims.REFRESH_TOKEN, properties.refreshExpiration, clock.instant()))

    fun validateAccessToken(token: String): Boolean = parseAccessClaims(token) != null

    fun validateRefreshToken(token: String): Boolean = parseRefreshClaims(token) != null

    fun getUserIdFromToken(token: String): Long? =
        (parseAccessClaims(token) ?: parseRefreshClaims(token))?.subject?.toLongOrNull()

    fun getExpirationDate(token: String): Date? =
        (parseAccessClaims(token) ?: parseRefreshClaims(token))?.expiration

    private fun tokenBuilder(userId: Long, tokenType: String, expirationMs: Long, now: Instant): JwtBuilder {
        val expiration = now.plusMillis(expirationMs)
        requireLegacyExpirationWithinCutoff(tokenType, expiration)
        val builder = Jwts.builder()
            .subject(userId.toString())
            .issuedAt(Date.from(now))
            .expiration(Date.from(expiration))
            .claim(GatewayJwtClaims.TOKEN_TYPE, tokenType)
        if (properties.signingMode == GatewayJwtSigningMode.RS256) {
            builder.issuer(GatewayJwtContract.ISSUER)
            val audiences = if (tokenType == GatewayJwtClaims.ACCESS_TOKEN) {
                GatewayJwtContract.ACCESS_AUDIENCES
            } else {
                setOf(GatewayJwtContract.GATEWAY_API_AUDIENCE)
            }
            audiences.forEach { builder.audience().add(it).and() }
        }
        return builder
    }

    private fun sign(builder: JwtBuilder): String = when (properties.signingMode) {
        GatewayJwtSigningMode.LEGACY_HS256 -> builder.signWith(requireNotNull(legacyKey), Jwts.SIG.HS256).compact()
        GatewayJwtSigningMode.RS256 -> builder.signWith(requireNotNull(privateKey), Jwts.SIG.RS256).compact()
    }

    private fun parseAccessClaims(token: String): Claims? =
        parseModern(token, GatewayJwtContract.GATEWAY_API_AUDIENCE, GatewayJwtClaims.ACCESS_TOKEN)
            ?: parseLegacy(token, GatewayJwtClaims.ACCESS_TOKEN, legacyAccessAcceptUntil)

    private fun parseRefreshClaims(token: String): Claims? =
        parseModern(token, GatewayJwtContract.GATEWAY_API_AUDIENCE, GatewayJwtClaims.REFRESH_TOKEN)
            ?: parseLegacy(token, GatewayJwtClaims.REFRESH_TOKEN, legacyRefreshAcceptUntil)

    private fun parseModern(token: String, audience: String, tokenType: String): Claims? {
        val verificationKey = publicKey ?: return null
        return try {
            Jwts.parser()
                .clock { Date.from(clock.instant()) }
                .verifyWith(verificationKey)
                .requireIssuer(GatewayJwtContract.ISSUER)
                .requireAudience(audience)
                .require(GatewayJwtClaims.TOKEN_TYPE, tokenType)
                .build()
                .parseSignedClaims(token)
                .payload
                .takeIf { it.expiration != null && it.subject?.toLongOrNull()?.let { id -> id > 0 } == true }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseLegacy(token: String, tokenType: String, cutoff: Instant?): Claims? {
        val verificationKey = legacyKey ?: return null
        val acceptUntil = cutoff ?: return null
        if (!clock.instant().isBefore(acceptUntil)) return null
        return try {
            Jwts.parser()
                .clock { Date.from(clock.instant()) }
                .verifyWith(verificationKey)
                .require(GatewayJwtClaims.TOKEN_TYPE, tokenType)
                .build()
                .parseSignedClaims(token)
                .payload
                .takeIf { it.issuer == null && it.audience.isNullOrEmpty() }
                ?.takeIf { it.expiration?.toInstant()?.isAfter(acceptUntil) == false }
        } catch (_: Exception) {
            null
        }
    }

    private fun requireLegacyExpirationWithinCutoff(tokenType: String, expiration: Instant) {
        if (properties.signingMode != GatewayJwtSigningMode.LEGACY_HS256) return
        val cutoff = if (tokenType == GatewayJwtClaims.ACCESS_TOKEN) {
            legacyAccessAcceptUntil
        } else {
            legacyRefreshAcceptUntil
        }
        require(cutoff != null && !expiration.isAfter(cutoff)) {
            "JWT legacy token expiration exceeds its rollout cutoff"
        }
    }

    private fun String.toInstantOrNull(): Instant? = takeIf(String::isNotBlank)?.let(Instant::parse)

    override fun contribute(builder: Info.Builder) {
        builder.withDetail(
            "jwt",
            mapOf(
                "verifier" to "rsa-audience-v1",
                "publicKeySha256" to (publicKeyFingerprint ?: "unconfigured"),
            ),
        )
    }
}
