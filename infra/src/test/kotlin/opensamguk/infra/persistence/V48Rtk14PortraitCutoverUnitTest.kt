package opensamguk.infra.persistence

import db.migration.V48__rtk14_portrait_cutover
import opensamguk.infra.seed.ScenarioJson
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class V48Rtk14PortraitCutoverUnitTest {
    @Test
    fun `portrait mapping accepts exactly 1000 source officers and preserves both ID bands`() {
        val mapping = V48__rtk14_portrait_cutover.portraitMappings(
            ScenarioJson.loadScenario(scenarioJson(1000)),
        )

        assertEquals(1000, mapping.size)
        assertEquals("10001.png", mapping[1])
        assertEquals("11000.png", mapping[1000])
    }

    @Test
    fun `portrait mapping rejects partial source rosters`() {
        assertFailsWith<IllegalArgumentException> {
            V48__rtk14_portrait_cutover.portraitMappings(
                ScenarioJson.loadScenario(scenarioJson(999)),
            )
        }
    }

    @Test
    fun `portrait mapping ignores a legacy scenario with no source metadata`() {
        val scenario = ScenarioJson.loadScenario(
            """{"title":"legacy","startYear":190,"map":{},"const":{},"nation":[],"general":[[0,"장수",1001,0,null,70,71,72,0,170,240,null,null]],"general_ex":[],"general_neutral":[],"diplomacy":[]}""",
        )

        assertEquals(emptyMap(), V48__rtk14_portrait_cutover.portraitMappings(scenario))
    }

    private fun scenarioJson(count: Int): String {
        val generals = (1..count).joinToString(",") { sourceNumber ->
            val stableId = 10000 + sourceNumber
            "[0,\"장수$sourceNumber\",\"$stableId.png\",0,null,70,71,72,0,170,240,null,null,null,73,74,200,$sourceNumber,\"남\",71,41,360,\"왕도\",true,false]"
        }
        return """{"title":"portrait","startYear":190,"map":{},"const":{},"nation":[],"general":[$generals],"general_ex":[],"general_neutral":[],"diplomacy":[]}"""
    }
}
