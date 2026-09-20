package opensamguk.logic.world

import java.util.Collections

/** A source raster cell, not a tactical movement or elevation rule. */
data class HanProvinceCell(val col: Int, val row: Int, val terrainCode: Char)

/** Immutable selected-artifact cell inventory. Disconnected components and terrain are preserved. */
class HanProvinceCellIndex(
    val topologyRevision: String,
    val topologyHash: String,
    val tilesContentHash: String,
    val cols: Int,
    val rows: Int,
    terrainLegend: Map<Char, String>,
    cellsByProvince: Map<String, List<HanProvinceCell>>,
) {
    val terrainLegend: Map<Char, String> = Collections.unmodifiableMap(LinkedHashMap(terrainLegend))
    private val cellsByProvince: Map<String, List<HanProvinceCell>> = Collections.unmodifiableMap(
        cellsByProvince.mapValuesTo(linkedMapOf()) { (_, cells) -> Collections.unmodifiableList(ArrayList(cells)) })
    val provinceIds: Set<String> = Collections.unmodifiableSet(LinkedHashSet(this.cellsByProvince.keys))

    init {
        require(topologyRevision.isNotBlank())
        require(listOf(topologyHash, tilesContentHash).all { it.matches(Regex("[0-9a-f]{64}")) })
        require(cols > 0 && rows > 0 && cols.toLong() * rows <= Int.MAX_VALUE)
        require(this.terrainLegend.isNotEmpty() && this.terrainLegend.all { (code, name) -> code in '0'..'9' && name.isNotBlank() })
        require(provinceIds.isNotEmpty() && provinceIds.all { it.isNotBlank() })
        val occupied = java.util.BitSet()
        for (cells in this.cellsByProvince.values) {
            var previous = -1L
            for (cell in cells) {
                require(cell.col in 0 until cols && cell.row in 0 until rows && cell.terrainCode in this.terrainLegend)
                val offset = cell.row.toLong() * cols + cell.col
                require(offset > previous && !occupied[offset.toInt()]) { "Province cells must be unique and row-major" }
                occupied.set(offset.toInt())
                previous = offset
            }
        }
    }

    fun cellsOf(provinceId: String): List<HanProvinceCell> =
        requireNotNull(cellsByProvince[provinceId]) { "Unknown Han province identity" }
}
