package opensamguk.engine.redis

import opensamguk.common.wire.CommandSettledEvent
import opensamguk.common.world.WorldId
import opensamguk.infra.persistence.CommandResultRepository
import org.slf4j.LoggerFactory
import java.time.Clock

open class CommandOutboxRelay(
    private val repository: CommandResultRepository,
    private val realtimePublisher: RealtimePublisher,
    private val worldId: WorldId,
    private val clock: Clock = Clock.systemUTC(),
    private val batchSize: Int = 100,
) {
    open fun publishPending(): Int {
        val pending = runCatching {
            repository.findPendingCommandResultOutbox(worldId, batchSize)
        }.onFailure { log.warn("command outbox 조회 실패 world={}", worldId.value, it) }
            .getOrDefault(emptyList())
        var published = 0
        for (event in pending) {
            val marked = runCatching {
                realtimePublisher.publishCommandResultPayload(event.requestId, event.payloadJson)
                repository.markCommandOutboxPublished(worldId, event.eventId, clock.instant())
            }.onFailure { log.warn("command 결과 발행 실패 requestId={}", event.requestId, it) }
                .getOrDefault(false)
            if (marked) {
                published += 1
                // OPENSAM-45 (1-b·1-c·1-d) — 폴링 키를 쓴 **뒤** 깨움 신호를 보낸다. 순서를 뒤집으면
                // 알림을 받은 FE가 아직 없는 키를 읽고 PENDING으로 물러난다(신호가 자기 결과를 앞지른다).
                //
                // 반대 방향의 손실은 남는다: 마킹 성공 후 발행 전에 데몬이 죽으면 그 신호는 재시도 없이
                // 사라진다. 받아들이는 이유는 FE 폴링이 같은 창을 그대로 덮기 때문이다 — 신호가 없으면
                // 예전 속도로 결론이 날 뿐 결과를 잃지 않는다. 신호를 재시도 대상으로 만들면 outbox에
                // 두 번째 '발행됨' 상태가 생겨 멱등 기준이 둘로 갈라진다.
                runCatching {
                    realtimePublisher.publishRealtimeEvent(
                        CommandSettledEvent(at = clock.instant().toString(), requestId = event.requestId),
                    )
                }.onFailure {
                    // 조용히 삼키면 push 경로가 죽어도 폴링이 가려서 아무도 모른다.
                    log.warn("command 결과 깨움 신호 발행 실패 requestId={}", event.requestId, it)
                }
            }
        }
        return published
    }

    private companion object {
        private val log = LoggerFactory.getLogger(CommandOutboxRelay::class.java)
    }
}
