package opensamguk.gameapi.security

import java.util.concurrent.TimeUnit

/** Publication 신원이다. 실제 world/generation 검증은 별도 원천이 맡는다. */
enum class ServerPublicationState { PUBLIC, VERIFYING }

internal data class ServerAdmissionSnapshot(val serverId: String, val state: ServerPublicationState, val revision: Long)

sealed interface ServerAdmissionRead {
    class Known internal constructor(
        internal val snapshot: ServerAdmissionSnapshot,
        internal val startedNanos: Long,
        internal val budgetNanos: Long,
    ) : ServerAdmissionRead
    data object Unavailable : ServerAdmissionRead
}

fun interface ServerAdmissionSource {
    /** 이전 성공을 반환하지 않고 이번 조회의 완료 결과만 제공한다. */
    fun readFresh(): ServerAdmissionRead
}

sealed interface ServerAdmissionDecision {
    class Allowed internal constructor(internal val proof: ServerAdmissionRead.Known, internal val failureEpoch: Long) : ServerAdmissionDecision
    enum class Denied(val httpStatus: Int, val code: String) : ServerAdmissionDecision {
        AUTH_REQUIRED(401, "AUTH_REQUIRED"),
        NOT_PUBLIC(403, "SERVER_NOT_PUBLIC"),
        UNAVAILABLE(503, "SERVER_ADMISSION_UNAVAILABLE"),
    }
}

/** HTTP/SSE/WS가 공유할 revision high-water. 이 상태를 성공 cache로 사용하지 않는다. */
class ServerAdmissionPolicy(
    private val source: ServerAdmissionSource,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val lock = Any()
    private var highest: ServerAdmissionSnapshot? = null
    private var failureEpoch = 0L

    fun checkOrdinary(): ServerAdmissionDecision {
        val fresh = try { source.readFresh() as? ServerAdmissionRead.Known } catch (_: Exception) { null }
            ?: return unavailable()
        return synchronized(lock) {
            if (expired(fresh)) return@synchronized unavailable()
            val previous = highest
            val current = fresh.snapshot
            if (previous != null && (current.serverId != previous.serverId || current.revision < previous.revision ||
                (current.revision == previous.revision && current.state != previous.state))) {
                return@synchronized unavailable()
            }
            highest = current
            if (current.state == ServerPublicationState.PUBLIC) ServerAdmissionDecision.Allowed(fresh, failureEpoch)
            else ServerAdmissionDecision.Denied.NOT_PUBLIC
        }
    }

    /** verifiedAuthentication은 JWT/ticket 검증 후 소비자가 주며 client 역할 입력을 받지 않는다. */
    fun checkHttp(verifiedAuthentication: Boolean): ServerAdmissionDecision = when (val decision = checkOrdinary()) {
        ServerAdmissionDecision.Denied.NOT_PUBLIC -> if (verifiedAuthentication) decision else ServerAdmissionDecision.Denied.AUTH_REQUIRED
        else -> decision
    }

    /** 같은 fanOut round에서만 사용한다. 다른 round/요청의 새 조회를 대체하지 않는다. */
    fun stillCurrent(allowed: ServerAdmissionDecision.Allowed): Boolean = synchronized(lock) {
        !expired(allowed.proof) && failureEpoch == allowed.failureEpoch && highest == allowed.proof.snapshot
    }

    private fun unavailable(): ServerAdmissionDecision.Denied = synchronized(lock) {
        failureEpoch++
        ServerAdmissionDecision.Denied.UNAVAILABLE
    }

    private fun expired(fresh: ServerAdmissionRead.Known): Boolean =
        nanoTime() - fresh.startedNanos >= fresh.budgetNanos
}

/** 수치는 C8/C2 ACK 대기인 초안이며 운영에 배선되지 않았다. */
internal object ServerAdmissionDraftBudget {
    const val CONNECT_MILLIS = 500L
    const val TOTAL_MILLIS = 2_000L
    const val MAX_CONCURRENT = 8
    const val MAX_BODY_BYTES = 4_096
    val totalNanos: Long = TimeUnit.MILLISECONDS.toNanos(TOTAL_MILLIS)
}
