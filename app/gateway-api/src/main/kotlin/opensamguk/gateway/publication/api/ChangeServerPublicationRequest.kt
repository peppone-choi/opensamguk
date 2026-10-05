package opensamguk.gateway.publication.api

import opensamguk.gateway.publication.domain.*

data class ChangeServerPublicationRequest(
    val state: ServerPublicationState,
    val expectedRevision: String,
    val operationId: String,
    val expectedGeneration: Int? = null,
    val expectedScenarioCode: String? = null,
    val targetFingerprint: String? = null,
    val validationReceiptSha256: String? = null,
) {
    fun revision(): Long {
        val parsed = expectedRevision.toLongOrNull()
        require(parsed != null && parsed > 0 && parsed.toString() == expectedRevision)
        require(operationId.matches(Regex("[a-f0-9]{32}")))
        return parsed
    }

    fun verifying(serverId: String): VerifyServerPublication {
        require(state == ServerPublicationState.VERIFYING && validationReceiptSha256 == null)
        return VerifyServerPublication(serverId, revision(), ServerPublicationTarget(
            operationId, requireNotNull(expectedGeneration),
            requireNotNull(expectedScenarioCode), requireNotNull(targetFingerprint),
        ))
    }

    fun publish(serverId: String): PublishServerPublication {
        require(state == ServerPublicationState.PUBLIC &&
            expectedGeneration == null && expectedScenarioCode == null && targetFingerprint == null)
        val receipt = requireNotNull(validationReceiptSha256)
        require(receipt.matches(Regex("[a-f0-9]{64}")))
        return PublishServerPublication(serverId, revision(), operationId, receipt)
    }
}
