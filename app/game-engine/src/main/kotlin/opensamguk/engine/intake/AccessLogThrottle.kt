package opensamguk.engine.intake

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import java.time.Instant

internal class AccessLogThrottle(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val nowProvider: () -> Instant = Instant::now,
) {
    fun increaseAndBlocked(generalId: Int, count: Int = 1): Boolean {
        val current = world.getAccessLog(generalId) ?: return false
        val next = current.copy(
            lastRefresh = nowProvider(),
            refresh = current.refresh + count,
            refreshTotal = current.refreshTotal + count,
            refreshScore = current.refreshScore + count,
            refreshScoreTotal = current.refreshScoreTotal + count,
        )
        recorder.recordAccessLogUpsert(world, next)

        val globalRefresh = (world.getState().meta["refresh"] as? Number)?.toInt() ?: 0
        val nextGlobalRefresh = globalRefresh + count
        world.setGameEnvValue("refresh", nextGlobalRefresh)
        recorder.recordKv("game_env", "game_env", "refresh", nextGlobalRefresh)

        val refreshLimit = (world.getState().meta["refreshLimit"] as? Number)?.toInt() ?: Int.MAX_VALUE
        return next.refreshScore > refreshLimit
    }
}
