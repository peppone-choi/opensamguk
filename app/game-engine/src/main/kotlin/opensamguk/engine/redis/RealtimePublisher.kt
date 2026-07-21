package opensamguk.engine.redis

import opensamguk.common.wire.RealtimeEvent
import opensamguk.common.wire.TurnDaemonCommandResult
import opensamguk.common.wire.TurnDaemonEvent
import opensamguk.common.wire.TurnDaemonEventEnvelope
import opensamguk.common.wire.WireJson
import opensamguk.common.wire.commandResultKey
import opensamguk.common.world.WorldId
import opensamguk.common.wire.gameEventChannel
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration

/**
 * Publishes coarse `turnCompleted` realtime signals (design §4) to the per-profile pub/sub
 * channel. game-api subscribes and fans them out over SSE. Consumes the `:common`
 * [gameEventChannel] directly — no engine-local key helper.
 *
 * W0-4 인테이크 결과 회신 채널 — [publishCommandResult]가 per-requestId 결과를 [commandResultKey]
 * string 키 아래 짧은 TTL로 SET한다. 엔진 쓰기는 이 Redis 결과 채널뿐(JPA 없음 — one-daemon-write-rule
 * 비위반: ChangeRecorder→JdbcFlushExecutor 경로 밖의 DB 쓰기가 아니라 휘발성 Redis 회신이다).
 */
class RealtimePublisher(
    private val template: StringRedisTemplate,
    private val profileName: String,
    private val worldId: WorldId,
) {
    companion object {
        /**
         * 결과 키 TTL — FE 폴링 윈도(제출→데몬 드레인→폴링 수신)만 버티면 되는 휘발성 회신.
         * 데몬은 reserve poke로 수 초 내 드레인하므로 5분이면 충분하고, 만료 후 폴링은 PENDING으로
         * 수렴한다(게이트웨이가 RESOLVED를 위조하지 않는 안전한 기본값).
         */
        val COMMAND_RESULT_TTL: Duration = Duration.ofMinutes(5)
    }

    /**
     * 인테이크 결과 1건을 [TurnDaemonEventEnvelope]\(commandResult\)로 감싸 [commandResultKey] 아래
     * [COMMAND_RESULT_TTL]로 SET한다. game-api `GET /api/command/result/{requestId}`가 같은 키를
     * 읽어 폴링 응답한다. deny(ok=false)도 동일하게 회신된다 — 페이지가 성공 토스트를 위조하지
     * 않으려면 deny가 반드시 돌아와야 한다.
     */
    fun publishCommandResult(requestId: String, result: TurnDaemonCommandResult, sentAtIso: String) {
        val envelope = TurnDaemonEventEnvelope(
            requestId = requestId,
            sentAt = sentAtIso,
            event = TurnDaemonEvent.CommandResult(result),
        )
        template.opsForValue().set(
            commandResultKey(profileName, worldId, requestId),
            WireJson.encodeToString(TurnDaemonEventEnvelope.serializer(), envelope),
            COMMAND_RESULT_TTL,
        )
    }

    fun publishTurnCompleted(
        atIso: String,
        lastTurnTimeIso: String,
        year: Int,
        month: Int,
        turnNumber: Int,
        turnPhase: Int? = null,
        turnPhaseText: String? = null,
    ) {
        val event = RealtimeEvent.TurnCompleted(
            at = atIso,
            lastTurnTime = lastTurnTimeIso,
            year = year,
            month = month,
            turnNumber = turnNumber,
            turnPhase = turnPhase,
            turnPhaseText = turnPhaseText,
        )
        template.convertAndSend(
            gameEventChannel(profileName, worldId),
            WireJson.encodeToString(RealtimeEvent.serializer(), event),
        )
    }
}
