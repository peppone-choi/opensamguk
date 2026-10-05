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
import java.security.PublicKey
import java.time.Clock
import java.util.Base64
import java.util.concurrent.FutureTask
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** The transport receives only a URL derived from the trusted, fixed private origin. */
internal fun interface D101RootResultTransport {
    fun fetch(uri: URI, bearerToken: String, deadlineNanos: Long): D101RootResultHttpResponse
}

internal class D101RootResultHttpResponse(val status: Int, body: ByteArray, proofHeaders: List<String>) {
    private val original = body.copyOf()
    val proofHeaders: List<String> = proofHeaders.toList()
    fun body(): ByteArray = original.copyOf()
}

/** No redirect, proxy, query parameter, caller URL, or unbounded response buffer. */
internal class D101UrlConnectionResultTransport : D101RootResultTransport {
    override fun fetch(uri: URI, bearerToken: String, deadlineNanos: Long): D101RootResultHttpResponse {
        val connection = (uri.toURL().openConnection(Proxy.NO_PROXY) as? HttpURLConnection)
            ?: throw D101ObservationUnavailable()
        try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer $bearerToken")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.connectTimeout = remainingMillis(deadlineNanos)
            connection.readTimeout = remainingMillis(deadlineNanos)
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) throw D101ObservationUnavailable()
            val headers = connection.headerFields.entries
                .filter { it.key?.equals("X-D101-Result-Proof", ignoreCase = true) == true }
                .flatMap { it.value.orEmpty() }
            val length = connection.contentLengthLong
            if (length > D101RootExecutionResult.MAX_BYTES) throw D101ObservationUnavailable()
            val out = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val chunk = ByteArray(4096)
                while (true) {
                    connection.readTimeout = remainingMillis(deadlineNanos)
                    val read = input.read(chunk, 0, minOf(chunk.size, D101RootExecutionResult.MAX_BYTES + 1 - out.size()))
                    if (read < 0) break
                    if (read == 0) continue
                    out.write(chunk, 0, read)
                    if (out.size() > D101RootExecutionResult.MAX_BYTES) throw D101ObservationUnavailable()
                }
            }
            remainingMillis(deadlineNanos)
            return D101RootResultHttpResponse(status, out.toByteArray(), headers)
        } finally {
            connection.disconnect()
        }
    }

    private fun remainingMillis(deadlineNanos: Long): Int {
        val remaining = deadlineNanos - System.nanoTime()
        if (remaining <= 0) throw D101ObservationUnavailable()
        return TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}

/** Read only. No production authority or key custody is wired by this module. */
internal class D101RootExecutionResultClient(
    private val fixedPrivateOrigin: URI,
    private val serviceToken: () -> String,
    private val authority: D101PurposeAuthority = UnavailableD101PurposeAuthority(),
    private val clock: Clock = Clock.systemUTC(),
    private val mapper: ObjectMapper = ObjectMapper(),
    private val transport: D101RootResultTransport = D101UrlConnectionResultTransport(),
) {
    init {
        if (!fixedPrivateOrigin.isAbsolute || fixedPrivateOrigin.scheme !in setOf("http", "https") ||
            fixedPrivateOrigin.host.isNullOrBlank() || fixedPrivateOrigin.port !in 1..65535 ||
            fixedPrivateOrigin.rawUserInfo != null || fixedPrivateOrigin.rawQuery != null ||
            fixedPrivateOrigin.rawFragment != null || fixedPrivateOrigin.rawPath !in setOf("", "/")) unavailable()
    }

    fun readVerified(execution: D101Execution, receiptSha256: String): D101RootExecutionResult {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        if (!D101StrictJson.SHA.matches(receiptSha256) || execution.dispatch == null ||
            (execution.rootResultReceiptSha256 != null && execution.rootResultReceiptSha256 != receiptSha256) ||
            execution.intent.operationId.let { !D101StrictJson.OPERATION.matches(it) }) unavailable()
        if (!slots.tryAcquire()) unavailable() // cap 2, queue 0
        val task = FutureTask {
            try { readWithinSlot(execution, receiptSha256, deadline) }
            finally { slots.release() }
        }
        val worker = Thread(task, "d101-root-result-reader").apply { isDaemon = true }
        worker.start()
        return try {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) unavailable()
            task.get(remaining, TimeUnit.NANOSECONDS)
        } catch (_: InterruptedException) {
            task.cancel(true)
            Thread.currentThread().interrupt()
            unavailable()
        } catch (_: Exception) {
            task.cancel(true)
            unavailable()
        }
    }

    private fun readWithinSlot(execution: D101Execution, expectedSha: String, deadline: Long): D101RootExecutionResult {
        try {
            val intent = execution.intent
            val trusted = authority.readVerified(intent.sha256)
            if (!D101StrictJson.KEY_ID.matches(trusted.keyId) ||
                !listOf(trusted.publicKeySpkiSha256, trusted.deploymentCardSha256,
                    trusted.approvedReceiptProvenanceSha256, trusted.clockAgreementReceiptSha256)
                    .all(D101StrictJson.SHA::matches) ||
                trusted.approvedIntent.sha256 != intent.sha256 ||
                trusted.approvedIntent.operationId != intent.operationId ||
                trusted.approvedIntent.targetFingerprint != intent.targetFingerprint ||
                trusted.approvedIntent.appSourceSha != intent.appSourceSha ||
                trusted.approvedIntent.newImageDigests != intent.newImageDigests) unavailable()
            val key = D101Ed25519.decodePublicKey(trusted.publicKeySpki(), trusted.publicKeySpkiSha256)
            val token = serviceToken()
            if (token.isBlank() || token.length > 4096 || token.any { it.isWhitespace() || it.code > 127 }) unavailable()
            val path = "/operations/${intent.operationId}/execution-result/$expectedSha"
            val uri = URI(fixedPrivateOrigin.scheme, null, fixedPrivateOrigin.host, fixedPrivateOrigin.port, path, null, null)
            val response = transport.fetch(uri, token, deadline)
            if (response.status != 200 || response.proofHeaders.size != 1) unavailable()
            val original = response.body()
            // Raw hash is checked before JSON parsing or signature verification.
            if (original.isEmpty() || original.size > D101RootExecutionResult.MAX_BYTES ||
                D101StrictJson.hash(original) != expectedSha) unavailable()
            verifyProof(response.proofHeaders.single(), trusted.keyId, key, original)
            if (System.nanoTime() >= deadline) unavailable()
            return D101RootExecutionResult.decode(original, expectedSha, execution, clock, mapper)
        } catch (_: Exception) {
            unavailable()
        }
    }

    private fun verifyProof(header: String, keyId: String, key: PublicKey, original: ByteArray) {
        if (header.length > 256 || header.any { it.code > 127 }) unavailable()
        val dot = header.lastIndexOf('.')
        if (dot <= 0 || header.substring(0, dot) != keyId) unavailable()
        val signatureText = header.substring(dot + 1)
        if (!Regex("[A-Za-z0-9_-]+").matches(signatureText)) unavailable()
        val signature = try { Base64.getUrlDecoder().decode(signatureText) } catch (_: Exception) { unavailable() }
        if (signature.size != 64 || Base64.getUrlEncoder().withoutPadding().encodeToString(signature) != signatureText ||
            !D101Ed25519.verify(key, DOMAIN + original, signature)) unavailable()
    }

    private fun unavailable(): Nothing = throw D101ObservationUnavailable()

    companion object {
        private val slots = Semaphore(2, true)
        val DOMAIN: ByteArray get() = "OPENSAMGUK-D101-RESULT-V1\n".toByteArray(Charsets.US_ASCII)
    }
}
