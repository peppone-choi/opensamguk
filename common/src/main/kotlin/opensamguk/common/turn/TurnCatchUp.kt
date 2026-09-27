package opensamguk.common.turn

import java.time.Duration
import java.time.Instant

/** Wall-clock pacing for an overdue world. This state never enters the game clock or RNG. */
data class TurnCatchUp(
    val active: Boolean,
    val startedAt: Instant,
    val initialBacklogSeconds: Long,
    val multiplier: Int,
    val lastCalculatedAt: Instant,
    val anchorAt: Instant,
    val anchorGameAt: Instant,
) {
    init {
        require(multiplier == 2 || multiplier == 4) { "catch-up multiplier must be 2 or 4" }
        require(initialBacklogSeconds >= 0)
    }

    fun virtualTime(at: Instant): Instant =
        anchorGameAt.plus(Duration.between(anchorAt, at).multipliedBy(multiplier.toLong()))

    fun wallTimeFor(gameTime: Instant): Instant =
        anchorAt.plus(Duration.between(anchorGameAt, gameTime).dividedBy(multiplier.toLong()))

    fun switchMultiplier(multiplier: Int, at: Instant): TurnCatchUp {
        require(multiplier == 2 || multiplier == 4) { "catch-up multiplier must be 2 or 4" }
        return copy(
            multiplier = multiplier,
            lastCalculatedAt = at,
            anchorAt = at,
            anchorGameAt = virtualTime(at),
        )
    }

    fun snapshot(nextWorldRun: Instant, at: Instant): CatchUpSnapshot {
        val backlog = Duration.between(nextWorldRun, at).seconds.coerceAtLeast(0)
        val remaining = if (active) backlog / (multiplier - 1) else 0
        return CatchUpSnapshot(
            active, multiplier, backlog, remaining, if (active) at.plusSeconds(remaining).toString() else null,
            initialBacklogSeconds, (initialBacklogSeconds - backlog).coerceAtLeast(0),
        )
    }

    fun toMeta(): Map<String, Any?> = linkedMapOf(
        "active" to active,
        "startedAt" to startedAt.toString(),
        "initialBacklogSeconds" to initialBacklogSeconds,
        "multiplier" to multiplier,
        "lastCalculatedAt" to lastCalculatedAt.toString(),
        "anchorAt" to anchorAt.toString(),
        "anchorGameAt" to anchorGameAt.toString(),
    )

    companion object {
        fun snapshotFromStored(value: Any?, lastTurnTime: Instant?, tickSeconds: Int, at: Instant): CatchUpSnapshot {
            val plan = fromMeta(value)
            return if (plan != null && lastTurnTime != null) {
                plan.snapshot(lastTurnTime.plusSeconds(tickSeconds.toLong()), at)
            } else CatchUpSnapshot(false, 2, 0, 0, null)
        }

        fun shouldStart(nextWorldRun: Instant, tickSeconds: Int, at: Instant): Boolean {
            require(tickSeconds > 0)
            return Duration.between(nextWorldRun, at) > Duration.ofSeconds(tickSeconds.toLong() * 2)
        }

        fun start(nextWorldRun: Instant, at: Instant): TurnCatchUp = TurnCatchUp(
            active = true,
            startedAt = at,
            initialBacklogSeconds = Duration.between(nextWorldRun, at).seconds.coerceAtLeast(0),
            multiplier = 2,
            lastCalculatedAt = at,
            anchorAt = at,
            anchorGameAt = nextWorldRun,
        )

        fun fromMeta(value: Any?): TurnCatchUp? {
            val map = value as? Map<*, *> ?: return null
            if (map.isEmpty()) return null
            return TurnCatchUp(
                active = map["active"] as Boolean,
                startedAt = Instant.parse(map["startedAt"] as String),
                initialBacklogSeconds = (map["initialBacklogSeconds"] as Number).toLong(),
                multiplier = (map["multiplier"] as Number).toInt(),
                lastCalculatedAt = Instant.parse(map["lastCalculatedAt"] as String),
                anchorAt = Instant.parse(map["anchorAt"] as String),
                anchorGameAt = Instant.parse(map["anchorGameAt"] as String),
            )
        }
    }
}

data class CatchUpSnapshot(
    val active: Boolean,
    val multiplier: Int,
    val backlogSeconds: Long,
    val remainingSeconds: Long,
    val etaAt: String?,
    val initialBacklogSeconds: Long = 0,
    val recoveredSeconds: Long = 0,
)
