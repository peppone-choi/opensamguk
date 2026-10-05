package opensamguk.gateway.d101.domain

import java.math.BigInteger

internal class D101RequestInvalid : RuntimeException("D101 request is invalid")
internal class D101PurposeGrantInvalid : RuntimeException("D101 purpose grant is invalid")
internal class D101PurposeAuthorityUnavailable : RuntimeException("D101 purpose authority is unavailable")

/** Transport candidate only; source provenance is checked by a separate authority. */
internal class D101ApprovalIntent(
    val sha256: String,
    val operationId: String,
    val targetFingerprint: String,
    val appSourceSha: String,
    val initialPublicRevision: Long,
    val windowOpensAtUnix: Long,
    val destructiveCutoffUnix: Long,
    val recoveryDeadlineUnix: Long,
    val approvalReceiptSha256: String,
    val combinedCiReceiptSha256: String,
    val selectedSourceReceiptSha256: String,
    val isolatedSeedTickReceiptSha256: String,
    val spaceInventoryReceiptSha256: String,
    val oldImageDigests: Map<String, String>,
    val newImageDigests: Map<String, String>,
    val spaceBudget: D101SpaceBudget,
    val target: D101ResetTarget,
)

internal class D101ResetTarget(
    originalBytes: ByteArray,
    val imageDigests: Map<String, String>,
    val storageImageDigests: Map<String, String>,
    val updates: Map<String, String>,
) {
    private val original = originalBytes.copyOf()
    fun originalBytes(): ByteArray = original.copyOf()
}

internal data class D101SpaceBudget(
    val candidateUnpackedBytes: BigInteger,
    val backupBytes: BigInteger,
    val recoveryBytes: BigInteger,
    val temporaryBytes: BigInteger,
    val newFileCount: BigInteger,
    val inodeReserve: BigInteger,
)

internal enum class D101PurposeAction(val method: String, val suffix: String) {
    PREPARE("POST", "/prepare"),
    QUERY("GET", ""),
    DISPATCH_INTENT("POST", "/dispatch-intent"),
    SETTLE_REGISTRY("POST", "/terminal");

    fun path(operationId: String): String =
        "/internal/d101/servers/pep/operations/" + operationId + suffix
}
