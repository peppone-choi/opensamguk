package opensamguk.infra.seed

import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScenarioJsonTest {

    @Test
    fun `scenario_1 uses the canonical Han world contract`() {
        val scenario = ScenarioJson.loadScenario(readResource("scenario/scenario_1.json"))

        assertEquals("han-world-v2", scenario.map["mapName"])
        assertEquals("han", scenario.map["unitSet"])
        assertEquals(0, scenario.const["joinRuinedNPCProp"])
    }

    @Test
    fun `scenario_2 uses the canonical Han world contract`() {
        val scenario = ScenarioJson.loadScenario(readResource("scenario/scenario_2.json"))

        assertEquals("han-world-v2", scenario.map["mapName"])
        assertEquals("han", scenario.map["unitSet"])
    }

    @Test
    fun `committed runtime scenarios preserve frozen V2 and opt into new-world-only V3 explicitly`() {
        val frozenV2Codes = setOf(
            "0", "1", "2", "900", "901", "902", "903", "905", "906", "908",
            "910", "911", "912", "913", "914",
        )
        val newWorldV3Codes = setOf(
            "1010", "1020", "1021", "1030", "1031", "1040", "1041", "1050",
            "1060", "1070", "1080", "1090", "1100", "1110", "1120",
            "9200",
        )

        for (code in frozenV2Codes + newWorldV3Codes) {
            val scenario = ScenarioJson.loadScenario(readResource("scenario/scenario_$code.json"))
            val expectedMap = if (code in newWorldV3Codes) "han-world-v3" else "han-world-v2"
            assertEquals(expectedMap, scenario.map["mapName"], "scenario_$code mapName")
            assertEquals("han", scenario.map["unitSet"], "scenario_$code unitSet")
            ScenarioImporter(
                scenario = scenario,
                cities = emptyList(),
                scenarioCode = "scenario_$code",
                extendedGeneral = false,
            ).validateSeedContract()
            ScenarioImporter(
                scenario = scenario,
                cities = emptyList(),
                scenarioCode = "scenario_$code",
                extendedGeneral = true,
            ).validateSeedContract()
        }
    }

    @Test
    fun `scenario 9200 pins Chang-an and Luoyang to stable V3 city ids`() {
        val scenario = ScenarioJson.loadScenario(readResource("scenario/scenario_9200.json"))

        assertEquals("han-world-v3", scenario.map["mapName"])
        assertEquals(listOf("46"), scenario.nations.single { it.name == "동탁" }.cities)
        assertEquals(listOf("1"), scenario.nations.single { it.name == "원소" }.cities)
        assertEquals("46", scenario.generals.single { it.name == "동탁" }.locatedCity)
        assertEquals("1", scenario.generals.single { it.name == "원소" }.locatedCity)
    }

    @Test
    fun `scenario_1010 keeps the full source roster plus the explicit emperor`() {
        val scenario = ScenarioJson.loadScenario(readResource("scenario/scenario_1010.json"))

        assertEquals(679, scenario.generals.size)
        assertEquals(setOf("유굉"), scenario.imperialGeneralNames)
        assertEquals(7, scenario.generals.single { it.name == "유굉" }.npcType)
    }

    @Test
    fun `scenario seed contract decodes literal base and extended active rosters`() {
        val scenario = ScenarioJson.loadScenario(
            """
            {
              "title": "contract",
              "startYear": 181,
              "map": {"mapName": "che"},
              "const": {},
              "seedContract": {"activeGenerals": {"base": 174, "extended": 229}},
              "nation": [],
              "general": [],
              "general_ex": [],
              "diplomacy": []
            }
            """.trimIndent(),
        )

        assertEquals(174, scenario.seedContract?.activeGenerals?.base)
        assertEquals(229, scenario.seedContract?.activeGenerals?.extended)
    }

    @Test
    fun `scenario importer rejects an active roster smaller than its JSON contract`() {
        val scenario = ScenarioJson.loadScenario(
            """
            {
              "title": "truncated",
              "startYear": 181,
              "map": {"mapName": "che"},
              "const": {},
              "seedContract": {"activeGenerals": {"base": 1, "extended": 2}},
              "nation": [],
              "general": [[1,"Only",null,0,null,1,1,1,0,160,220,null,null]],
              "general_ex": [],
              "diplomacy": []
            }
            """.trimIndent(),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            ScenarioImporter(scenario, emptyList()).validateSeedContract()
        }
        assertTrue(error.message.orEmpty().contains("expected 2 active generals, decoded 1"))
    }

    @Test
    fun `removing one eligible general fails the pre-write seed contract check`() {
        val scenario = ScenarioJson.loadScenario(
            """
            {
              "title": "one row removed",
              "startYear": 181,
              "map": {"mapName": "han-world-v3"},
              "const": {},
              "seedContract": {"activeGenerals": {"base": 2, "extended": 2}},
              "nation": [],
              "general": [[1,"남은장수",null,0,null,1,1,1,0,160,220,null,null]],
              "general_ex": [],
              "diplomacy": []
            }
            """.trimIndent(),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            ScenarioImporter(
                scenario = scenario,
                cities = emptyList(),
                extendedGeneral = false,
            ).validateSeedContract()
        }

        assertTrue(error.message.orEmpty().contains("expected 2 active generals, decoded 1"))
    }

    @Test
    fun `frozen V2 and new V3 Han worlds cannot seed without an active roster contract`() {
        for (mapName in listOf("han-world-v2", "han-world-v3")) {
            val scenario = ScenarioJson.loadScenario(
                """
                {
                  "title": "uncontracted Han scenario",
                  "startYear": 181,
                  "map": {"mapName": "$mapName"},
                  "const": {},
                  "nation": [],
                  "general": [],
                  "general_ex": [],
                  "diplomacy": []
                }
                """.trimIndent(),
            )

            val error = assertFailsWith<IllegalArgumentException> {
                ScenarioImporter(scenario, emptyList()).validateSeedContract()
            }
            assertTrue(error.message.orEmpty().contains("requires seedContract.activeGenerals"))
        }
    }

    @Test
    fun `all Han scenarios satisfy both committed active roster contracts`() {
        val codes = listOf(
            "1010", "1020", "1021", "1030", "1031", "1040", "1041", "1050",
            "1060", "1070", "1080", "1090", "1100", "1110", "1120",
        )

        for (code in codes) {
            val scenario = ScenarioJson.loadScenario(readResource("scenario/scenario_$code.json"))
            ScenarioImporter(
                scenario = scenario,
                cities = emptyList(),
                scenarioCode = "scenario_$code",
                extendedGeneral = false,
            ).validateSeedContract()
            ScenarioImporter(
                scenario = scenario,
                cities = emptyList(),
                scenarioCode = "scenario_$code",
                extendedGeneral = true,
            ).validateSeedContract()
        }
    }

    @Test
    fun `enriched roster retains every RTK14 source officer when legacy extensions are disabled`() {
        fun sourceTuple(officerNumber: Int) =
            "[0,\"RTK$officerNumber\",null,0,null,1,1,1,0,180,240,null,null,null,50,50,200,$officerNumber,\"남\",60,41,5,\"유가\",false,false]"
        val baseGenerals = (1..500).joinToString(",", transform = ::sourceTuple)
        val sourceExtendedGenerals = (501..1000).joinToString(",", transform = ::sourceTuple)
        val scenario = ScenarioJson.loadScenario(
            """
            {
              "title": "enriched roster",
              "startYear": 180,
              "map": {"mapName": "che"},
              "const": {},
              "nation": [],
              "general": [$baseGenerals],
              "general_ex": [$sourceExtendedGenerals],
              "diplomacy": []
            }
            """.trimIndent(),
        )

        val seeded = scenario.seedGenerals(extendedGeneral = false)
        assertEquals(1000, seeded.size)
        assertEquals((1..1000).toList(), seeded.mapNotNull(ScenarioGeneral::officerNumber))
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
                [2,"RTK14",null,0,null,41,42,43,0,181,241,null,null,null,71,72,205,1001,"남",60,37,333,"유가",true,true]
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
        assertNull(legacy.legacyActiveAtStart)
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
        assertEquals(true, rtk14.legacyActiveAtStart)
        assertEquals(25, rtk14.rawTuple.size)
        assertEquals(205, rtk14.rawTuple[16])
        assertEquals("유가", rtk14.rawTuple[22])
        assertEquals(true, rtk14.rawTuple[23])
        assertEquals(true, rtk14.rawTuple[24])
    }

    @Test
    fun `24-slot RTK14 source tuples remain valid without a legacy activity override`() {
        val scenario = ScenarioJson.loadScenario(
            """
            {
              "title": "rtk14 source tuple",
              "startYear": 200,
              "map": {"mapName": "che"},
              "const": {},
              "nation": [],
              "general": [[1,"RTK14",null,0,null,41,42,43,0,181,241,null,null,null,71,72,205,1001,"남",60,37,333,"유가",true]],
              "general_ex": [],
              "diplomacy": []
            }
            """.trimIndent(),
        )

        val rtk14 = scenario.baseGenerals.single()
        assertEquals(24, rtk14.rawTuple.size)
        assertNull(rtk14.legacyActiveAtStart)
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
    fun `RTK14 additions outside general fail closed instead of being silently dropped`() {
        for (section in listOf("general_ex", "general_neutral")) {
            val scenario = ScenarioJson.loadScenario(
                """
                {
                  "title": "invalid RTK14 placement",
                  "startYear": 200,
                  "map": {"mapName": "che"},
                  "const": {},
                  "nation": [],
                  "general": [[1,"Legacy",null,0,null,1,1,1,0,180,240,null,null]],
                  "$section": [[1,"InvalidRtk14Placement",null,0,null,1,1,1,0,180,240,null,null,null,50,50,200,1,"남",60,40,300,"유가",true,false]],
                  "diplomacy": []
                }
                """.trimIndent(),
            )

            val error = assertFailsWith<IllegalArgumentException> {
                scenario.initGenerals()
            }
            assertTrue(error.message.orEmpty().contains(section))
        }
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
    fun `imperial general is decoded as protected npc type seven`() {
        val json = """
            {
              "title": "imperial",
              "startYear": 181,
              "imperialGenerals": ["영제"],
              "map": {"mapName": "che"},
              "const": {},
              "nation": [["하진", "#fff", 0, 0, "", 0, "유가", 1, ["낙양"]]],
              "general": [[1,"영제",null,"하진",null,20,11,48,0,156,189,null,null]],
              "general_ex": [],
              "general_neutral": [],
              "diplomacy": []
            }
        """.trimIndent()

        val scenario = ScenarioJson.loadScenario(json)

        assertEquals(setOf("영제"), scenario.imperialGeneralNames)
        assertEquals(7, scenario.baseGenerals.single().npcType)
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
