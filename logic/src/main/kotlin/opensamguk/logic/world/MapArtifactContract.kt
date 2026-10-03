package opensamguk.logic.world

/** File and field names are part of an immutable release's stored contract. */
enum class MapArtifactContract(
    val tilesPath: String,
    val sourceTilesField: String,
    val tilesHashField: String,
    val dryBoundarySource: String,
) {
    CURRENT("data/map/province-tiles.json", "sourceTiles", "tilesSha256", "province-tiles"),
    ARCHIVE("data/map/han-tiles.json", "baseHanTiles", "hanTilesSha256", "han-tiles"),
}

/** Exactly one contract must supply the terrain pin; mixed contracts are rejected. */
fun StrategicTopologySnapshot.tilesArtifactHash(): String {
    val pins = MapArtifactContract.entries.mapNotNull { artifactHashes[it.tilesPath] }
    require(pins.size == 1) { "Topology must select exactly one terrain artifact contract" }
    return pins.single()
}
