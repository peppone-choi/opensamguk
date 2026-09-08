package opensamguk.infra.persistence

import opensamguk.logic.world.BattlefieldPresence
import opensamguk.logic.world.GeneralPositionState
import opensamguk.logic.world.StrategicNodeRef
import java.sql.ResultSet
import java.util.Collections

/** Strict persistence boundary for a general's strategic node. */
object GeneralPositionRowCodec {
    fun decode(rs: ResultSet): GeneralPositionState {
        val generalId = rs.getObject("general_id")
        require(generalId is Int) { "General position general ID must be a non-null integer" }
        val revision = rs.getObject("revision")
        require(revision is Long) { "General position revision must be a non-null bigint" }
        val nodeId = requireNotNull(rs.getString("node_id"))
        val node = when (val nodeKind = requireNotNull(rs.getString("node_kind"))) {
            "LAND_PROVINCE" -> StrategicNodeRef.LandProvince(nodeId)
            "WATER_ZONE" -> StrategicNodeRef.WaterZone(nodeId)
            else -> throw IllegalArgumentException("Unknown strategic node kind: $nodeKind")
        }
        val siteId = rs.getString("battlefield_id")
        val catalogHash = rs.getString("battlefield_catalog_hash")
        val returnCityId = rs.getObject("battlefield_return_city_id")
        val battlefield = if (siteId == null && catalogHash == null && returnCityId == null) null else {
            require(siteId != null && catalogHash != null && returnCityId is Int) { "Partial battlefield presence" }
            BattlefieldPresence(siteId, catalogHash, returnCityId)
        }
        return GeneralPositionState(
            topologyRevision = requireNotNull(rs.getString("topology_revision")),
            topologyHash = requireNotNull(rs.getString("topology_hash")),
            generalId = generalId,
            node = node,
            revision = revision,
            battlefield = battlefield,
        )
    }
}

data class GeneralPositionWriteRow(
    val expectedRevision: Long?, val state: GeneralPositionState, val delete: Boolean = false,
) {
    init {
        require(if (delete) expectedRevision != null && expectedRevision > 0 && state.revision >= expectedRevision
            else expectedRevision == null || (expectedRevision > 0 && state.revision > expectedRevision)) {
            "General position write must advance the expected persisted revision"
        }
    }
}

/** Deep-immutable ordered payload that can be retained unchanged after rollback. */
class GeneralPositionWriteBatch(rows: List<GeneralPositionWriteRow> = emptyList()) :
    java.util.AbstractList<GeneralPositionWriteRow>() {
    private val rows = Collections.unmodifiableList(ArrayList(rows))

    init {
        require(this.rows.map { it.state.generalId }.distinct().size == this.rows.size) {
            "Duplicate general position write general"
        }
        require(this.rows.map { it.state.topologyRevision to it.state.topologyHash }.distinct().size <= 1) {
            "General position batch cannot mix topology pins"
        }
    }

    override val size: Int get() = rows.size
    override fun get(index: Int): GeneralPositionWriteRow = rows[index]
}

class StaleGeneralPositionException(worldId: Int, generalId: Int, expectedRevision: Long?) :
    IllegalStateException(
        "General position CAS mismatch: worldId=$worldId generalId=$generalId " +
            "expectedRevision=$expectedRevision; reload required",
    )
