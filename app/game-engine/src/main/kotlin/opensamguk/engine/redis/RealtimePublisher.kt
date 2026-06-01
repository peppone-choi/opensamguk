package opensamguk.engine.redis

import opensamguk.common.wire.RealtimeEvent
import opensamguk.common.wire.WireJson
import opensamguk.common.wire.gameEventChannel
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * Publishes coarse `turnCompleted` realtime signals (design §4) to the per-profile pub/sub
 * channel. game-api subscribes and fans them out over SSE. Consumes the `:common`
 * [gameEventChannel] directly — no engine-local key helper.
 */
class RealtimePublisher(
    private val template: StringRedisTemplate,
    private val profileName: String,
) {
    fun publishTurnCompleted(
        atIso: String,
        lastTurnTimeIso: String,
        year: Int,
        month: Int,
        turnNumber: Int,
    ) {
        val event = RealtimeEvent.TurnCompleted(
            at = atIso,
            lastTurnTime = lastTurnTimeIso,
            year = year,
            month = month,
            turnNumber = turnNumber,
        )
        template.convertAndSend(
            gameEventChannel(profileName),
            WireJson.encodeToString(RealtimeEvent.serializer(), event),
        )
    }
}
