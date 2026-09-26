package opensamguk.infra.seed

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Path
import opensamguk.logic.world.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class LandMarchMetricIntegrationTest {
    private val resolver = WorldArtifactsResolver(Path.of(".."))

    @Test fun `current pinned bundle agrees with independent Python distances including rough terrain`() {
        val bundle = resolver.artifacts(WorldMapVariant.V3_1133)
        val topology = bundle.projection.topology
        val before = topology.traversalEdges.associate { it.id to it.movementCost }
        val snapshot = bundle.landMarchMetrics
        val reference = ObjectMapper().readTree(javaClass.getResourceAsStream("/land-march-python-reference.json"))
        assertEquals(reference["tilesSha256"].asText(), snapshot.tilesHash)
        assertEquals(topology.contentHash, snapshot.topologyHash)
        assertEquals(6, reference["rows"].size())
        for (row in reference["rows"]) {
            val endpoints = setOf(row["fromProvince"].asText(), row["toProvince"].asText())
            val edges = topology.traversalEdges.filter { edge ->
                LandMarchMetricSnapshot.supports(edge) && setOf(
                    (edge.from as StrategicNodeRef.LandProvince).id,
                    (edge.to as StrategicNodeRef.LandProvince).id) == endpoints
            }
            assertTrue(edges.isNotEmpty(), "Reference pair must exist in the executable topology: $endpoints")
            for (edge in edges) {
                val metric = snapshot.edgesById.getValue(edge.id)
                assertEquals(row["distanceMm"].asLong(), metric.distanceMm, edge.id)
                assertEquals(row["costMm"].asLong(), metric.costMm, edge.id)
            }
        }
        assertEquals(before, topology.traversalEdges.associate { it.id to it.movementCost })
        assertSame(snapshot, bundle.landMarchMetrics)
        assertEquals(topology.traversalEdges.filter(LandMarchMetricSnapshot::supports).map { it.id }.toSet(), snapshot.edgesById.keys)
    }

    @Test fun `historical selection uses its archived geometry instead of checkout tiles`() {
        val old = resolver.artifacts(WorldMapVariant.V3_832)
        val current = resolver.artifacts(WorldMapVariant.V3_1133)
        val snapshot = old.landMarchMetrics
        assertEquals(old.projection.topology.contentHash, snapshot.topologyHash)
        assertNotEquals(current.landMarchMetrics.tilesHash, snapshot.tilesHash)
        assertFailsWith<IllegalArgumentException> {
            LandMarchMetricJson.load(old.projection.topology,
                current.artifactBytes(LandMarchMetricSnapshot.TILES_PATH))
        }
        assertEquals(snapshot.contentHash, LandMarchMetricJson.load(old.projection.topology,
            old.artifactBytes(LandMarchMetricSnapshot.TILES_PATH)).contentHash)
    }
}
