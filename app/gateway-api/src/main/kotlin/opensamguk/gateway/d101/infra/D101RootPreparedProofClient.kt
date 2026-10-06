package opensamguk.gateway.d101.infra

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gateway.d101.domain.*
import opensamguk.gateway.d101.security.D101Ed25519
import opensamguk.gateway.d101.security.D101PurposeAuthority
import opensamguk.gateway.d101.security.UnavailableD101PurposeAuthority
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.time.Clock
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal fun interface D101RootPreparedTransport {
    fun fetch(uri: URI, bearerToken: String, deadlineNanos: Long): D101RootPreparedResponse
}

internal class D101RootPreparedResponse(val status: Int, body: ByteArray,
    shaHeaders: List<String>, proofHeaders: List<String>) {
    private val original = body.copyOf()
    val shaHeaders = shaHeaders.toList()
    val proofHeaders = proofHeaders.toList()
    fun body(): ByteArray = original.copyOf()
}

/** Fixed private GET, existing Root token, redirect/proxy/body/query zero. */
internal class D101UrlConnectionPreparedTransport : D101RootPreparedTransport {
    override fun fetch(uri: URI, bearerToken: String, deadlineNanos: Long): D101RootPreparedResponse {
        val connection = (uri.toURL().openConnection(Proxy.NO_PROXY) as? HttpURLConnection)
            ?: throw D101ObservationUnavailable()
        fun remainingMillis(): Int {
            val left = deadlineNanos - System.nanoTime()
            if (left <= 0) throw D101ObservationUnavailable()
            return TimeUnit.NANOSECONDS.toMillis(left).coerceAtLeast(1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer $bearerToken")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.connectTimeout = remainingMillis()
            connection.readTimeout = remainingMillis()
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK || connection.contentLengthLong > D101RootPreparedProof.MAX_BYTES)
                throw D101ObservationUnavailable()
            fun headers(name: String) = connection.headerFields.entries
                .filter { it.key?.equals(name, ignoreCase = true) == true }.flatMap { it.value.orEmpty() }
            val shaHeaders = headers("X-D101-Prepared-Sha256")
            val proofHeaders = headers("X-D101-Prepared-Proof")
            val out = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val chunk = ByteArray(4096)
                while (true) {
                    connection.readTimeout = remainingMillis()
                    val read = input.read(chunk, 0, minOf(chunk.size, D101RootPreparedProof.MAX_BYTES + 1 - out.size()))
                    if (read < 0) break
                    if (read == 0) continue
                    out.write(chunk, 0, read)
                    if (out.size() > D101RootPreparedProof.MAX_BYTES) throw D101ObservationUnavailable()
                }
            }
            remainingMillis()
            return D101RootPreparedResponse(status, out.toByteArray(), shaHeaders, proofHeaders)
        } finally {
            connection.disconnect()
        }
    }
}

/** Uses the existing purpose authority and Ed25519 trust; no new key provider. */
internal class D101RootPreparedProofClient(
    private val fixedPrivateOrigin: URI,
    private val rootToken: () -> String,
    private val authority: D101PurposeAuthority = UnavailableD101PurposeAuthority(),
    private val clock: Clock = Clock.systemUTC(),
    private val mapper: ObjectMapper = ObjectMapper(),
    private val transport: D101RootPreparedTransport = D101UrlConnectionPreparedTransport(),
    private val workerThread: (Runnable) -> Thread = { action ->
        Thread(action, "d101-root-prepared-reader").apply { isDaemon = true }
    },
) {
    init {
        if (!fixedPrivateOrigin.isAbsolute || fixedPrivateOrigin.scheme !in setOf("http", "https") ||
            fixedPrivateOrigin.host !in setOf("deployer", "opensamguk-deployer", "localhost", "127.0.0.1", "[::1]") ||
            fixedPrivateOrigin.port !in 1..65535 || fixedPrivateOrigin.rawUserInfo != null ||
            fixedPrivateOrigin.rawQuery != null || fixedPrivateOrigin.rawFragment != null ||
            fixedPrivateOrigin.rawPath !in setOf("", "/")) unavailable()
    }

    fun readVerified(execution: D101Execution, candidate: D101DispatchIntentCandidate): D101RootPreparedProof {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        if (execution.state != D101ExecutionState.PREPARED || execution.dispatch != null ||
            !D101StrictJson.OPERATION.matches(execution.intent.operationId) || candidate.verifyingRevision != execution.verifyingRevision ||
            !listOf(candidate.approvalPlanSha256, candidate.executionReceiptSha256, candidate.rootRequestFingerprint)
                .all(D101StrictJson.SHA::matches) || !slots.tryAcquire()) unavailable()
        val released = AtomicBoolean(false)
        fun releaseSlot() { if (released.compareAndSet(false, true)) slots.release() }
        val completed = CountDownLatch(1)
        val result = AtomicReference<D101RootPreparedProof?>()
        val worker = try {
            workerThread(Runnable {
                try { result.set(readWithinSlot(execution, candidate, deadline)) }
                catch (_: Exception) { /* No unverifiable proof leaves the reader. */ }
                finally { releaseSlot(); completed.countDown() }
            })
        } catch (_: Throwable) { releaseSlot(); unavailable() }
        try { worker.start() } catch (_: Throwable) { releaseSlot(); unavailable() }
        val done = try {
            val remaining = deadline - System.nanoTime()
            remaining > 0 && completed.await(remaining, TimeUnit.NANOSECONDS)
        } catch (_: InterruptedException) {
            worker.interrupt(); Thread.currentThread().interrupt(); unavailable()
        }
        if (!done) { worker.interrupt(); unavailable() }
        return result.get() ?: unavailable()
    }

    private fun readWithinSlot(execution: D101Execution, candidate: D101DispatchIntentCandidate, deadline: Long): D101RootPreparedProof {
        try {
            val intent = execution.intent
            val trusted = authority.readVerified(intent.sha256)
            if (!D101StrictJson.KEY_ID.matches(trusted.keyId) ||
                !listOf(trusted.publicKeySpkiSha256, trusted.deploymentCardSha256,
                    trusted.approvedReceiptProvenanceSha256, trusted.clockAgreementReceiptSha256).all(D101StrictJson.SHA::matches) ||
                trusted.approvedIntent.sha256 != intent.sha256 || trusted.approvedIntent.operationId != intent.operationId ||
                trusted.approvedIntent.targetFingerprint != intent.targetFingerprint || trusted.approvedIntent.appSourceSha != intent.appSourceSha ||
                trusted.approvedIntent.newImageDigests != intent.newImageDigests ||
                trusted.approvedIntent.windowOpensAtUnix != intent.windowOpensAtUnix ||
                trusted.approvedIntent.destructiveCutoffUnix != intent.destructiveCutoffUnix ||
                trusted.approvedIntent.recoveryDeadlineUnix != intent.recoveryDeadlineUnix) unavailable()
            val key = D101Ed25519.decodePublicKey(trusted.publicKeySpki(), trusted.publicKeySpkiSha256)
            val token = rootToken()
            if (token.isBlank() || token.length > 4096 || token.any { it.isWhitespace() || it.code < 33 || it.code > 126 }) unavailable()
            val path = "/operations/${intent.operationId}/prepared-proof/${candidate.approvalPlanSha256}/${candidate.executionReceiptSha256}"
            val uri = URI(fixedPrivateOrigin.scheme, null, fixedPrivateOrigin.host, fixedPrivateOrigin.port, path, null, null)
            val response = transport.fetch(uri, token, deadline)
            if (response.status != 200 || response.shaHeaders.size != 1 || response.proofHeaders.size != 1) unavailable()
            val original = response.body()
            val expectedSha = response.shaHeaders.single()
            if (!D101StrictJson.SHA.matches(expectedSha) || original.isEmpty() || original.size > D101RootPreparedProof.MAX_BYTES ||
                D101StrictJson.hash(original) != expectedSha) unavailable()
            val header = response.proofHeaders.single()
            if (header.length > 256 || header.any { it.code > 127 }) unavailable()
            val dot = header.lastIndexOf('.')
            if (dot <= 0 || header.substring(0, dot) != trusted.keyId) unavailable()
            val signatureText = header.substring(dot + 1)
            if (!Regex("[A-Za-z0-9_-]+").matches(signatureText)) unavailable()
            val signature = Base64.getUrlDecoder().decode(signatureText)
            if (signature.size != 64 || Base64.getUrlEncoder().withoutPadding().encodeToString(signature) != signatureText ||
                !D101Ed25519.verify(key, DOMAIN + original, signature) || System.nanoTime() >= deadline) unavailable()
            return D101RootPreparedProof.decode(original, expectedSha, execution, candidate, clock, mapper)
        } catch (_: Exception) { unavailable() }
    }

    private fun unavailable(): Nothing = throw D101ObservationUnavailable()

    companion object {
        private val slots = Semaphore(2, true)
        val DOMAIN: ByteArray get() = "OPENSAMGUK-D101-PREPARED-V1\n".toByteArray(Charsets.US_ASCII)
    }
}
