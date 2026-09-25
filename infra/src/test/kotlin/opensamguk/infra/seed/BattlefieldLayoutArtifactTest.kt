package opensamguk.infra.seed

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import opensamguk.logic.world.*
import opensamguk.logic.world.BattlefieldGeometry.Position

/** Measures the selected source bundle; unavailable layouts are evidence, not test failures. */
class BattlefieldLayoutArtifactTest {
    @Test fun `all selected land edges in both directions retain source geometry and valid deployment zones`() {
        val bundle = HanWorldArtifactsResolver(Path.of("..")).artifacts(HanWorldVariant.V3_1133)
        val topology = bundle.projection.topology
        val index = bundle.provinceCells
        val edges = topology.traversalEdges.filter { it.mode in setOf(TraversalMode.LAND, TraversalMode.FORD, TraversalMode.BRIDGE) }.sortedBy { it.id }
        assertTrue(edges.isNotEmpty())
        val rows = mutableListOf<Map<String, Any>>()
        for (edge in edges) {
            val a = (edge.from as StrategicNodeRef.LandProvince).id
            val b = (edge.to as StrategicNodeRef.LandProvince).id
            for ((approach, province) in listOf(a to b, b to a)) {
                val geometry = BattlefieldGeometry.extract(index, province)
                assertEquals(index.cellsOf(province), geometry.cells.map { it.source })
                assertEquals(index.topologyHash, geometry.topologyHash)
                assertEquals(index.tilesContentHash, geometry.tilesContentHash)
                val row = linkedMapOf<String, Any>("edgeId" to edge.id, "mode" to edge.mode.name,
                    "approach" to approach, "province" to province, "sourceCells" to geometry.cells.size)
                when (val result = BattlefieldLayout.prepare(index, province, approach)) {
                    is BattlefieldLayout.Result.Unavailable -> row["status"] = result.reason.name
                    is BattlefieldLayout.Result.Ready -> {
                        val layout = result.layout
                        assertEquals(geometry.cells, layout.geometry.cells)
                        val selected = layout.distancesFromEntry.keys
                        assertTrue(layout.attackerZone.isNotEmpty() && layout.defenderZone.isNotEmpty())
                        assertTrue(layout.attackerZone.toSet().intersect(layout.defenderZone.toSet()).isEmpty())
                        assertTrue(selected.containsAll(layout.attackerZone + layout.defenderZone))
                        selected.forEach { assertTrue(BattlefieldLayout.isLandPassable(geometry.cellAt(it)!!.terrain)) }
                        val reached = hashSetOf<Position>()
                        val queue = ArrayDeque<Position>()
                        val approachCells = index.cellsOf(approach).filter { BattlefieldLayout.isLandPassable(index.terrainLegend.getValue(it.terrainCode)) }
                            .mapTo(hashSetOf()) { Position(it.col, it.row) }
                        // Independent physical entry calculation and BFS, not the returned distances as authority.
                        for (position in selected) {
                            val source = geometry.cellAt(position)!!.source
                            if (listOf(Position(source.col-1,source.row),Position(source.col+1,source.row),
                                    Position(source.col,source.row-1),Position(source.col,source.row+1)).any { it in approachCells }) {
                                reached.add(position); queue.add(position)
                            }
                        }
                        assertTrue(queue.isNotEmpty())
                        val distances = queue.associateWith { 0 }.toMutableMap()
                        while (queue.isNotEmpty()) {
                            val current = queue.removeFirst()
                            for (neighbor in geometry.neighbors(current)) if (neighbor.position in selected && reached.add(neighbor.position)) {
                                distances[neighbor.position] = distances.getValue(current)+1
                                queue.add(neighbor.position)
                            }
                        }
                        assertEquals(selected, reached)
                        assertEquals(layout.distancesFromEntry, distances)
                        // No passable neighboring cell may have been dropped from the selected component.
                        selected.forEach { position -> geometry.neighbors(position).filter { BattlefieldLayout.isLandPassable(it.terrain) }
                            .forEach { assertTrue(it.position in selected) } }
                        row["status"] = "READY"
                        row["connectedCells"] = selected.size
                        row["attackerCells"] = layout.attackerZone.size
                        row["defenderCells"] = layout.defenderZone.size
                        row["depth"] = distances.values.max()
                    }
                }
                rows.add(row)
            }
        }
        assertEquals(edges.size*2, rows.size)
        val evidence = linkedMapOf<String, Any>("ruleVersion" to BattlefieldLayout.RULE_VERSION,
            "scope" to "Selected V3_1133 LAND/FORD/BRIDGE edges, both geometric directions; not combat readiness or route permission",
            "topologyRevision" to topology.topologyRevision, "topologyHash" to topology.contentHash,
            "tilesHash" to index.tilesContentHash, "provinceCount" to index.provinceIds.size,
            "edgeCount" to edges.size, "directionCount" to rows.size,
            "counts" to rows.groupingBy { it.getValue("status") }.eachCount().toSortedMap(compareBy { it.toString() }),
            "cases" to rows)
        val output = Path.of("build/reports/battlefield-layout/source-sweep.json")
        Files.createDirectories(output.parent)
        ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), evidence)
        println("Battlefield source sweep: " + evidence["counts"])
    }
}
