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
        val nextTurnAt: Instant?,
        val unknownSince: Instant?,
        val unknownSeconds: Long?,
        val unknownAlertDue: Boolean,
        val resetCompletedAt: Instant?,
    ) {
        val healthy: Boolean get() = state in setOf(State.RUNNING, State.CATCHING_UP, State.WAITING)
        val healthStatus: String get() = if (healthy) "healthy" else "degraded"
        val failureReason: String? get() = when (state) {
            State.UNKNOWN -> "pause_observation_unavailable"
            State.STALLED -> "turn_stalled"
            State.PAUSED -> "turn_paused"
            else -> null
        }
    }

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
        unknownSince: Instant? = null,
        resetCompletedAt: Instant? = null,
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
        val since = unknownSince?.takeIf { state == State.UNKNOWN && it <= serverTime }
        val unknownAge = since?.let { Duration.between(it, serverTime) }
        return Result(
            serverTime = serverTime,
            state = state,
            paused = paused,
            pausedReason = if (paused == true) "UNKNOWN" else null,
            observationState = observationState,
            sourceObservedAt = observation?.sourceObservedAt,
            receivedAt = observation?.receivedAt,
            staleSeconds = age?.coerceAtLeast(0),
            nextTurnAt = nextTurnAt.takeIf { state in setOf(State.RUNNING, State.CATCHING_UP, State.WAITING) },
            unknownSince = since,
            unknownSeconds = unknownAge?.seconds,
            // UNKNOWN uses only the existing 3tick budget, without the incident-regression cap.
            resetCompletedAt = resetCompletedAt?.takeIf { it <= serverTime },
            unknownAlertDue = unknownAge != null && tickSeconds > 0 && unknownAge > Duration.ofSeconds(tickSeconds.toLong() * 3L),
        )
    }
}
