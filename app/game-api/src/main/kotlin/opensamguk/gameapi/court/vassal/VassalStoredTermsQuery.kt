package opensamguk.gameapi.court.vassal

import opensamguk.gameapi.owner.GeneralResolver
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

class VassalTermsForbidden : RuntimeException()

/** Own-character authorization and persisted conditions are observed in the same read transaction. */
@Service
class VassalStoredTermsQuery(
    private val resolver: GeneralResolver,
    private val reader: VassalStoredTermsReader,
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun read(generalId: Int, userId: Long): StoredVassalTermsSnapshot {
        if (generalId <= 0 || userId <= 0 || userId > Int.MAX_VALUE.toLong()) throw VassalTermsForbidden()
        val actor = resolver.resolve(userId)?.general?.takeIf { it.id == generalId }
            ?: throw VassalTermsForbidden()
        return reader.read(actor.worldId, actor.nationId)
    }
}
