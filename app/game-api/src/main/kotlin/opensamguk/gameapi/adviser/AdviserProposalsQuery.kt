package opensamguk.gameapi.adviser

import opensamguk.gameapi.owner.GeneralResolver
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

class AdviserProposalsForbidden : RuntimeException()

/** Proposals are visible only to the receiving actor; authorization and the world clock share one read transaction. */
@Service
class AdviserProposalsQuery(private val resolver: GeneralResolver, private val reader: AdviserProposalsReader) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun read(generalId: Int, userId: Long): AdviserProposalsDto {
        if (generalId <= 0 || userId <= 0 || userId > Int.MAX_VALUE.toLong()) throw AdviserProposalsForbidden()
        val actor = resolver.resolve(userId)?.general?.takeIf {
            it.id == generalId && it.userId == userId.toString()
        } ?: throw AdviserProposalsForbidden()
        return reader.read(actor.worldId)
    }
}
