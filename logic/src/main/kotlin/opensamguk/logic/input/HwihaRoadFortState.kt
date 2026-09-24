package opensamguk.logic.input

import opensamguk.logic.world.StrategicEdgeStateSnapshot
import opensamguk.logic.world.StrategicEdgeState

/** Roadside forts are independently owned sites; county capture does not transfer them. */
data class HwihaRoadFort(
    val id: String,
    val edgeId: String,
    val provinceId: String,
    val row: Int,
    val col: Int,
    val ownerNationId: Int,
    val wall: Int,
    val garrison: Int,
    val besiegerNationId: Int? = null,
    val besiegerGeneralId: Int? = null,
    val siegeProgress: Int = 0,
) {
    init {
        require(id == siteId(edgeId, row, col) && provinceId.isNotBlank())
        require(row >= 0 && col >= 0 && ownerNationId > 0 && wall in 0..100 && garrison >= 0)
        require((besiegerNationId == null && besiegerGeneralId == null && siegeProgress == 0) ||
            (besiegerNationId != null && besiegerNationId > 0 && besiegerNationId != ownerNationId &&
                besiegerGeneralId != null && besiegerGeneralId > 0 && siegeProgress in 0..99))
    }

    companion object {
        fun siteId(edgeId: String, row: Int, col: Int) = "$edgeId@$row,$col"
    }
}

object HwihaRoadFortState {
    const val META_KEY = "hwihaRoadForts"
    private val fields = setOf("version", "forts")
    private val fortFields = setOf("id", "edgeId", "provinceId", "row", "col", "ownerNationId", "wall", "garrison",
        "besiegerNationId", "besiegerGeneralId", "siegeProgress")

    fun read(meta: Map<String, Any?>): List<HwihaRoadFort> {
        val raw = meta[META_KEY] ?: return emptyList()
        require(raw is Map<*, *> && raw.keys == fields && raw["version"] == 1) { "Invalid road fort schema" }
        val rows = raw["forts"] as? List<*> ?: invalid()
        val forts = rows.map { item ->
            val row = item as? Map<*, *> ?: invalid()
            require(row.keys == fortFields) { "Invalid road fort fields" }
            HwihaRoadFort(
                row["id"] as? String ?: invalid(), row["edgeId"] as? String ?: invalid(),
                row["provinceId"] as? String ?: invalid(), row["row"] as? Int ?: invalid(),
                row["col"] as? Int ?: invalid(), row["ownerNationId"] as? Int ?: invalid(),
                row["wall"] as? Int ?: invalid(), row["garrison"] as? Int ?: invalid(),
                row["besiegerNationId"]?.let { it as? Int ?: invalid() },
                row["besiegerGeneralId"]?.let { it as? Int ?: invalid() },
                row["siegeProgress"] as? Int ?: invalid(),
            )
        }
        require(forts == forts.sortedBy { it.id } && forts.map { it.id }.distinct().size == forts.size) {
            "Road forts must be uniquely sorted"
        }
        return forts
    }

    fun toMetaValue(forts: List<HwihaRoadFort>): Map<String, Any> = linkedMapOf(
        "version" to 1,
        "forts" to forts.sortedBy { it.id }.map { fort ->
            linkedMapOf<String, Any?>(
                "id" to fort.id, "edgeId" to fort.edgeId, "provinceId" to fort.provinceId,
                "row" to fort.row, "col" to fort.col, "ownerNationId" to fort.ownerNationId,
                "wall" to fort.wall, "garrison" to fort.garrison,
                "besiegerNationId" to fort.besiegerNationId, "besiegerGeneralId" to fort.besiegerGeneralId,
                "siegeProgress" to fort.siegeProgress,
            )
        },
    )

    /** A hostile fort seals its road for this army; the owner can still use it. */
    fun forNation(passage: StrategicEdgeStateSnapshot, forts: List<HwihaRoadFort>,
                  hostileNationIds: Set<Int>): StrategicEdgeStateSnapshot {
        val hostileEdges = forts.filter { it.ownerNationId in hostileNationIds }.mapTo(hashSetOf()) { it.edgeId }
        if (hostileEdges.isEmpty()) return passage
        return StrategicEdgeStateSnapshot(passage.topologyRevision, passage.topologyHash,
            passage.edgeStates.mapValues { (id, state) ->
                if (id in hostileEdges) StrategicEdgeState(false, state.seasonOpen, state.blockaded, state.availableCapacity)
                else state
            })
    }

    private fun invalid(): Nothing = throw IllegalArgumentException("Invalid HWIHA road fort state")
}
