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
    private val artifacts: ActiveWorldArtifactResolver,
    processWorld: GameApiProcessWorld,
) {
    private val worldId = processWorld.worldId.value
    @org.springframework.transaction.annotation.Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    fun read(generalId: Int): BattlefieldReadState {
        val selected = requireNotNull(artifacts.resolve())
        require(selected.world.id == worldId) { "Battlefield world does not match process world" }
        val topology = requireNotNull(selected.artifacts).projection.topology
        val rows = jdbc.query(
            "SELECT * FROM general_spatial_position WHERE world_id = :world AND general_id = :general",
            mapOf("world" to worldId, "general" to generalId),
            org.springframework.jdbc.core.RowMapper { rs, _ -> GeneralPositionRowCodec.decode(rs) },
        )
        require(rows.size <= 1)
        val position = GeneralPositionSnapshot.fromTopology(topology, rows).stateFor(generalId)
        position?.battlefield?.let { HistoricalBattlefieldCatalog.validatePresence(position.node, it) }
        val cities = selected.cities.map { it.id }
        return BattlefieldReadState(topology.topologyRevision, topology.contentHash, position,
            HistoricalBattlefieldCatalog.cityAnchors().filterKeys { it in cities })
    }
}
