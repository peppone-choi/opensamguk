package opensamguk.gameapi.read

import java.time.Duration
import java.time.Instant

/** 공개 읽기에서 사용하는 턴 루프 판단. 운영 상태를 바꾸지 않는다. */
object TurnLoopHealth {
    private const val MAX_HEALTHY_AGE_SECONDS = 25L * 60L * 60L
    private const val MAX_FUTURE_SKEW_SECONDS = 5L * 60L

    data class Observation(
        val lastTurnAt: String?,
        val nextTurnAt: String?,
        val lastTickExecutedAt: String?,
        val state: State,
        /** 마지막 성공 턴의 실제 벽시각 이후 경과 초. 시각이 없으면 null. */
        val staleSeconds: Long?,
    ) {
        val stale: Boolean get() = state == State.STALLED
    }

    enum class State { RUNNING, CATCHING_UP, WAITING, PAUSED, STALLED }

    fun observe(world: WorldStateReadEntity, now: Instant): Observation {
        val last = parse(world.meta["lastTurnTime"])
        val start = world.startTime ?: parse(world.meta["startTime"])
        val next = (last ?: start)?.let { runCatching { it.plusSeconds(world.tickSeconds.toLong()) }.getOrNull() }
        val executed = parse(world.meta["lastTickExecutedAt"])
        val age = executed?.let { runCatching { Duration.between(it, now).seconds }.getOrNull() }
        val state = when {
            world.status != "OPEN" || world.tickSeconds <= 0 -> State.PAUSED
            executed == null && next?.isAfter(now) == true -> State.WAITING
            executed == null || age == null -> State.STALLED
            age < -MAX_FUTURE_SKEW_SECONDS -> State.STALLED
            age > minOf(world.tickSeconds.toLong() * 3, MAX_HEALTHY_AGE_SECONDS) -> State.STALLED
            world.catchUp?.get("active") == true -> State.CATCHING_UP
            else -> State.RUNNING
        }
        return Observation(last?.toString(), next?.toString(), executed?.toString(), state, age?.coerceAtLeast(0))
    }

    private fun parse(value: Any?): Instant? = (value as? String)?.let { runCatching { Instant.parse(it) }.getOrNull() }
}
