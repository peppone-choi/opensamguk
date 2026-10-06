package opensamguk.gameapi.court.office

import opensamguk.gameapi.owner.GeneralResolver
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

class LocalOfficesForbidden : RuntimeException()

/** Own live character authorization and the tenure store are observed in the same read transaction. */
@Service
class LocalOfficesQuery(private val resolver: GeneralResolver, private val reader: LocalOfficesReader) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun read(generalId: Int, userId: Long): LocalOfficesDto {
        if (generalId <= 0 || userId <= 0 || userId > Int.MAX_VALUE.toLong()) throw LocalOfficesForbidden()
        val actor = resolver.resolve(userId)?.general?.takeIf {
            it.id == generalId && it.userId == userId.toString()
        } ?: throw LocalOfficesForbidden()
        return reader.read(actor.worldId, actor.nationId)
    }
}
