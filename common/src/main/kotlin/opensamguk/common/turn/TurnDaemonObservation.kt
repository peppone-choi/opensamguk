package opensamguk.common.turn

import java.time.Duration
import java.time.Instant

/** A bounded read of the current engine gate, never a durable plock mirror. */
data class TurnDaemonObservation(
    val serverId: String,
    val worldId: Int,
    val sourceObservedAt: Instant,
    val receivedAt: Instant,
    val paused: Boolean,
)

/** Pure projection for an OPEN world. Callers provide the confirmed observation freshness budget. */
object TurnDaemonProjection {
    enum class State { RUNNING, CATCHING_UP, WAITING, PAUSED, STALLED, UNKNOWN }
    enum class ObservationState { CURRENT, MISSING, EXPIRED, INVALID }

    data class Result(
        val serverTime: Instant,
        val state: State,
        val paused: Boolean?,
        val pausedReason: String?,
        val observationState: ObservationState,
        val sourceObservedAt: Instant?,
        val receivedAt: Instant?,
        val staleSeconds: Long?,
    )

    fun observe(
        serverId: String,
        worldId: Int,
        serverTime: Instant,
        observation: TurnDaemonObservation?,
        maxObservationAge: Duration,
        lastTickExecutedAt: Instant?,
        nextTurnAt: Instant?,
        tickSeconds: Int,
        catchUpActive: Boolean,
    ): Result {
        require(!maxObservationAge.isZero && !maxObservationAge.isNegative)
        val observationState = when {
            observation == null -> ObservationState.MISSING
            tickSeconds > 0 && maxObservationAge >= Duration.ofSeconds(tickSeconds.toLong() * 3L) -> ObservationState.INVALID
            observation.serverId != serverId || observation.worldId != worldId || observation.sourceObservedAt > observation.receivedAt ||
                observation.receivedAt > serverTime -> ObservationState.INVALID
            Duration.between(observation.sourceObservedAt, serverTime) > maxObservationAge ||
                Duration.between(observation.receivedAt, serverTime) > maxObservationAge -> ObservationState.EXPIRED
            else -> ObservationState.CURRENT
        }
        val paused = observation?.paused?.takeIf { observationState == ObservationState.CURRENT }
        val age = lastTickExecutedAt?.let { Duration.between(it, serverTime).seconds }
        val state = when {
            paused == null -> State.UNKNOWN
            paused -> State.PAUSED
            tickSeconds <= 0 -> State.STALLED
            lastTickExecutedAt == null && nextTurnAt?.isAfter(serverTime) == true -> State.WAITING
            age == null || age < -5L * 60L || age > minOf(tickSeconds.toLong() * 3L, 25L * 60L * 60L) -> State.STALLED
            catchUpActive -> State.CATCHING_UP
            else -> State.RUNNING
        }
        return Result(
            serverTime = serverTime,
            state = state,
            paused = paused,
            pausedReason = if (paused == true) "UNKNOWN" else null,
            observationState = observationState,
            sourceObservedAt = observation?.sourceObservedAt,
            receivedAt = observation?.receivedAt,
            staleSeconds = age?.coerceAtLeast(0),
        )
    }
}
