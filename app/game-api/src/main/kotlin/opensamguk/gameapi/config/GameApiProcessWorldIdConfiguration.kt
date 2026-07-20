package opensamguk.gameapi.config

import opensamguk.common.world.WorldId
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

class GameApiProcessWorld(configuredWorldId: Int) {
    val worldId: WorldId = WorldId(configuredWorldId)
}

@Configuration
class GameApiProcessWorldIdConfiguration {
    @Bean
    fun gameApiProcessWorld(@Value("\${opensamguk.world-id}") configuredWorldId: Int): GameApiProcessWorld =
        GameApiProcessWorld(configuredWorldId)
}
