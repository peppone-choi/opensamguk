package opensamguk.infra.seed

import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
    fun `RTK14 lifecycle slots decode while legacy tuples keep null lifecycle metadata`() {
        val scenario = ScenarioJson.loadScenario(
            """
            {
              "title": "rtk14",
              "startYear": 200,
              "map": {"mapName": "che"},
              "const": {},
              "nation": [],
              "general": [
                [1,"Legacy",null,0,null,51,52,53,0,180,240,null,null,null,61,62],
                [2,"RTK14",null,0,null,41,42,43,0,181,241,null,null,null,71,72,205,1001,"남",60,37,333,"유가",true]
              ],
              "general_ex": [],
              "diplomacy": []
            }
            """.trimIndent(),
        )

        val legacy = scenario.baseGenerals[0]
        assertNull(legacy.appearanceYear)
        assertNull(legacy.officerNumber)
        assertNull(legacy.gender)
        assertNull(legacy.lifespan)
        assertNull(legacy.activityYears)
        assertNull(legacy.total)
        assertNull(legacy.ideology)
        assertEquals(false, legacy.rtk14Added)

        val rtk14 = scenario.baseGenerals[1]
        assertEquals(205, rtk14.appearanceYear)
        assertEquals(1001, rtk14.officerNumber)
        assertEquals("남", rtk14.gender)
        assertEquals(60, rtk14.lifespan)
        assertEquals(37, rtk14.activityYears)
        assertEquals(333, rtk14.total)
        assertEquals("유가", rtk14.ideology)
        assertEquals(true, rtk14.rtk14Added)
        assertEquals(24, rtk14.rawTuple.size)
        assertEquals(205, rtk14.rawTuple[16])
        assertEquals("유가", rtk14.rawTuple[22])
        assertEquals(true, rtk14.rawTuple[23])
    }

    @Test
    fun `RTK14 appended rows follow all existing sections in seed and init order`() {
        val scenario = ScenarioJson.loadScenario(
            """
            {
              "title": "rtk14 order",
              "startYear": 200,
              "map": {"mapName": "che"},
              "const": {},
              "nation": [],
              "general": [
                [1,"BaseLegacy",null,0,null,1,1,1,0,180,240,null,null],
                [1,"AppendedRtk14",null,0,null,1,1,1,0,180,240,null,null,null,50,50,200,1,"남",60,40,300,"유가",true]
              ],
              "general_ex": [[1,"ExtendedLegacy",null,0,null,1,1,1,0,180,240,null,null]],
              "general_neutral": [[1,"NeutralLegacy",null,0,null,1,1,1,0,180,240,null,null]],
              "diplomacy": []
            }
            """.trimIndent(),
        )

        assertEquals(
            listOf("BaseLegacy", "ExtendedLegacy", "NeutralLegacy", "AppendedRtk14"),
            scenario.initGenerals().map { it.name },
        )
        assertEquals(
            listOf("BaseLegacy", "ExtendedLegacy", "NeutralLegacy", "AppendedRtk14"),
            scenario.seedGenerals(extendedGeneral = true).map { it.name },
        )
        assertEquals(
            listOf("BaseLegacy", "NeutralLegacy", "AppendedRtk14"),
            scenario.seedGenerals(extendedGeneral = false).map { it.name },
        )
    }

    @Test
    fun `scenario general_neutral keeps npc type nation name resolution and raw tuple`() {
        val json = """
            {
              "title": "neutral",
              "startYear": 180,
              "map": {"mapName": "che"},
              "const": {},
              "nation": [["후한", "#fff", 0, 0, "", 0, "유가", 1, ["낙양"]]],
              "general": [[1,"소속",null,"후한",null,51,52,53,0,150,210,null,null]],
              "general_ex": [],
              "general_neutral": [[0,"재야",null,"후한",null,61,62,63,0,170,230,null,null,"대사",71,72]],
              "diplomacy": []
            }
        """.trimIndent()

        val scenario = ScenarioJson.loadScenario(json)

        assertEquals(2, scenario.generals.size)
        assertEquals(1, scenario.baseGenerals.single().nationId)
        assertEquals(1, scenario.generalNeutral.single().nationId)
        assertEquals(6, scenario.generalNeutral.single().npcType)
        assertEquals(16, scenario.generalNeutral.single().rawTuple.size)
        assertEquals("대사", scenario.generalNeutral.single().rawTuple[13])
        assertEquals(71, scenario.generalNeutral.single().politics)
        assertEquals(72, scenario.generalNeutral.single().charm)
    }

    @Test
    fun `scenario icon environment preserves iconPath and stored_icons`() {
        val json = """
            {
              "title": "icons",
              "startYear": 180,
              "iconPath": "custom",
              "stored_icons": {
                ".": {"1001": "numeric.png"},
                "custom": {"장수A": "named.png"}
              },
              "map": {"mapName": "che"},
              "const": {},
              "nation": [],
              "general": [],
              "general_ex": [],
              "diplomacy": []
            }
        """.trimIndent()

        val scenario = ScenarioJson.loadScenario(json)

        assertEquals("custom", scenario.iconPath)
        val dotIcons = scenario.storedIcons["."] as Map<*, *>
        val customIcons = scenario.storedIcons["custom"] as Map<*, *>
        assertEquals("numeric.png", dotIcons["1001"])
        assertEquals("named.png", customIcons["장수A"])
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
