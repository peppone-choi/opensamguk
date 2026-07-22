package opensamguk.engine.redis

import opensamguk.common.world.WorldId
import opensamguk.infra.persistence.CommandResultRepository
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
        }.getOrDefault(emptyList())
        var published = 0
        for (event in pending) {
            val marked = runCatching {
                realtimePublisher.publishCommandResultPayload(event.requestId, event.payloadJson)
                repository.markCommandOutboxPublished(worldId, event.eventId, clock.instant())
            }.getOrDefault(false)
            if (marked) {
                published += 1
            }
        }
        return published
    }
}
