package opensamguk.gameapi.court.imperial

import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.gameapi.owner.GeneralResolver
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

class ImperialCourtForbidden : RuntimeException()

@Service
class ImperialCourtQuery(
    private val resolver: GeneralResolver,
    private val reader: ImperialCourtReader,
    private val processWorld: GameApiProcessWorld,
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun read(generalId: Int, userId: Long): ImperialCourtDto {
        if (generalId <= 0 || userId <= 0 || userId > Int.MAX_VALUE.toLong()) throw ImperialCourtForbidden()
        val actor = resolver.resolve(userId)?.general?.takeIf {
            it.id == generalId && it.worldId == processWorld.worldId.value
        } ?: throw ImperialCourtForbidden()
        return reader.read(actor.worldId)
    }
}
