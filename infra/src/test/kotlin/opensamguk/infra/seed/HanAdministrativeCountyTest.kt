package opensamguk.infra.seed

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.core.JsonToken
import java.nio.file.Path
import kotlin.test.*

class HanAdministrativeCountyTest {
    private val mapper = ObjectMapper()
    @Test fun `pinned 1133 archive exposes only administrative counties`() {
        val projection = WorldArtifactsResolver(Path.of("..")).artifacts(opensamguk.logic.world.WorldMapVariant.V3_1133).projection
        assertEquals(1022, projection.administrativeCountyIds.size)
        assertTrue(1 in projection.administrativeCountyIds)
        assertFalse(1047 in projection.administrativeCountyIds)
        assertFalse(1133 in projection.administrativeCountyIds)
        assertFailsWith<UnsupportedOperationException> { (projection.administrativeCountyIds as MutableSet).clear() }
    }
    @Test fun `all registered archives keep county identities inside their selected roster`() {
        for (variant in opensamguk.logic.world.WorldMapVariant.entries) {
            // This audit only needs one release at a time. Holding every
            // fourfold-grid bundle and parsing its full terrain tree exceeds
            // the test worker heap without strengthening the assertion.
            val artifact = WorldArtifactsResolver(Path.of("..")).artifacts(variant)
            val projection = artifact.projection
            val bundledCityIds = MapJson.loadCityDetails(artifact.artifactBytes(
                "infra/src/main/resources/map/han-world-v3.json").toString(Charsets.UTF_8))
                .mapTo(linkedSetOf()) { it.id }
            assertEquals(bundledCityIds, projection.bindingsByCityId.keys)
            assertTrue(projection.administrativeCountyIds.all { it in projection.bindingsByCityId })
            val hasJurisdictions = mapper.factory.createParser(
                artifact.artifactBytes("data/map/han-tiles.json")
            ).use { parser ->
                var found = false
                while (parser.nextToken() != null) {
                    if (parser.currentToken == JsonToken.FIELD_NAME && parser.text == "jurisdictionRecords") {
                        found = true
                        break
                    }
                }
                found
            }
            if (!hasJurisdictions) assertTrue(projection.administrativeCountyIds.isEmpty())
        }
    }
    @Test fun `missing legacy classification grants no county capability`() {
        assertNull(StrategicTopologyJson.administrativeCountySeats(mapper.readTree("{}"), setOf("a")))
    }
    @Test fun `only COUNTY classifies and malformed identity fails closed`() {
        fun read(rows: String) = StrategicTopologyJson.administrativeCountySeats(
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
