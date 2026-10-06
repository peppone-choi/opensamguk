package opensamguk.gameapi.health

import opensamguk.common.turn.TurnDaemonProjection
import opensamguk.gameapi.read.EnginePauseObservationCollector
import opensamguk.gameapi.read.WorldStateReadEntity
import java.time.Duration
import java.time.Instant

/** 한 요청 시각에서 실제 엔진 관측과 저장된 턴 시각을 함께 판단한다. */
object TurnLoopHealth {
    private val unavailableSince = Instant.now()

    data class Observation(
        val lastTurnAt: String?,
        val nextTurnAt: String?,
        val lastTickExecutedAt: String?,
        val state: State,
        val staleSeconds: Long?,
        val daemon: TurnDaemonProjection.Result?,
    ) {
        val stale: Boolean get() = state == State.STALLED
        val healthy: Boolean get() = state in setOf(State.RUNNING, State.CATCHING_UP, State.WAITING)
    }

    enum class State { RUNNING, CATCHING_UP, WAITING, PAUSED, STALLED, UNKNOWN }

    fun observe(world: WorldStateReadEntity, now: Instant,
                collector: EnginePauseObservationCollector? = null): Observation {
        val last = parse(world.meta["lastTurnTime"])
        val start = world.startTime ?: parse(world.meta["startTime"])
        val next = (last ?: start)?.let { runCatching { it.plusSeconds(world.tickSeconds.toLong()) }.getOrNull() }
        val executed = parse(world.meta["lastTickExecutedAt"])
        val age = executed?.let { runCatching { Duration.between(it, now).seconds }.getOrNull() }
        val daemon = if (world.status == "OPEN") {
            if (collector != null && collector.matchesWorld(world.id)) {
                collector.project(now, executed, next, world.tickSeconds, world.catchUp?.get("active") == true)
            } else {
                val since = unavailableSince.takeIf { it <= now } ?: now
                TurnDaemonProjection.Result(now, TurnDaemonProjection.State.UNKNOWN, null, null,
                    TurnDaemonProjection.ObservationState.MISSING, null, null, age?.coerceAtLeast(0),
                    null, since, Duration.between(since, now).seconds,
                    world.tickSeconds > 0 && Duration.between(since, now).seconds > world.tickSeconds.toLong() * 3L, null)
            }
        } else null
        val state = daemon?.let { State.valueOf(it.state.name) } ?: State.PAUSED
        return Observation(last?.toString(), daemon?.nextTurnAt?.toString(), executed?.toString(), state,
            daemon?.staleSeconds ?: age?.coerceAtLeast(0), daemon)
    }

    private fun parse(value: Any?): Instant? = (value as? String)?.let { runCatching { Instant.parse(it) }.getOrNull() }
}
