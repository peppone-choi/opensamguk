package opensamguk.gateway.d101.infra

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gateway.d101.domain.D101PurposeAuthorityUnavailable
import opensamguk.gateway.d101.security.*
import java.time.Clock

/** An atomic provider/Root pair, created only after actual source verification.
 * No automatic bean, env flag, key generation or credential installation. */
internal class D101InstalledDeploymentTrust internal constructor(
    val purpose: D101PurposeAuthority,
    val root: D101RootReaderBinding,
 val selectedProducerIdentity:D101FixedProducerIdentity?=null,
)

internal class D101DeploymentTrustInstaller(
    private val pins: D101DeploymentTrustPins,
    private val source: D101FixedHostTrustSource,
    private val evidenceVerifier: D101HostEvidenceVerifier? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val mapper: ObjectMapper = ObjectMapper(),
) {
    // Construct fixed identity before full14 verification; no dependency on the
    // installed result exists when concrete semantic consumers are assembled.
    private val selectedIdentity=D101FixedProducerIdentity(pins)
    fun fixedProducerIdentity():D101FixedProducerIdentity=selectedIdentity

    fun install(): D101InstalledDeploymentTrust {
        val authority = D101ApprovedPurposeAuthority(pins, source, evidenceVerifier, clock, mapper)
        authority.readVerified(pins.approvalIntentSha256)
        val root = D101RootReaderBinding(pins.fixedPrivateOrigin) {
            // A stale/changed trust source cannot release a Root credential.
            authority.readVerified(pins.approvalIntentSha256)
            source.rootToken().also { token ->
                if (token.isBlank() || token.length > 8192 ||
                    token.any { it.code !in 33..126 }) throw D101PurposeAuthorityUnavailable()
            }
        }
        return D101InstalledDeploymentTrust(authority, root,selectedIdentity)
    }
}
