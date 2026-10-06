package opensamguk.gateway.d101.security

import opensamguk.gateway.d101.domain.*

/** C3 service verifies purpose before reading, then rechecks exact evidence and
 * same-op state/version under its own parent/publication/execution/registry CAS. */
internal interface D101RecoveryAuthority {
    fun readBegin(execution: D101Execution, rootResultReceiptSha256: String): D101VerifiedRecoveryBegin
    fun readClose(execution: D101Execution, recoveryBeginReceiptSha256: String,
                  recoveryResultReceiptSha256: String): D101VerifiedRecoveryClose
}

internal class UnavailableD101RecoveryAuthority : D101RecoveryAuthority {
    override fun readBegin(execution: D101Execution, rootResultReceiptSha256: String): D101VerifiedRecoveryBegin =
        throw D101ObservationUnavailable()
    override fun readClose(execution: D101Execution, recoveryBeginReceiptSha256: String,
                           recoveryResultReceiptSha256: String): D101VerifiedRecoveryClose = throw D101ObservationUnavailable()
}

internal class D101VerifiedRecoveryBegin(
    execution: D101Execution,
    val rootResultReceiptSha256: String,
    val rootStatus: String,
    original: ByteArray,
) {
    private val intent = execution.intentBytes()
    private val prepare = execution.preparePayload()
    private val state = execution.state
    private val safe = execution.lastSafeState
    private val verifying = execution.verifyingRevision
    private val bytes = original.copyOf()
    init {
        require(rootStatus in setOf("SUCCEEDED","FAILED","RECOVERY_REQUIRED","CANCELLED") &&
            D101StrictJson.SHA.matches(rootResultReceiptSha256) && bytes.isNotEmpty() &&
            bytes.size <= 16 * 1024 && D101StrictJson.hash(bytes) == rootResultReceiptSha256)
    }
    fun originalBytes() = bytes.copyOf()
    fun requireMatches(execution: D101Execution, receiptSha256: String) {
        if (!intent.contentEquals(execution.intentBytes()) || !prepare.contentEquals(execution.preparePayload()) ||
            state != execution.state || safe != execution.lastSafeState || verifying != execution.verifyingRevision ||
            receiptSha256 != rootResultReceiptSha256) throw D101OperationConflict()
    }
}

internal class D101VerifiedRecoveryClose(
    execution: D101Execution,
    val recoveryBeginReceiptSha256: String,
    val recoveryResultReceiptSha256: String,
    val originalRootResultSha256: String,
    val backupManifestSha256: String,
    val oldGeneration: Int,
    val oldScenarioCode: String,
    oldImageDigests: Map<String, String>,
    val oldRegistryReceiptSha256: String,
    val oldPublicationReceiptSha256: String,
    val oldWorldReceiptSha256: String,
    val snapshots: D101RecoverySnapshots,
    original: ByteArray,
) {
    val oldCanonicalRegistry get() = snapshots.oldCanonicalRegistry
    val restoredWorld get() = snapshots.restoredWorld
    fun oldRegistryOriginalBytes() = snapshots.oldRegistryOriginalBytes()
    fun oldWorldOriginalBytes() = snapshots.oldWorldOriginalBytes()
    private val intent = execution.intentBytes()
    private val prepare = execution.preparePayload()
    private val safe = execution.lastSafeState
    private val verifying = execution.verifyingRevision
    private val bytes = original.copyOf()
    val oldImageDigests = java.util.Collections.unmodifiableMap(oldImageDigests.toMap())
    init {
        require(execution.state == D101ExecutionState.RECOVERY_REQUIRED && oldGeneration >= 0 &&
            (snapshots.oldCanonicalRegistry.generation == null || snapshots.oldCanonicalRegistry.generation == oldGeneration) &&
            (snapshots.oldCanonicalRegistry.scenarioCode == null || snapshots.oldCanonicalRegistry.scenarioCode == oldScenarioCode) &&
            snapshots.restoredWorld.generation == oldGeneration && snapshots.restoredWorld.scenarioCode == oldScenarioCode &&
            D101StrictJson.hash(snapshots.oldRegistryOriginalBytes()) == oldRegistryReceiptSha256 &&
            D101StrictJson.hash(snapshots.oldWorldOriginalBytes()) == oldWorldReceiptSha256 &&
            oldScenarioCode.matches(Regex("scenario_[0-9]+")) &&
            listOf(recoveryBeginReceiptSha256,recoveryResultReceiptSha256,originalRootResultSha256,backupManifestSha256,
                oldRegistryReceiptSha256,oldPublicationReceiptSha256,oldWorldReceiptSha256).all(D101StrictJson.SHA::matches) &&
            bytes.isNotEmpty() && bytes.size <= 16 * 1024 && D101StrictJson.hash(bytes) == recoveryResultReceiptSha256)
    }
    fun originalBytes() = bytes.copyOf()
    fun requireMatches(execution: D101Execution, beginSha: String, resultSha: String) {
        if (execution.state != D101ExecutionState.RECOVERY_REQUIRED || safe != execution.lastSafeState ||
            verifying != execution.verifyingRevision || !intent.contentEquals(execution.intentBytes()) ||
            !prepare.contentEquals(execution.preparePayload()) || beginSha != recoveryBeginReceiptSha256 ||
            resultSha != recoveryResultReceiptSha256 || oldImageDigests != execution.intent.oldImageDigests)
            throw D101OperationConflict()
        if (execution.rootResultReceiptSha256 != null && execution.rootResultReceiptSha256 != originalRootResultSha256)
            throw D101OperationConflict()
    }
}
