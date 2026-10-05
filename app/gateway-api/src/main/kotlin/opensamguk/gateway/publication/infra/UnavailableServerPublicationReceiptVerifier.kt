package opensamguk.gateway.publication.infra

import opensamguk.gateway.publication.domain.ServerPublication
import opensamguk.gateway.publication.domain.ServerPublicationReceiptVerifier
import opensamguk.gateway.publication.domain.ServerPublicationSourceUnavailable
import org.springframework.stereotype.Component

// Closed until the exact approved issuer/private receipt source is connected.
// No property or request boolean can bypass this missing production gate.
@Component
class UnavailableServerPublicationReceiptVerifier : ServerPublicationReceiptVerifier {
    override fun verify(serverId: String, publication: ServerPublication, receiptSha256: String) {
        throw ServerPublicationSourceUnavailable()
    }
}
