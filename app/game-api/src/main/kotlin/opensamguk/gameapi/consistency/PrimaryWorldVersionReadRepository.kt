package opensamguk.gameapi.consistency

import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.gameapi.config.ReadBarrierJdbcTemplate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.stereotype.Repository

interface WorldVersionReader {
    fun currentWorldVersion(): Long?
}

@Repository
class PrimaryWorldVersionReadRepository(
    readBarrierJdbcTemplate: ReadBarrierJdbcTemplate,
    processWorld: GameApiProcessWorld,
) : WorldVersionReader {
    private val jdbc = readBarrierJdbcTemplate.jdbc
    private val worldId = processWorld.worldId

    override fun currentWorldVersion(): Long? =
        jdbc.query(
            """
            SELECT world_version
              FROM world_state
             WHERE id = :world_id
            """.trimIndent(),
            MapSqlParameterSource("world_id", worldId.value),
        ) { rs, _ -> rs.getLong("world_version") }
            .firstOrNull()
}
