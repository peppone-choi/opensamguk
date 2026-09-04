package opensamguk.infra.persistence

import opensamguk.logic.world.WaterBlockadeState
import opensamguk.logic.world.WaterControlState
import java.sql.ResultSet
import java.util.Collections

/** Shared strict read codec for cold boot and read-only API projections. No ownership inference. */
object WaterControlRowCodec {
    fun decode(rs: ResultSet): WaterControlState {
        val controller = rs.getObject("controlling_nation_id")
        require(controller == null || controller is Long) { "Water controller must be a nullable bigint" }
        return WaterControlState(
            requireNotNull(rs.getString("topology_revision")), requireNotNull(rs.getString("topology_hash")),
            requireNotNull(rs.getString("water_zone_id")), controller as Long?,
            decodeContestingNationIds(requireNotNull(rs.getString("contesting_nation_ids"))),
            WaterBlockadeState.valueOf(requireNotNull(rs.getString("blockade_state"))), rs.getLong("revision"),
        )
    }

    fun decodeContestingNationIds(json: String): List<Long> {
        val text = json.trim()
        require(text.startsWith('[') && text.endsWith(']')) { "Contesting IDs must be a JSON integer array" }
        val body = text.substring(1, text.length - 1).trim()
        if (body.isEmpty()) return emptyList()
        val ids = body.split(',').map { token ->
            val number = token.trim()
            require(number.matches(Regex("[1-9][0-9]*"))) { "Contesting IDs must be positive integer tokens" }
            requireNotNull(number.toLongOrNull()) { "Contesting ID exceeds bigint" }
        }
        require(ids == ids.distinct().sorted()) { "Persisted contesting IDs must be sorted and unique" }
        return Collections.unmodifiableList(ids)
    }
}

data class WaterControlWriteRow(val expectedRevision: Long?, val state: WaterControlState) {
    init {
        require(expectedRevision == null || (expectedRevision > 0 && state.revision > expectedRevision)) {
            "Water control write must advance the expected persisted revision"
        }
    }
}

/** Deep-immutable typed payload, safe to retain unchanged across a rolled-back flush. */
class WaterControlWriteBatch(rows: List<WaterControlWriteRow> = emptyList()) : java.util.AbstractList<WaterControlWriteRow>() {
    private val rows = Collections.unmodifiableList(ArrayList(rows))
    init {
        require(this.rows.map { it.state.waterZoneId }.distinct().size == this.rows.size) { "Duplicate water control write zone" }
        require(this.rows.map { it.state.topologyRevision to it.state.topologyHash }.distinct().size <= 1) {
            "Water control batch cannot mix topology pins"
        }
    }
    override val size: Int get() = rows.size
    override fun get(index: Int): WaterControlWriteRow = rows[index]
}

class StaleWaterControlException(worldId: Int, zoneId: String, expectedRevision: Long?) : IllegalStateException(
    "Water control CAS mismatch: worldId=$worldId zoneId=$zoneId expectedRevision=$expectedRevision; reload required",
)
