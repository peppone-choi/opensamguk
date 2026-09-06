package opensamguk.infra.persistence

import opensamguk.logic.world.ProvinceControlState
import java.sql.ResultSet
import java.util.Collections

/** Strict persistence boundary for campaign-owned province control. */
object ProvinceControlRowCodec {
    fun decode(rs: ResultSet): ProvinceControlState {
        val nationId = rs.getObject("nation_id")
        require(nationId is Int) { "Province control nation ID must be a non-null integer" }
        val revision = rs.getObject("revision")
        require(revision is Long) { "Province control revision must be a non-null bigint" }
        return ProvinceControlState(
            topologyRevision = requireNotNull(rs.getString("topology_revision")),
            topologyHash = requireNotNull(rs.getString("topology_hash")),
            provinceId = requireNotNull(rs.getString("province_id")),
            nationId = nationId,
            revision = revision,
        )
    }
}

data class ProvinceControlWriteRow(val expectedRevision: Long?, val state: ProvinceControlState) {
    init {
        require(expectedRevision == null || (expectedRevision > 0 && state.revision > expectedRevision)) {
            "Province control write must advance the expected persisted revision"
        }
    }
}

/** Deep-immutable ordered payload that can be retained unchanged after rollback. */
class ProvinceControlWriteBatch(rows: List<ProvinceControlWriteRow> = emptyList()) :
    java.util.AbstractList<ProvinceControlWriteRow>() {
    private val rows = Collections.unmodifiableList(ArrayList(rows))

    init {
        require(this.rows.map { it.state.provinceId }.distinct().size == this.rows.size) {
            "Duplicate province control write province"
        }
        require(this.rows.map { it.state.topologyRevision to it.state.topologyHash }.distinct().size <= 1) {
            "Province control batch cannot mix topology pins"
        }
    }

    override val size: Int get() = rows.size
    override fun get(index: Int): ProvinceControlWriteRow = rows[index]
}

class StaleProvinceControlException(worldId: Int, provinceId: String, expectedRevision: Long?) :
    IllegalStateException(
        "Province control CAS mismatch: worldId=$worldId provinceId=$provinceId " +
            "expectedRevision=$expectedRevision; reload required",
    )
