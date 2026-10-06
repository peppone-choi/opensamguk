package opensamguk.gateway.d101.domain

import java.time.Instant

internal enum class D101ExecutionState {
    PREPARED, DISPATCH_INTENT, REMOTE_SUCCEEDED, REGISTRY_SETTLED, PUBLISHED, RECOVERY_REQUIRED, RECOVERED,
}

internal class D101OperationConflict : RuntimeException("D101 operation conflicts")
internal class D101OperationNotFound : RuntimeException("D101 operation not found")
internal class D101ObservationUnavailable : RuntimeException("D101 observation unavailable")
internal class D101CapacityUnavailable : RuntimeException("D101 capacity unavailable")

/** Original payloads are immutable; public responses contain only non-secret bindings. */
internal class D101Execution(
    val intent: D101ApprovalIntent,
    intentBytes: ByteArray,
    preparePayload: ByteArray,
    val gatewayPayloadSha256: String,
    val state: D101ExecutionState,
    val lastSafeState: D101ExecutionState,
    val verifyingRevision: Long,
    val dispatch: D101DispatchIntentCandidate?,
    val rootResultReceiptSha256: String?,
    val publishedRevision: Long?,
    val validationReceiptSha256: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    private val originalIntent = intentBytes.copyOf()
    private val originalPrepare = preparePayload.copyOf()
    fun intentBytes(): ByteArray = originalIntent.copyOf()
    fun preparePayload(): ByteArray = originalPrepare.copyOf()

    init {
        require(D101StrictJson.hash(originalIntent) == intent.sha256)
        require(D101StrictJson.hash(originalPrepare) == gatewayPayloadSha256)
        require(verifyingRevision > intent.initialPublicRevision && createdAt <= updatedAt)
        require(lastSafeState !in setOf(D101ExecutionState.RECOVERY_REQUIRED, D101ExecutionState.RECOVERED))
        require(state == lastSafeState || state in setOf(D101ExecutionState.RECOVERY_REQUIRED, D101ExecutionState.RECOVERED))
        require((lastSafeState == D101ExecutionState.PREPARED) == (dispatch == null))
        require(dispatch == null || dispatch.verifyingRevision == verifyingRevision)
        val completed = lastSafeState in setOf(D101ExecutionState.REMOTE_SUCCEEDED, D101ExecutionState.REGISTRY_SETTLED, D101ExecutionState.PUBLISHED)
        require(completed == (rootResultReceiptSha256 != null))
        require(rootResultReceiptSha256 == null || D101StrictJson.SHA.matches(rootResultReceiptSha256))
        require((lastSafeState == D101ExecutionState.PUBLISHED) == (publishedRevision != null && validationReceiptSha256 != null))
        require(publishedRevision == null || publishedRevision > verifyingRevision)
        require((publishedRevision == null) == (validationReceiptSha256 == null))
        require(validationReceiptSha256 == null || D101StrictJson.SHA.matches(validationReceiptSha256))
    }
}

internal data class D101ExecutionWrite(val execution: D101Execution, val created: Boolean)
