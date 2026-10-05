package opensamguk.gameapi.court.offer

import opensamguk.gameapi.owner.GeneralResolver
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

class CourtOffersForbidden : RuntimeException()
class CourtOffersWorldUnavailable : RuntimeException()

@Service
class CourtOffersQuery(private val resolver: GeneralResolver, private val reader: CourtOffersReader) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun read(generalId: Int, userId: Long): CourtOffersDto {
        if (generalId <= 0 || userId <= 0 || userId > Int.MAX_VALUE.toLong()) throw CourtOffersForbidden()
        val actor = resolver.resolve(userId)?.general?.takeIf {
            it.id == generalId && it.userId == userId.toString()
        } ?: throw CourtOffersForbidden()
        return reader.read(actor.worldId, actor.id, userId)
    }
}
