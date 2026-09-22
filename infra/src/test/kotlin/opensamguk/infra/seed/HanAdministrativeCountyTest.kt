package opensamguk.infra.seed

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Path
import kotlin.test.*

class HanAdministrativeCountyTest {
    private val mapper = ObjectMapper()
    @Test fun `pinned 1133 archive exposes only administrative counties`() {
        val projection = HanWorldArtifactsResolver(Path.of("..")).artifacts(opensamguk.logic.world.HanWorldVariant.V3_1133).projection
        assertEquals(1022, projection.administrativeCountyIds.size)
        assertTrue(1 in projection.administrativeCountyIds)
        assertFalse(1047 in projection.administrativeCountyIds)
        assertFalse(1133 in projection.administrativeCountyIds)
        assertFailsWith<UnsupportedOperationException> { (projection.administrativeCountyIds as MutableSet).clear() }
    }
    @Test fun `all registered archives keep county identities inside their selected roster`() {
        val resolver = HanWorldArtifactsResolver(Path.of(".."))
        for (variant in opensamguk.logic.world.HanWorldVariant.entries) {
            val artifact = resolver.artifacts(variant)
            val projection = artifact.projection
            assertEquals(variant.cityCount, projection.bindingsByCityId.size)
            assertTrue(projection.administrativeCountyIds.all { it in projection.bindingsByCityId })
            val tiles = mapper.readTree(artifact.artifactBytes("data/map/han-tiles.json"))
            if (!tiles.has("jurisdictionRecords")) assertTrue(projection.administrativeCountyIds.isEmpty())
        }
    }
    @Test fun `missing legacy classification grants no county capability`() {
        assertNull(HanStrategicTopologyJson.administrativeCountySeats(mapper.readTree("{}"), setOf("a")))
    }
    @Test fun `only COUNTY classifies and malformed identity fails closed`() {
        fun read(rows: String) = HanStrategicTopologyJson.administrativeCountySeats(
            mapper.readTree("""{"jurisdictionRecords":$rows}"""), setOf("a", "b", "c"))
        assertEquals(mapOf("a" to true, "b" to false, "c" to false), read("""[
            {"id":"1","seatPlaceId":"a","kind":"COUNTY"},
            {"id":"2","seatPlaceId":"b","kind":"STRATEGIC_SITE"},
            {"id":"3","seatPlaceId":"c","kind":"EXTERNAL_SETTLEMENT"}]"""))
        for (rows in listOf("null", """[{"id":"1","seatPlaceId":"z","kind":"COUNTY"}]""",
            """[{"id":"1","seatPlaceId":"a","kind":"UNKNOWN"}]""",
            """[{"id":"1","seatPlaceId":"a","kind":"COUNTY"},{"id":"2","seatPlaceId":"a","kind":"COUNTY"}]""")) {
            assertFailsWith<IllegalArgumentException> { read(rows) }
        }
    }
}
