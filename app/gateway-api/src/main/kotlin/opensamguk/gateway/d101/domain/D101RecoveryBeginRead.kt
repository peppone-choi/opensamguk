package opensamguk.gateway.d101.domain

/** Called only after the existing QUERY purpose has admitted this execution.
 * The implementation rereads committed state and the receipt under DB locks. */
internal fun interface D101RecoveryBeginReader {
    fun readForQuery(execution: D101Execution): D101RecoveryBeginRead
}

internal class D101RecoveryBeginRead(
    val operationId: String,
    val verifyingRevision: Long,
    val beginReceiptSha256: String,
    original: ByteArray,
) {
    private val original = original.copyOf()
    fun originalBytes(): ByteArray = original.copyOf()

    init {
        require(D101StrictJson.OPERATION.matches(operationId) && verifyingRevision > 0 &&
            D101StrictJson.SHA.matches(beginReceiptSha256) && original.isNotEmpty() && original.size <= 16 * 1024 &&
            D101StrictJson.hash(original) == beginReceiptSha256)
    }
}
