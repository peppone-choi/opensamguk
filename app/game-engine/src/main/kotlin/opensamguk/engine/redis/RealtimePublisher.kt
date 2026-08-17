package opensamguk.engine.redis

import opensamguk.common.wire.RealtimeEvent
import opensamguk.common.wire.commandResultKey
import opensamguk.common.world.WorldId
import opensamguk.common.wire.gameEventChannel
import opensamguk.common.wire.WireJson
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration

open class RealtimePublisher(
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

    open fun publishCommandResultPayload(requestId: String, payloadJson: String) {
        template.opsForValue().set(
            commandResultKey(profileName, worldId, requestId),
            payloadJson,
            COMMAND_RESULT_TTL,
        )
    }

    /**
     * OPENSAM-45 (1-b·1-c·1-d) — 명령 결과 lifecycle 신호를 턴 이벤트와 **같은 채널**로 보낸다.
     *
     * 채널을 나누지 않는 이유: 브라우저는 SSE 연결 하나(`/sse/turn`)만 열고 이벤트 이름으로 분기한다.
     * 채널을 늘리면 릴레이·구독자·프록시가 전부 두 벌이 되는데, 얻는 것은 없다.
     *
     * 발행 시점은 호출자가 보장한다 — [opensamguk.engine.redis.CommandOutboxRelay]는 이미 커밋된
     * outbox 행만 읽으므로 커밋 전 알림이 구조적으로 불가능하다.
     */
    open fun publishRealtimeEvent(event: RealtimeEvent) {
        template.convertAndSend(
            gameEventChannel(profileName, worldId),
            WireJson.encodeToString(RealtimeEvent.serializer(), event),
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
