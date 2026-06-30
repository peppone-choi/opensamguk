package opensamguk.infra.seed

import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals

class ScenarioJsonTest {

    @Test
    fun `scenario_1 preserves miniche map metadata`() {
        val scenario = ScenarioJson.loadScenario(readResource("scenario/scenario_1.json"))

        assertEquals("miniche", scenario.map["mapName"])
        assertEquals(0, scenario.const["joinRuinedNPCProp"])
    }

    @Test
    fun `scenario_2 preserves miniche_b map metadata`() {
        val scenario = ScenarioJson.loadScenario(readResource("scenario/scenario_2.json"))

        assertEquals("miniche_b", scenario.map["mapName"])
    }

    @Test
    fun `map_miniche_b city data keeps its own city stats`() {
        val cities = ScenarioJson.loadMapCities(readResource("map/miniche_b.json"))

        assertEquals(78, cities.size)
        assertEquals("낙양", cities[0].name)
        assertEquals(8, cities[0].level)
        assertEquals(668600, cities[0].popMax)
        assertEquals(7800, cities[0].agriMax)
    }

    private fun readResource(path: String): String {
        val stream = javaClass.classLoader.getResourceAsStream(path)
            ?: error("resource not found: $path")
        return stream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
    }
}
