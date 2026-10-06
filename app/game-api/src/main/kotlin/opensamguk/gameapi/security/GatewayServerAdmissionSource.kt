package opensamguk.gameapi.security

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** HTTP 교환 오류와 다른, 원래 요청 전체 예산의 소진이다. */
internal class ServerAdmissionRequestDeadlineException(cause: Throwable? = null) :
    IllegalStateException("server admission deadline", cause)

internal data class ServerAdmissionHttpResponse(val status: Int, val body: String)
internal fun interface ServerAdmissionTransport {
    fun fetch(uri: URI, token: String, startedNanos: Long, budgetNanos: Long): ServerAdmissionHttpResponse
}

/** 필수 설정과 원천은 별도 bean wiring에서 강제한다. 이 초안에는 conditional/default-open bean이 없다. */
class GatewayServerAdmissionSource internal constructor(
    gatewayOrigin: String,
    private val processServerId: String,
    private val serviceToken: String,
    private val transport: ServerAdmissionTransport,
    private val nanoTime: () -> Long = System::nanoTime,
    maxConcurrent: Int = ServerAdmissionDraftBudget.MAX_CONCURRENT,
) : ServerAdmissionSource {
    private val permits: Semaphore
    private val waiting: Semaphore
    private val uri: URI

    constructor(gatewayOrigin: String, processServerId: String, serviceToken: String) : this(
        gatewayOrigin, processServerId, serviceToken, JdkServerAdmissionTransport(),
    )

    init {
        require(processServerId.matches(Regex("[a-z0-9]{1,48}"))) { "server admission identity required" }
        require(serviceToken.isNotBlank()) { "server admission service authentication required" }
        require(maxConcurrent in 1..ServerAdmissionDraftBudget.MAX_CONCURRENT) { "server admission concurrency must be bounded" }
        val origin = URI(gatewayOrigin)
        require(origin.scheme in setOf("http", "https") && origin.host != null && origin.userInfo == null &&
            origin.query == null && origin.fragment == null && origin.path in setOf("", "/")) { "server admission origin required" }
        val id = URLEncoder.encode(processServerId, StandardCharsets.UTF_8).replace("+", "%20")
        uri = origin.resolve("/internal/servers/$id/admission")
        permits = Semaphore(maxConcurrent, true)
        // 활성 상한과 같은 유한 대기 슬롯. 새 시간 예산이나 성공 cache는 만들지 않는다.
        waiting = Semaphore(maxConcurrent)
    }

    override fun readFresh(): ServerAdmissionRead {
        val started = nanoTime()
        return readAt(started, started, false)
    }

    override fun readFresh(startedNanos: Long): ServerAdmissionRead = readFresh(startedNanos, false)

    override fun readFresh(startedNanos: Long, localQueueWait: Boolean): ServerAdmissionRead =
        readAt(startedNanos, nanoTime(), localQueueWait)

    private fun readAt(started: Long, observedNanos: Long, localQueueWait: Boolean): ServerAdmissionRead {
        val elapsed = observedNanos - started
        if (elapsed < 0) return ServerAdmissionRead.Unavailable
        if (elapsed >= ServerAdmissionDraftBudget.totalNanos) return ServerAdmissionRead.LocalCapacity
        var waitedLocally = localQueueWait
        // fair timed acquire는 먼저 기다린 요청을 추월하지 않는다. 대기도 같은 전체 예산에 포함한다.
        try {
            if (!permits.tryAcquire(0, TimeUnit.NANOSECONDS)) {
                if (!waiting.tryAcquire()) return ServerAdmissionRead.LocalCapacity
                waitedLocally = true
                try {
                    val remaining = ServerAdmissionDraftBudget.totalNanos - (nanoTime() - started)
                    if (remaining <= 0 || !permits.tryAcquire(remaining, TimeUnit.NANOSECONDS)) {
                        return ServerAdmissionRead.LocalCapacity
                    }
                } finally { waiting.release() }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return ServerAdmissionRead.LocalCapacity
        }
        try {
            if (nanoTime() - started >= ServerAdmissionDraftBudget.totalNanos) return ServerAdmissionRead.LocalCapacity
            val response = transport.fetch(uri, serviceToken, started, ServerAdmissionDraftBudget.totalNanos)
            if (response.status != 200 || response.body.toByteArray(StandardCharsets.UTF_8).size > ServerAdmissionDraftBudget.MAX_BODY_BYTES) {
                return ServerAdmissionRead.Unavailable
            }
            val node = Json.parseToJsonElement(response.body) as? JsonObject ?: return ServerAdmissionRead.Unavailable
            fun text(key: String): String? = (node[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val id = text("serverId") ?: return ServerAdmissionRead.Unavailable
            if (!id.matches(Regex("[a-z0-9]{1,48}")) || id != processServerId || text("sourceStatus") != "KNOWN") return ServerAdmissionRead.Unavailable
            val state = when (text("state")) {
                "PUBLIC" -> ServerPublicationState.PUBLIC
                "VERIFYING" -> ServerPublicationState.VERIFYING
                else -> return ServerAdmissionRead.Unavailable
            }
            val rawRevision = text("revision") ?: return ServerAdmissionRead.Unavailable
            if (!rawRevision.matches(Regex("[1-9][0-9]{0,18}"))) return ServerAdmissionRead.Unavailable
            val revision = rawRevision.toLongOrNull() ?: return ServerAdmissionRead.Unavailable
            // body 완료·파싱까지 포함한다. deadline 뒤 성공은 revision을 갱신하지 않는다.
            if (nanoTime() - started >= ServerAdmissionDraftBudget.totalNanos) {
                return if (waitedLocally && state == ServerPublicationState.PUBLIC) ServerAdmissionRead.ExpiredPublicObservation(
                    ServerAdmissionSnapshot(id, state, revision), started, ServerAdmissionDraftBudget.totalNanos)
                    else ServerAdmissionRead.Unavailable
            }
            return ServerAdmissionRead.Known(ServerAdmissionSnapshot(id, state, revision), started, ServerAdmissionDraftBudget.totalNanos)
        } catch (_: ServerAdmissionRequestDeadlineException) {
            return if (waitedLocally) ServerAdmissionRead.LocalCapacity else ServerAdmissionRead.Unavailable
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return ServerAdmissionRead.Unavailable
        } catch (_: Exception) {
            // 상류 body·token·예외 상세를 소비자 응답에 내보내지 않는다.
            return ServerAdmissionRead.Unavailable
        } finally {
            permits.release()
        }
    }
}
