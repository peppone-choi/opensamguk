package opensamguk.engine.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class WorldIdConfig {
    @Bean
    fun engineProcessWorld(@Value("\${OPENSAMGUK_WORLD_ID}") rawWorldId: String): EngineProcessWorld =
        EngineProcessWorld(rawWorldId.toIntOrNull() ?: error("OPENSAMGUK_WORLD_ID must be a positive integer"))
}
