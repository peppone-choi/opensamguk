package opensamguk.gameapi.frontier

import opensamguk.gameapi.owner.GeneralResolver
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

class FrontierForbidden : RuntimeException()

/** Own live character authorization and the world clock are observed in the same read transaction. */
@Service
class FrontierQuery(private val resolver: GeneralResolver, private val reader: FrontierReader) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun read(generalId: Int, userId: Long): FrontierDto {
        if (generalId <= 0 || userId <= 0 || userId > Int.MAX_VALUE.toLong()) throw FrontierForbidden()
        val actor = resolver.resolve(userId)?.general?.takeIf {
            it.id == generalId && it.userId == userId.toString()
        } ?: throw FrontierForbidden()
        return reader.read(actor.worldId, actor.nationId)
    }
}
