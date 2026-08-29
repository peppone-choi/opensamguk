package opensamguk.infra.seed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MapJsonTest {

    @Test
    fun `world v2 resolves to the committed Han gameplay resource`() {
        assertEquals("han", MapJson.resourceCode("han-world-v2"))
        assertEquals("han-780-v1", MapJson.resourceCode("han-780-v1"))
        assertEquals("che", MapJson.resourceCode("che"))
    }

    @Test
    fun `map coordinates retain Han administrative metadata`() {
        val data = MapJson.loadMap(
            """
            {
              "width": 700,
              "height": 610,
              "cities": [{
                "id": 1,
                "name": "장안",
                "x": 280,
                "y": 221,
                "meta": {
                  "ju": "사예",
                  "jun": "경조윤",
                  "seat": "장안현",
                  "isSeat": true
                }
              }]
            }
            """.trimIndent(),
        )

        val city = data.cities.single()
        assertEquals("사예", city.regionName)
        assertEquals("경조윤", city.commanderyName)
        assertTrue(city.isCommanderySeat)
    }
}
