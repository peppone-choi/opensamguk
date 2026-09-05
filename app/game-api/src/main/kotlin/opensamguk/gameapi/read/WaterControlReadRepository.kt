package opensamguk.gameapi.read

import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.infra.persistence.WaterControlRowCodec
import opensamguk.logic.world.StrategicTopologySnapshot
import opensamguk.logic.world.WaterControlSnapshot
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

/** Dedicated read seam: never reads another world and never infers missing control. */
@Repository
class WaterControlReadRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    processWorld: GameApiProcessWorld,
) {
    private val worldId = processWorld.worldId

    fun readSnapshot(expectedWorldId: Int, topology: StrategicTopologySnapshot): WaterControlSnapshot {
        require(expectedWorldId == worldId.value) { "Water control world does not match process world" }
        val rows = jdbc.query(
            """SELECT water_zone_id, topology_revision, topology_hash, controlling_nation_id,
                contesting_nation_ids, blockade_state, revision
                FROM water_zone_control WHERE world_id = :world_id ORDER BY water_zone_id""".trimIndent(),
            MapSqlParameterSource("world_id", worldId.value),
        ) { row, _ -> WaterControlRowCodec.decode(row) }
        return WaterControlSnapshot.fromTopology(topology, rows)
    }
}
