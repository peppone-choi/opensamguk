package opensamguk.gameapi.season

import opensamguk.gameapi.owner.GeneralResolver
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

class WorldSeasonForbidden : RuntimeException()

/** Own-character authorization and the world date are observed in the same read transaction. */
@Service
class WorldSeasonQuery(
    private val resolver: GeneralResolver,
    private val reader: WorldSeasonReader,
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun read(generalId: Int, userId: Long): WorldSeasonDto {
        if (generalId <= 0 || userId <= 0 || userId > Int.MAX_VALUE.toLong()) throw WorldSeasonForbidden()
        val actor = resolver.resolve(userId)?.general?.takeIf { it.id == generalId }
            ?: throw WorldSeasonForbidden()
        return WorldSeasonProjection.project(reader.read(actor.worldId))
    }
}
