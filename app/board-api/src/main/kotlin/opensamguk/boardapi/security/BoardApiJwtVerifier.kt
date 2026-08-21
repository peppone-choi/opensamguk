package opensamguk.boardapi.security

import opensamguk.common.auth.GatewayAccessTokenVerifier
import opensamguk.common.auth.GatewayJwtContract
import opensamguk.common.auth.GatewayPrincipal
import org.springframework.boot.actuate.info.Info
import org.springframework.boot.actuate.info.InfoContributor
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class BoardApiJwtVerifier(
    @Value("\${jwt.public-key}") publicKey: String,
    @Value("\${jwt.legacy-secret:}") legacySecret: String,
    @Value("\${jwt.legacy-accept-until:}") legacyAcceptUntil: String,
) : InfoContributor {
    private val verifier = GatewayAccessTokenVerifier(
        publicKey,
        GatewayJwtContract.BOARD_API_AUDIENCE,
        legacySecret = legacySecret.takeIf(String::isNotBlank),
        legacyAcceptUntil = legacyAcceptUntil.takeIf(String::isNotBlank)?.let(Instant::parse),
    )

    fun verifyAccessToken(token: String): GatewayPrincipal? = verifier.verifyAccessToken(token)

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
