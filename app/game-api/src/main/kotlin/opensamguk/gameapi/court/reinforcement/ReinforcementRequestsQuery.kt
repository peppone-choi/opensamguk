package opensamguk.gameapi.court.reinforcement

import opensamguk.gameapi.owner.GeneralResolver
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

class ReinforcementRequestsForbidden : RuntimeException()

/** Own-character authorization and the world date are observed in the same read transaction. */
@Service
class ReinforcementRequestsQuery(
    private val resolver: GeneralResolver,
    private val reader: ReinforcementRequestsReader,
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun read(generalId: Int, userId: Long): ReinforcementRequestsDto {
        if (generalId <= 0 || userId <= 0 || userId > Int.MAX_VALUE.toLong()) throw ReinforcementRequestsForbidden()
        val actor = resolver.resolve(userId)?.general?.takeIf { it.id == generalId }
            ?: throw ReinforcementRequestsForbidden()
        return ReinforcementRequestsProjection.project(reader.read(actor.worldId))
    }
}
