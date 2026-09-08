package opensamguk.gameapi.read

import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.infra.persistence.GeneralPositionRowCodec
import opensamguk.infra.seed.HistoricalBattlefieldCatalog
import opensamguk.logic.world.*
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

data class BattlefieldReadState(val topologyRevision: String, val topologyHash: String,
    val position: GeneralPositionState?, val cityAnchors: Map<Int, StrategicNodeRef>)

@Repository
class BattlefieldReadRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val source: StrategicTopologyReadSource,
    processWorld: GameApiProcessWorld,
) {
    private val worldId = processWorld.worldId.value
    fun read(generalId: Int): BattlefieldReadState {
        val topology = source.projection.topology
        val rows = jdbc.query(
            "SELECT * FROM general_spatial_position WHERE world_id = :world AND general_id = :general",
            mapOf("world" to worldId, "general" to generalId),
            org.springframework.jdbc.core.RowMapper { rs, _ -> GeneralPositionRowCodec.decode(rs) },
        )
        require(rows.size <= 1)
        val position = GeneralPositionSnapshot.fromTopology(topology, rows).stateFor(generalId)
        position?.battlefield?.let { HistoricalBattlefieldCatalog.validatePresence(position.node, it) }
        val cities = jdbc.queryForList("SELECT id FROM city WHERE world_id = :world", mapOf("world" to worldId), Int::class.java)
        return BattlefieldReadState(topology.topologyRevision, topology.contentHash, position,
            HistoricalBattlefieldCatalog.cityAnchors().filterKeys { it in cities })
    }
}
