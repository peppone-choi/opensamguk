package opensamguk.gateway.d101.security

import opensamguk.gateway.d101.domain.D101PurposeAuthorityUnavailable
import opensamguk.gateway.d101.domain.D101StrictJson

/** Same independently pinned Root key already used by deployment trust. This
 * is immutable identity data, not a new approval issuer or semantic PASS. */
internal class D101FixedProducerIdentity(pins:D101DeploymentTrustPins) {
    val producerIdentity:String=pins.keyId
    val publicKeySpkiSha256:String=pins.purposeSpkiSha256
    private val spki=pins.purposeSpki()
    init {
        if (!D101StrictJson.KEY_ID.matches(producerIdentity)) throw D101PurposeAuthorityUnavailable()
        D101Ed25519.decodePublicKey(spki,publicKeySpkiSha256)
    }
    fun publicKeySpki()=spki.copyOf()
}
