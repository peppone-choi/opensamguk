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
    fun `scenario_1010 loads general and general_ex as 678 generals`() {
        val scenario = ScenarioJson.loadScenario(readResource("scenario/scenario_1010.json"))

        assertEquals(678, scenario.generals.size)
    }

    @Test
    fun `scenario event tuples and initial events retain wire order`() {
        val scenario = ScenarioJson.loadScenario(readResource("scenario/scenario_1010.json"))

        assertEquals(false, scenario.ignoreDefaultEvents)
        assertEquals(1, scenario.events.size)
        assertEquals("destroy_nation", scenario.events.single().target)
        assertEquals(1000, scenario.events.single().priority)
        assertEquals(2, scenario.events.single().actions.size)
        assertEquals(1, scenario.initialEvents.size)
        assertEquals(2, scenario.initialEvents.single().actions.size)
    }

    @Test
    fun `scenario 911 and 912 load their event rows`() {
        val scenario911 = ScenarioJson.loadScenario(readResource("scenario/scenario_911.json"))
        val scenario912 = ScenarioJson.loadScenario(readResource("scenario/scenario_912.json"))

        assertEquals(5, scenario911.events.size)
        assertEquals(9, scenario912.events.size)
        assertEquals(false, scenario911.ignoreDefaultEvents)
        assertEquals(false, scenario912.ignoreDefaultEvents)
    }

    @Test
    fun `scenario 910 honors ignoreDefaultEvents`() {
        val scenario = ScenarioJson.loadScenario(readResource("scenario/scenario_910.json"))

        assertEquals(true, scenario.ignoreDefaultEvents)
        assertEquals(19, scenario.events.size)
        assertEquals(0, scenario.initialEvents.size)
    }

    @Test
    fun `scenario local tuple politics and charm are decoded from positions fourteen and fifteen`() {
        val json = """
            {
              "title": "local",
              "startYear": 180,
              "map": {"mapName": "che"},
              "const": {},
              "nation": [],
              "general": [[1,"장수A",null,0,null,51,52,53,0,150,210,null,null,null,61,62]],
              "general_ex": [[2,"장수B",null,0,null,41,42,43,0,151,211,null,null,null,71,72]],
              "diplomacy": []
            }
        """.trimIndent()

        val scenario = ScenarioJson.loadScenario(json)

        assertEquals(2, scenario.generals.size)
        assertEquals(61, scenario.generals[0].politics)
        assertEquals(62, scenario.generals[0].charm)
        assertEquals(71, scenario.generals[1].politics)
        assertEquals(72, scenario.generals[1].charm)
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
