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

    private fun readResource(path: String): String {
        val stream = javaClass.classLoader.getResourceAsStream(path)
            ?: error("resource not found: $path")
        return stream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
    }
}
