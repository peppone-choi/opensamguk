package opensamguk.gameapi.read

import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.infra.seed.WorldTopologyPin
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class WorldArtifactIdentityReadRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    processWorld: GameApiProcessWorld,
) {
    private val worldId = processWorld.worldId

    fun readPins(expectedWorldId: Int): List<WorldTopologyPin> {
        require(expectedWorldId == worldId.value) { "Artifact identity world does not match process world" }
        return jdbc.query(
            """SELECT 'water_zone_control' AS channel, topology_revision, topology_hash
                FROM water_zone_control WHERE world_id = :world_id
                UNION ALL
                SELECT 'province_control' AS channel, topology_revision, topology_hash
                FROM province_control WHERE world_id = :world_id
                UNION ALL
                SELECT 'general_spatial_position' AS channel, topology_revision, topology_hash
                FROM general_spatial_position WHERE world_id = :world_id""".trimIndent(),
            MapSqlParameterSource("world_id", worldId.value),
        ) { row, _ -> WorldTopologyPin(row.getString("channel"), row.getString("topology_revision"), row.getString("topology_hash")) }
    }
}
