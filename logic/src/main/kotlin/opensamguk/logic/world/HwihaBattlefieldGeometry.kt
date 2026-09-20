package opensamguk.logic.world

import java.util.Collections

/** Source-cell geometry only: neither deployment permission nor a resolved battle. */
class HwihaBattlefieldGeometry private constructor(
    val provinceId: String,
    val topologyRevision: String,
    val topologyHash: String,
    val tilesContentHash: String,
    val originCol: Int,
    val originRow: Int,
    val width: Int,
    val height: Int,
    cells: List<Cell>,
) {
    data class Position(val col: Int, val row: Int)
    data class Cell(val position: Position, val source: HanProvinceCell, val terrain: String)

    val cells: List<Cell> = Collections.unmodifiableList(ArrayList(cells))
    private val byPosition = this.cells.associateBy { it.position }

    /** Holes and out-of-bounds coordinates are absent, never manufactured terrain. */
    fun cellAt(position: Position): Cell? = byPosition[position]

    /** Geometric adjacency includes obstacles; movement rules must assess terrain separately. */
    fun neighbors(position: Position): List<Cell> {
        if (position !in byPosition) return emptyList()
        return listOf(
            Position(position.col, position.row - 1),
            Position(position.col - 1, position.row),
            Position(position.col + 1, position.row),
            Position(position.col, position.row + 1),
        ).mapNotNull(byPosition::get)
    }

    /** Physical shared-border candidates only; this does not validate a strategic route. */
    fun borderFacing(index: HanProvinceCellIndex, approachProvinceId: String): List<Cell> {
        require(index.topologyRevision == topologyRevision && index.topologyHash == topologyHash &&
            index.tilesContentHash == tilesContentHash) { "Battlefield source pins differ" }
        require(approachProvinceId != provinceId) { "Approach must be a different province" }
        val approach = index.cellsOf(approachProvinceId).mapTo(hashSetOf()) { Position(it.col, it.row) }
        return cells.filter { cell ->
            val (col, row) = cell.source
            Position(col, row - 1) in approach || Position(col - 1, row) in approach ||
                Position(col + 1, row) in approach || Position(col, row + 1) in approach
        }
    }

    companion object {
        const val RULE_VERSION = 1

        /** Keep every owned source cell, including water and disconnected components. */
        fun extract(index: HanProvinceCellIndex, provinceId: String): HwihaBattlefieldGeometry {
            val source = index.cellsOf(provinceId)
            require(source.isNotEmpty()) { "Province has no source cells" }
            val col = source.minOf { it.col }
            val row = source.minOf { it.row }
            return HwihaBattlefieldGeometry(
                provinceId, index.topologyRevision, index.topologyHash, index.tilesContentHash,
                col, row, source.maxOf { it.col } - col + 1, source.maxOf { it.row } - row + 1,
                source.map { Cell(Position(it.col - col, it.row - row), it, index.terrainLegend.getValue(it.terrainCode)) },
            )
        }
    }
}
