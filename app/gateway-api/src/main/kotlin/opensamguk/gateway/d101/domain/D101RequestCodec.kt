package opensamguk.gateway.d101.domain

/** Raw request hashes exclude headers/credentials and never use reserialization. */
internal class D101RequestCodec(
    private val json: D101StrictJson,
    private val intentCodec: D101ApprovalIntentCodec,
) {
    fun prepare(wire: ByteArray): D101PrepareCandidate {
        val original = wire.copyOf()
        val root = json.objectBytes(original, setOf("schemaVersion", "approvalIntentSha256", "approvalIntentBytesBase64url"), 64 * 1024)
        version(root["schemaVersion"])
        val sha = json.sha(root["approvalIntentSha256"])
        val intentBytes = json.base64url(json.text(root["approvalIntentBytesBase64url"]), 32 * 1024)
        return D101PrepareCandidate(intentCodec.decode(intentBytes, sha), D101StrictJson.hash(original), intentBytes)
    }

    fun dispatchIntent(wire: ByteArray): D101DispatchIntentCandidate {
        val root = json.objectBytes(wire.copyOf(), setOf(
            "schemaVersion", "verifyingRevision", "approvalPlanSha256", "executionReceiptSha256", "rootRequestFingerprint",
        ), 16 * 1024)
        version(root["schemaVersion"])
        return D101DispatchIntentCandidate(
            json.revision(root["verifyingRevision"]), json.sha(root["approvalPlanSha256"]),
            json.sha(root["executionReceiptSha256"]), json.sha(root["rootRequestFingerprint"]),
        )
    }

    fun terminal(wire: ByteArray): D101TerminalCandidate {
        val root = json.objectBytes(wire.copyOf(), setOf("schemaVersion", "verifyingRevision", "rootResultReceiptSha256"), 16 * 1024)
        version(root["schemaVersion"])
        return D101TerminalCandidate(json.revision(root["verifyingRevision"]), json.sha(root["rootResultReceiptSha256"]))
    }

    private fun version(node: com.fasterxml.jackson.databind.JsonNode?) {
        if (json.positiveLong(node) != 1L) json.invalid()
    }
}

internal class D101PrepareCandidate(val intent: D101ApprovalIntent, val gatewayPayloadSha256: String, intentBytes: ByteArray) {
    private val originalIntent = intentBytes.copyOf()
    fun intentBytes(): ByteArray = originalIntent.copyOf()
}
internal data class D101DispatchIntentCandidate(
    val verifyingRevision: Long,
    val approvalPlanSha256: String,
    val executionReceiptSha256: String,
    val rootRequestFingerprint: String,
)
internal data class D101TerminalCandidate(val verifyingRevision: Long, val rootResultReceiptSha256: String)
