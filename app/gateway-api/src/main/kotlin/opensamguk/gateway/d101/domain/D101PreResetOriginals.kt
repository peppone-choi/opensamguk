package opensamguk.gateway.d101.domain

import java.util.Base64

/** The caller must have verified the existing QUERY purpose before using this reader. */
internal fun interface D101PreResetOriginalsReader {
    fun readForQuery(execution: D101Execution): D101PreResetOriginalsRead
}

internal class D101PreResetOriginalsRead(
    val operationId: String,
    val approvalIntentSha256: String,
    val targetFingerprint: String,
    val gatewayPayloadSha256: String,
    val initialPublicRevision: Long,
    val originalSha256: String,
    original: ByteArray,
) {
    private val original = original.copyOf()
    fun originalBytes(): ByteArray = original.copyOf()
    fun originalBytesBase64url(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(original)

    init {
        require(D101StrictJson.OPERATION.matches(operationId) &&
            listOf(approvalIntentSha256, targetFingerprint, gatewayPayloadSha256, originalSha256)
                .all { D101StrictJson.SHA.matches(it) } && initialPublicRevision > 0 &&
            original.isNotEmpty() && original.size <= 16 * 1024 &&
            D101StrictJson.hash(original) == originalSha256)
    }

    companion object {
        const val RESPONSE_SHA_FIELD = "preResetOriginalsSha256"
        const val RESPONSE_BYTES_FIELD = "preResetOriginalsBytesBase64url"
    }
}
