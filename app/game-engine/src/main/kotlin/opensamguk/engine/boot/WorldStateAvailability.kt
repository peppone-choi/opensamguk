package opensamguk.engine.boot

import opensamguk.engine.config.EngineProcessWorld
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

fun interface WorldStateAvailability {
    fun hasWorld(): Boolean
}

@Component
class JdbcWorldStateAvailability(
    private val jdbc: JdbcTemplate,
    processWorld: EngineProcessWorld,
) : WorldStateAvailability {
    private val worldId = processWorld.worldId

    override fun hasWorld(): Boolean =
        (jdbc.queryForObject("SELECT count(*) FROM world_state WHERE id = ?", Int::class.java, worldId.value) ?: 0) > 0
}
