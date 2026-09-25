package opensamguk.logic.world

import java.util.Collections

data class StrategicCellRun(val row: Int, val startCol: Int, val endCol: Int)

class StrategicWaterGeometry(
    val id: String,
    val terrainCode: Int,
    val cellCount: Int,
    cellRuns: List<StrategicCellRun>,
) {
    val cellRuns: List<StrategicCellRun> = Collections.unmodifiableList(ArrayList(cellRuns))
}

data class StrategicRoadGate(
    val edgeId: String,
    val fromRow: Int,
    val fromCol: Int,
    val toRow: Int,
    val toCol: Int,
    val terrainCost: Int,
    val initiallyBuilt: Boolean,
    val buildable: Boolean = true,
    val overviewTrunk: Boolean = false,
    val historicalRouteIds: List<String> = emptyList(),
    val fortCells: List<StrategicFortCell> = emptyList(),
    val fromTrail: List<List<Int>> = emptyList(),
    val toTrail: List<List<Int>> = emptyList(),
)

data class StrategicFortCell(val provinceId: String, val row: Int, val col: Int)

/** Display-only evidence retained by the validated loader. It never changes routing or land IDs. */
class StrategicMapPresentation(
    val cols: Int,
    val rows: Int,
    val baseTilesSha256: String,
    geometries: List<StrategicWaterGeometry>,
    zoneConnections: Map<String, String>,
    roadGates: List<StrategicRoadGate> = emptyList(),
) {
    val geometries: List<StrategicWaterGeometry> = Collections.unmodifiableList(ArrayList(geometries))
    val zoneConnections: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(zoneConnections))
    val roadGates: List<StrategicRoadGate> = Collections.unmodifiableList(ArrayList(roadGates))
}
