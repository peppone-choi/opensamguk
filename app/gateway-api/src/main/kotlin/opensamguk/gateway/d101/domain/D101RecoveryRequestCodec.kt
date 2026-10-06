package opensamguk.gateway.d101.domain

/** The complete JSON body is signed by the distinct recovery purpose grant. */
internal class D101RecoveryRequestCodec(private val json: D101StrictJson) {
    fun begin(wire: ByteArray): D101RecoveryBeginCandidate {
        val root = json.objectBytes(wire.copyOf(), BEGIN_KEYS, 16 * 1024)
        version(root["schemaVersion"])
        return D101RecoveryBeginCandidate(
            json.revision(root["verifyingRevision"]),
            json.sha(root["rootResultReceiptSha256"]),
        )
    }

    fun close(wire: ByteArray): D101RecoveryCloseCandidate {
        val root = json.objectBytes(wire.copyOf(), CLOSE_KEYS, 16 * 1024)
        version(root["schemaVersion"])
        return D101RecoveryCloseCandidate(
            json.revision(root["verifyingRevision"]),
            json.sha(root["recoveryBeginReceiptSha256"]),
            json.sha(root["recoveryResultReceiptSha256"]),
        )
    }

    private fun version(node: com.fasterxml.jackson.databind.JsonNode?) {
        if (json.positiveLong(node) != 1L) json.invalid()
    }

    private companion object {
        val BEGIN_KEYS = setOf("schemaVersion", "verifyingRevision", "rootResultReceiptSha256")
        val CLOSE_KEYS = setOf("schemaVersion", "verifyingRevision", "recoveryBeginReceiptSha256", "recoveryResultReceiptSha256")
    }
}

internal data class D101RecoveryBeginCandidate(val verifyingRevision: Long, val rootResultReceiptSha256: String)
internal data class D101RecoveryCloseCandidate(
    val verifyingRevision: Long,
    val recoveryBeginReceiptSha256: String,
    val recoveryResultReceiptSha256: String,
)
