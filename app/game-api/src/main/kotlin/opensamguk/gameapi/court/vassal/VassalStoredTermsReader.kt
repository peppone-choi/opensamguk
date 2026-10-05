package opensamguk.gameapi.court.vassal

import opensamguk.gameapi.read.GameKvReadRepository
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.WorldStateReadRepository
import org.springframework.stereotype.Component

/** Read boundary remains unavailable until the persisted-conditions implementation is supplied. */
@Component
class VassalStoredTermsReader(
    worlds: WorldStateReadRepository,
    gameKv: GameKvReadRepository,
    generals: GeneralReadRepository,
) {
    fun read(worldId: Int, nationId: Int): StoredVassalTermsSnapshot =
        StoredVassalTermsSnapshot(StoredVassalTermsStatus.UNAVAILABLE)
}
