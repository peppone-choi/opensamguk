package opensamguk.gameapi.court.imperial

import opensamguk.gameapi.read.WorldStateReadRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

@Component
class PublicRespondedEdictSourceReader(private val worlds: WorldStateReadRepository) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun read(): PublicRespondedEdictSource {
        val world = worlds.findProcessWorld() ?: return unavailable()
        require(world.id > 0)
        return unavailable()
    }

    private fun unavailable() = PublicRespondedEdictSource(PublicRespondedEdictSourceStatus.UNAVAILABLE)
}
