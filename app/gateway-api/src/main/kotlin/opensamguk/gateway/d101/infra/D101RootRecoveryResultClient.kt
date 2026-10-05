package opensamguk.gateway.d101.infra

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gateway.d101.domain.*
import opensamguk.gateway.d101.security.*
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference

internal class D101RootRecoveryResultClient(
    private val fixedPrivateOrigin: URI,
    private val token: () -> String,
    private val authority: D101PurposeAuthority = UnavailableD101PurposeAuthority(),
    private val clock: Clock = Clock.systemUTC(),
    private val mapper: ObjectMapper = ObjectMapper(),
    private val transport: D101RootResultTransport = D101UrlConnectionResultTransport(),
) {
    fun readVerified(execution: D101Execution, beginSha: String, resultSha: String): D101VerifiedRecoveryClose {
        requireD101FixedPrivateOrigin(fixedPrivateOrigin)
        if (execution.state != D101ExecutionState.RECOVERY_REQUIRED || execution.dispatch == null ||
            !listOf(beginSha,resultSha).all(D101StrictJson.SHA::matches) || !slots.tryAcquire()) unavailable()
        val done = CountDownLatch(1)
        val result = AtomicReference<D101VerifiedRecoveryClose?>()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        val worker = Thread({
            try { result.set(readWithin(execution,beginSha,resultSha,deadline)) }
            catch (_: Exception) { result.set(null) }
            finally { slots.release(); done.countDown() }
        },"d101-recovery-result-reader").apply { isDaemon=true }
        try { worker.start() } catch (_: Exception) { slots.release(); unavailable() }
        try {
            val left=deadline-System.nanoTime()
            if (left<=0 || !done.await(left,TimeUnit.NANOSECONDS)) { worker.interrupt(); unavailable() }
            return result.get() ?: unavailable()
        } catch (_: InterruptedException) { worker.interrupt(); Thread.currentThread().interrupt(); unavailable() }
    }

    private fun readWithin(e: D101Execution, beginSha: String, resultSha: String, deadline: Long): D101VerifiedRecoveryClose {
        val json=D101StrictJson(mapper)
        val trust=authority.readVerified(e.intent.sha256)
        if (trust.approvedIntent.sha256!=e.intent.sha256 || trust.approvedIntent.operationId!=e.intent.operationId ||
            trust.approvedIntent.targetFingerprint!=e.intent.targetFingerprint ||
            trust.approvedIntent.oldImageDigests!=e.intent.oldImageDigests ||
            !listOf(trust.deploymentCardSha256,trust.approvedReceiptProvenanceSha256,
                trust.clockAgreementReceiptSha256).all(D101StrictJson.SHA::matches)) unavailable()
        val key=D101Ed25519.decodePublicKey(trust.publicKeySpki(),trust.publicKeySpkiSha256)
        val credential=token()
        if (credential.isBlank() || credential.length>4096 || credential.any { it.code !in 33..126 }) unavailable()
        val uri=URI(fixedPrivateOrigin.scheme,null,fixedPrivateOrigin.host,fixedPrivateOrigin.port,
            "/operations/${e.intent.operationId}/recovery-result/$resultSha",null,null)
        val response=transport.fetch(uri,credential,deadline)
        val wire=response.body()
        if (response.status!=200 || response.proofHeaders.size!=1 || wire.isEmpty() ||
            wire.size>16*1024 || D101StrictJson.hash(wire)!=resultSha) unavailable()
        val proof=response.proofHeaders.single().split('.')
        if (proof.size!=2 || proof[0]!=trust.keyId ||
            !D101Ed25519.verify(key,DOMAIN+wire,json.base64url(proof[1],64))) unavailable()
        val root=json.objectBytes(wire,KEYS,16*1024)
        if (json.positiveLong(root["schemaVersion"])!=1L || json.text(root["kind"])!="D101_RECOVERY_RESULT_V1" ||
            json.text(root["status"])!="RECOVERED" || json.text(root["serverId"])!="pep" ||
            json.positiveLong(root["worldId"])!=1L || json.text(root["operationId"])!=e.intent.operationId ||
            json.sha(root["approvalIntentSha256"])!=e.intent.sha256 ||
            json.sha(root["targetFingerprint"])!=e.intent.targetFingerprint ||
            json.sha(root["gatewayPayloadSha256"])!=e.gatewayPayloadSha256 ||
            json.revision(root["verifyingRevision"])!=e.verifyingRevision ||
            json.sha(root["recoveryBeginReceiptSha256"])!=beginSha ||
            (e.rootResultReceiptSha256!=null && json.sha(root["originalRootResultSha256"])!=e.rootResultReceiptSha256) ||
            json.positiveLong(root["restoreAttempt"])!=1L ||
            json.positiveLong(root["recoveryDeadlineUnix"])!=e.intent.recoveryDeadlineUnix) unavailable()
        val images=json.stringMap(root["oldImageDigests"],D101ApprovalIntentCodec.FIVE_IMAGES,true)
        if (images!=e.intent.oldImageDigests) unavailable()
        for (field in listOf("recoveryClaimSha256","restoredDatabaseReceiptSha256",
            "restoredRuntimeReceiptSha256","restoredSelectedMetadataReceiptSha256")) json.sha(root[field])
        val generation=root["oldGeneration"]
        if (!generation.isIntegralNumber || !generation.canConvertToInt() || generation.intValue()<0) unavailable()
        fun instant(field:String):Instant {
            val value=json.text(root[field])
            if (!Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?Z").matches(value)) unavailable()
            return Instant.parse(value)
        }
        val started=instant("startedAtUtc"); val completed=instant("completedAtUtc"); val now=clock.instant()
        if (started<e.createdAt || completed<started || completed>now ||
            java.time.Duration.between(completed,now).seconds>=30 ||
            started.epochSecond<e.intent.windowOpensAtUnix ||
            completed.epochSecond>=e.intent.recoveryDeadlineUnix || now.epochSecond>=e.intent.recoveryDeadlineUnix ||
            System.nanoTime()>=deadline) unavailable()
        val snapshots = D101RecoverySnapshots.decode(json,e,beginSha,
            json.base64url(json.text(root["oldCanonicalRegistryBytesBase64url"]),16*1024),json.sha(root["oldRegistryReceiptSha256"]),
            json.base64url(json.text(root["restoredOldWorldBytesBase64url"]),16*1024),json.sha(root["oldWorldReceiptSha256"]),
            generation.intValue(),json.text(root["oldScenarioCode"]),json.sha(root["restoredDatabaseReceiptSha256"]),
            json.sha(root["restoredRuntimeReceiptSha256"]),started,completed)
        return D101VerifiedRecoveryClose(e,beginSha,resultSha,json.sha(root["originalRootResultSha256"]),json.sha(root["backupManifestSha256"]),
            generation.intValue(),json.text(root["oldScenarioCode"]),images,
            json.sha(root["oldRegistryReceiptSha256"]),json.sha(root["oldPublicationReceiptSha256"]),
            json.sha(root["oldWorldReceiptSha256"]),snapshots,wire)
    }
    private fun unavailable():Nothing=throw D101ObservationUnavailable()
    companion object {
        private val slots=Semaphore(2,true)
        val DOMAIN:ByteArray get()="OPENSAMGUK-D101-RECOVERY-RESULT-V1\n".toByteArray(Charsets.US_ASCII)
        private val KEYS=setOf("schemaVersion","kind","status","serverId","worldId","operationId",
            "approvalIntentSha256","targetFingerprint","gatewayPayloadSha256","verifyingRevision",
            "recoveryBeginReceiptSha256","originalRootResultSha256","backupManifestSha256","recoveryClaimSha256",
            "restoredDatabaseReceiptSha256","restoredRuntimeReceiptSha256","restoredSelectedMetadataReceiptSha256",
            "oldImageDigests","restoreAttempt","startedAtUtc","completedAtUtc","recoveryDeadlineUnix",
            "oldGeneration","oldScenarioCode","oldRegistryReceiptSha256","oldPublicationReceiptSha256","oldWorldReceiptSha256",
            "oldCanonicalRegistryBytesBase64url","restoredOldWorldBytesBase64url")
    }
}
