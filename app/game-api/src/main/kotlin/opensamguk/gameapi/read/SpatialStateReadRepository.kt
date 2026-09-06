package opensamguk.gameapi.read

import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.infra.persistence.GeneralPositionRowCodec
import opensamguk.infra.persistence.ProvinceControlRowCodec
import opensamguk.logic.world.GeneralPositionSnapshot
import opensamguk.logic.world.GeneralPositionState
import opensamguk.logic.world.ProvinceControlSnapshot
import opensamguk.logic.world.ProvinceControlState
import opensamguk.logic.world.StrategicTopologySnapshot
import org.springframework.jdbc.core.ResultSetExtractor
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

data class SpatialStateReadSnapshot(
    val provinceControlSnapshot: ProvinceControlSnapshot,
    val generalPositionSnapshot: GeneralPositionSnapshot,
)

/** One statement gives both channels the same PostgreSQL MVCC snapshot, even during a flush. */
@Repository
class SpatialStateReadRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    processWorld: GameApiProcessWorld,
) {
    private val worldId = processWorld.worldId

    fun readSnapshot(expectedWorldId: Int, topology: StrategicTopologySnapshot): SpatialStateReadSnapshot {
        require(expectedWorldId == worldId.value) { "Spatial state world does not match process world" }
        return requireNotNull(jdbc.query(
            """
            SELECT 'PROVINCE' AS channel, province_id, nation_id, NULL::integer AS general_id,
                NULL::text AS node_kind, NULL::text AS node_id, topology_revision, topology_hash,
                revision, true AS general_exists
            FROM province_control WHERE world_id = :world_id
            UNION ALL
            SELECT 'POSITION' AS channel, NULL::text AS province_id, NULL::integer AS nation_id,
                p.general_id, p.node_kind, p.node_id, p.topology_revision, p.topology_hash, p.revision,
                g.id IS NOT NULL AS general_exists
            FROM general_spatial_position p
            LEFT JOIN general g ON g.world_id = p.world_id AND g.id = p.general_id
            WHERE p.world_id = :world_id
            ORDER BY channel, province_id, general_id
            """.trimIndent(),
            MapSqlParameterSource("world_id", worldId.value),
            ResultSetExtractor { rows ->
                val provinces = mutableListOf<ProvinceControlState>()
                val positions = mutableListOf<GeneralPositionState>()
                while (rows.next()) {
                    when (rows.getString("channel")) {
                        "PROVINCE" -> provinces.add(ProvinceControlRowCodec.decode(rows))
                        "POSITION" -> {
                            require(rows.getBoolean("general_exists")) { "Orphan general position" }
                            positions.add(GeneralPositionRowCodec.decode(rows))
                        }
                        else -> error("Unknown spatial state channel")
                    }
                }
                SpatialStateReadSnapshot(
                    ProvinceControlSnapshot.fromTopology(topology, provinces),
                    GeneralPositionSnapshot.fromTopology(topology, positions),
                )
            },
        ))
    }
}
