package opensamguk.infra.seed

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScenarioJsonTest {

    @Test
    fun `hwiha lord declarations are explicit unique and profile scoped`() {
        val raw = readResource(LEGACY_FIXTURE).trimStart().removePrefix("{")
        val old = ScenarioJson.loadScenario("{" + raw)
        // 시작 시점(181)에 활동 중인 base 장수여야 아래 지연·제외 거부가 그 조작 때문에 난다
        // (첫 행 소제1은 168년생이라 원래부터 비활동이다).
        val name = "우길"
        assertEquals(1, old.baseGenerals.count { it.name == name })
        fun parse(declaration: String, format: String = "GENERAL_RETAINER_CAMPAIGN") =
            ScenarioJson.loadScenario("{\"worldFormat\":\"$format\",\"lords\":$declaration," + raw)
        val encoded = opensamguk.infra.persistence.MetaJson.encode(listOf(name))
        val declared = parse(encoded)
        // 양성 대조: 선언 그대로면 계약을 통과한다.
        ScenarioImporter(scenario = declared, cities = emptyList()).validateSeedContract()
        assertTrue(declared.generals.single { it.name == name }.lord == true)
        assertTrue(declared.generals.filter { it.name != name }.all { it.lord == false })
        assertTrue(old.generals.all { it.lord == false })
        val omittedProfile = ScenarioJson.loadScenario("{\"lords\":$encoded," + raw)
        assertNull(omittedProfile.ruleProfile)
        assertTrue(omittedProfile.generals.single { it.name == name }.lord == true)
        for (bad in listOf("null", "42", "[42]", "[\"\"]", "[\"no-such-general\"]",
            opensamguk.infra.persistence.MetaJson.encode(listOf(name, name)))) {
            assertFailsWith<IllegalArgumentException> { parse(bad) }
        }
        assertFailsWith<IllegalArgumentException> { parse(encoded, "SAMMO") }
        assertFailsWith<IllegalArgumentException> { parse("null", "SAMMO") }
        val duplicate = opensamguk.infra.persistence.MetaJson.decode("{" + raw).toMutableMap()
        duplicate["worldFormat"] = "GENERAL_RETAINER_CAMPAIGN"
        duplicate["lords"] = listOf(name)
        duplicate["general_ex"] = listOf((duplicate["general"] as List<*>).single { (it as List<*>)[1] == name })
        assertFailsWith<IllegalArgumentException> {
            ScenarioJson.loadScenario(opensamguk.infra.persistence.MetaJson.encode(duplicate))
        }
        val deferred = declared.copy(
            generals = declared.generals.map { if (it.name == name) it.copy(appearanceYear = declared.startYear + 1, deadYear = declared.startYear + 20) else it },
            baseGenerals = declared.baseGenerals.map { if (it.name == name) it.copy(appearanceYear = declared.startYear + 1, deadYear = declared.startYear + 20) else it },
        )
        val deferredError = assertFailsWith<IllegalArgumentException> {
            ScenarioImporter(scenario = deferred, cities = emptyList()).validateSeedContract()
        }
        assertTrue(deferredError.message!!.contains("declared HWIHA lord"))
        val lord = declared.baseGenerals.single { it.name == name }
        val excluded = declared.copy(
            baseGenerals = declared.baseGenerals - lord,
            generalEx = declared.generalEx + lord,
        )
        val excludedError = assertFailsWith<IllegalArgumentException> {
            ScenarioImporter(scenario = excluded, cities = emptyList(), extendedGeneral = false).validateSeedContract()
        }
        assertTrue(excludedError.message!!.contains("declared HWIHA lord"))
    }

    @Test
    fun `ruleProfile omission is preserved and parses fail closed`() {
        // 누락은 fresh 시드에서 HWIHA로 해석하되, 파싱 모델에서는 누락 여부를 보존한다.
        assertNull(ScenarioJson.loadScenario(readResource(LEGACY_FIXTURE)).ruleProfile)
        val base = readResource(LEGACY_FIXTURE).trimStart().removePrefix("{")
        assertEquals(
            opensamguk.logic.input.RuleProfile.HWIHA,
            ScenarioJson.loadScenario("{\"worldFormat\": \"GENERAL_RETAINER_CAMPAIGN\"," + base).ruleProfile,
        )
        assertFailsWith<IllegalArgumentException> { ScenarioJson.loadScenario("{\"worldFormat\": \"hwiha\"," + base) }
        assertFailsWith<IllegalArgumentException> { ScenarioJson.loadScenario("{\"ruleProfile\": null," + base) }
    }

    @Test
    fun `historical resources without HWIHA declarations cannot become fresh HWIHA worlds`() {
        val directory = Path.of("src/main/resources/scenario")
        val codes = Files.list(directory).use { paths ->
            paths.map { it.fileName.toString() }
                .filter { it.matches(Regex("scenario_\\d+\\.json")) }
                .toList()
        }
        // 목록이 살아 있는지는 개수 하한 대신 남을 런타임 시나리오로 확인한다 — 은퇴 파일이 빠져도 성립한다.
        assertTrue(codes.containsAll(RUNTIME_SCENARIO_CODES.map { "scenario_$it.json" }), "$codes")
        for (file in codes) {
            val scenario = ScenarioJson.loadScenario(readResource("scenario/$file"))
            val importer = ScenarioImporter(scenario, emptyList(), scenarioCode = file.removeSuffix(".json"))
            if (scenario.ruleProfile == null) {
                assertFailsWith<IllegalArgumentException>(file) { importer.validateFreshProfile() }
            } else {
                importer.validateFreshProfile()
            }
        }
        // 거부 쪽 갈래는 런타임 목록과 무관하게 구 시나리오 픽스처로 늘 잰다.
        val legacy = ScenarioJson.loadScenario(readResource(LEGACY_FIXTURE))
        assertNull(legacy.ruleProfile)
        assertFailsWith<IllegalArgumentException>(LEGACY_FIXTURE) {
            ScenarioImporter(legacy, emptyList(), scenarioCode = "scenario_mapless_legacy").validateFreshProfile()
        }
    }

    /**
     * 배포(`.github/workflows/deploy.yml` 「Validate materialized scenario seed contracts」)가 RTK14 보강 직후
     * 이 테스트를 **이름으로** 돌린다. 이름을 바꾸면 deploy.yml 도 같이 바꾼다.
     *
     * 대상은 운영 카탈로그가 고를 수 있는 제품 시나리오다(`ScenarioCatalogService` 의 활성 목록).
     * 휘하 190(`scenario_3190`)은 RTK14 보강이 행을 덧붙이는 파일이라 여기 넣지 않는다 — 커밋본의
     * 계약은 `Scenario3190SeedTest` 가 잰다.
     */
    @Test
    fun `product runtime scenario declares the HWIHA new world and satisfies its seed contract`() {
        for (code in listOf("990002")) {
            val scenario = ScenarioJson.loadScenario(readResource("scenario/scenario_$code.json"))
            assertEquals(opensamguk.logic.input.RuleProfile.HWIHA, scenario.ruleProfile, "scenario_$code worldFormat")
            assertEquals("han-world-v3", scenario.map["mapName"], "scenario_$code mapName")
            // unitSet 생략은 importer 가 han 으로 채운다(ScenarioImporter.scenarioMapConfig).
            assertTrue(scenario.map["unitSet"] in setOf(null, "han"), "scenario_$code unitSet")
            assertNotNull(scenario.seedContract, "scenario_$code seedContract")
            ScenarioImporter(scenario, emptyList(), scenarioCode = "scenario_$code").validateFreshProfile()
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
        val scenario = ScenarioJson.loadScenario(readResource(LEGACY_FIXTURE))

        assertEquals(false, scenario.ignoreDefaultEvents)
        assertEquals(1, scenario.events.size)
        assertEquals("destroy_nation", scenario.events.single().target)
        assertEquals(1000, scenario.events.single().priority)
        assertEquals(2, scenario.events.single().actions.size)
        assertEquals(1, scenario.initialEvents.size)
        assertEquals(2, scenario.initialEvents.single().actions.size)
    }

    @Test
    fun `scenario honors ignoreDefaultEvents`() {
        val scenario = ScenarioJson.loadScenario(
            """
            {
              "title": "own events",
              "startYear": 181,
              "map": {"mapName": "che"},
              "const": {},
              "ignoreDefaultEvents": true,
              "nation": [],
              "general": [],
              "general_ex": [],
              "diplomacy": [],
              "events": [
                ["Month", 1000, true, ["DeleteEvent"]],
                ["Month", 999, ["Date", ">=", 182, 1], ["BlockScoutAction"], ["DeleteEvent"]]
              ]
            }
            """.trimIndent(),
        )

        assertEquals(true, scenario.ignoreDefaultEvents)
        assertEquals(2, scenario.events.size)
        assertEquals(listOf(1000, 999), scenario.events.map { it.priority })
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

    private companion object {
        /** 은퇴 파일이 클래스패스를 떠난 뒤에도 `src/main/resources/scenario` 에 남는 런타임 시나리오. */
        val RUNTIME_SCENARIO_CODES = listOf("990002", "3190")

        /** 프로필·mapName 을 선언하지 않은 구(삼모) 시나리오 — `src/test/resources` 의 1010 사본. */
        const val LEGACY_FIXTURE = "scenario/scenario_mapless_legacy.json"
    }
}
