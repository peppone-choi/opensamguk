package opensamguk.gameapi.court.imperial

import opensamguk.gameapi.read.ActiveWorldArtifactResolver
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.NationReadRepository
import opensamguk.gameapi.read.WorldStateReadRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

@Component
class ImperialCourtReader(
    private val worlds: WorldStateReadRepository,
    generals: GeneralReadRepository,
    nations: NationReadRepository,
    cities: CityReadRepository,
    artifacts: ActiveWorldArtifactResolver,
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun read(observerWorldId: Int): ImperialCourtDto {
        val world = worlds.findProcessWorld()
        require(world == null || world.id == observerWorldId)
        return ImperialCourtDto(ImperialCourtStatus.STATE_UNAVAILABLE, emptyList())
    }
}
