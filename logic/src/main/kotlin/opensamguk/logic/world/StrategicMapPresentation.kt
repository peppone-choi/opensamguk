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

/** Display-only evidence retained by the validated loader. It never changes routing or land IDs. */
class StrategicMapPresentation(
    val cols: Int,
    val rows: Int,
    val baseTilesSha256: String,
    geometries: List<StrategicWaterGeometry>,
    zoneConnections: Map<String, String>,
) {
    val geometries: List<StrategicWaterGeometry> = Collections.unmodifiableList(ArrayList(geometries))
    val zoneConnections: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(zoneConnections))
}
