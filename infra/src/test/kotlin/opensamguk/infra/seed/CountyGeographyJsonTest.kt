package opensamguk.infra.seed

import java.nio.file.Path
import kotlin.test.*
import opensamguk.logic.world.HanWorldVariant

/** Reads the pinned 1133 bundle: every administrative county gets the runtime map's commandery, never an inferred one. */
class CountyGeographyJsonTest {
    @Test fun `administrative counties map to their runtime commandery and a unique jurisdiction`() {
        val bundle = HanWorldArtifactsResolver(Path.of("..")).artifacts(HanWorldVariant.V3_1133)
        val geography = CountyGeographyJson.load(bundle)
        val admin = bundle.projection.administrativeCountyIds
        assertEquals(admin, geography.byCounty.keys, "every administrative county carries meta.junCh")
        assertTrue(geography.byCounty.values.all { it.jurisdictionId != null })
        // Commandery groups are non-trivial: many counties share one commandery key.
        val groups = geography.byCounty.values.groupBy { it.commanderyId }
        assertTrue(groups.size in 100..admin.size, "${groups.size} commanderies")
        assertTrue(groups.values.any { it.size > 1 })
        // A county's jurisdiction resolves back to itself (hometown lookup is unambiguous when it answers).
        for (place in geography.byCounty.values) {
            val back = geography.countyOfJurisdiction(place.jurisdictionId!!)
            assertTrue(back == null || back == place.countyId)
        }
    }

    @Test fun `non administrative cities and cities without a commandery are left out`() {
        val map = """{"cities":[{"id":1,"meta":{"junCh":"甲郡","jun":"갑군"},"provinceId":0},
            {"id":2,"meta":{"jun":"을군"},"provinceId":1},{"id":3,"meta":{"junCh":"丙郡"},"provinceId":1}]}""".toByteArray()
        val tiles = """{"provinceRecords":[{"jurisdictionId":"j1"},{"jurisdictionId":"j2"}]}""".toByteArray()
        val geography = CountyGeographyJson.parse(map, tiles, setOf(1, 2))
        assertEquals(setOf(1), geography.byCounty.keys)
        assertEquals("甲郡", geography.commanderyOf(1))
        assertEquals(1, geography.countyOfJurisdiction("j1"))
    }
}
