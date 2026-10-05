package opensamguk.gameapi.court.imperial

import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.ImperialPresenceReader
import opensamguk.gameapi.read.WorldStateReadRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

@Component
class ImperialActiveCourtSourceReader(
    private val worlds: WorldStateReadRepository,
    private val presence: ImperialPresenceReader,
    private val cities: CityReadRepository,
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun read(): ImperialActiveCourtSource = ImperialActiveCourtSource(ImperialActiveCourtSourceStatus.UNAVAILABLE)
}
