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
    /** 원천을 조회하지 못한 로컬 대기/용량 거절. 다른 완료된 proof를 무효화하지 않는다. */
    data object LocalCapacity : ServerAdmissionRead
}

fun interface ServerAdmissionSource {
    /** 이전 성공을 반환하지 않고 이번 조회의 완료 결과만 제공한다. */
    fun readFresh(): ServerAdmissionRead
    /** 신뢰하는 내부 소비자의 접수 시각. 외부 요청 필드로 받지 않는다. */
    fun readFresh(startedNanos: Long): ServerAdmissionRead = readFresh()
    /** 같은 monotonic clock에서 확인한 내부 dispatch 대기 여부만 받는다. */
    fun readFresh(startedNanos: Long, localQueueWait: Boolean): ServerAdmissionRead = readFresh(startedNanos)
}

sealed interface ServerAdmissionDecision {
    class Allowed internal constructor(internal val proof: ServerAdmissionRead.Known, internal val failureEpoch: Long) : ServerAdmissionDecision
    enum class Denied(val httpStatus: Int, val code: String) : ServerAdmissionDecision {
        AUTH_REQUIRED(401, "AUTH_REQUIRED"),
        NOT_PUBLIC(403, "SERVER_NOT_PUBLIC"),
        UNAVAILABLE(503, "SERVER_ADMISSION_UNAVAILABLE"),
        LOCAL_CAPACITY(503, "SERVER_ADMISSION_UNAVAILABLE"),
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

    fun checkOrdinary(): ServerAdmissionDecision = checkOrdinary(nanoTime())

    /** queue와 source 조회에 같은 접수 deadline을 전달한다. */
    internal fun checkOrdinary(startedNanos: Long, localQueueWait: Boolean = false): ServerAdmissionDecision {
        val elapsed = nanoTime() - startedNanos
        if (elapsed < 0) return unavailable()
        if (elapsed >= ServerAdmissionDraftBudget.totalNanos) return ServerAdmissionDecision.Denied.LOCAL_CAPACITY
        val read = try { source.readFresh(startedNanos, localQueueWait) } catch (_: Exception) { ServerAdmissionRead.Unavailable }
        if (read === ServerAdmissionRead.LocalCapacity) return ServerAdmissionDecision.Denied.LOCAL_CAPACITY
        val observed = read as? ServerAdmissionRead.Known ?: return unavailable()
        if (nanoTime() - observed.startedNanos < 0) return unavailable()
        // 기본 source 구현도 호출자가 먼저 쓴 시간을 새 예산으로 되돌릴 수 없다.
        val fresh = ServerAdmissionRead.Known(observed.snapshot, minOf(startedNanos, observed.startedNanos),
            minOf(ServerAdmissionDraftBudget.totalNanos, observed.budgetNanos))
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

    private fun expired(fresh: ServerAdmissionRead.Known): Boolean {
        val elapsed = nanoTime() - fresh.startedNanos
        return elapsed < 0 || elapsed >= fresh.budgetNanos
    }
}

/** C8/C2와 대조한 조회 예산. 대기는 같은 전체 예산 안에서 활성 상한과 같은 수로 제한한다. */
internal object ServerAdmissionDraftBudget {
    const val CONNECT_MILLIS = 500L
    const val TOTAL_MILLIS = 2_000L
    const val MAX_CONCURRENT = 8
    const val MAX_BODY_BYTES = 4_096
    val totalNanos: Long = TimeUnit.MILLISECONDS.toNanos(TOTAL_MILLIS)
}
