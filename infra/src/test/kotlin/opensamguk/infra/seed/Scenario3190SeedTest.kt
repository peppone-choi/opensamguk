package opensamguk.infra.seed

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import opensamguk.logic.renown.RenownAssessment
import opensamguk.logic.renown.RenownRules
import opensamguk.common.world.WorldId
import org.springframework.jdbc.core.JdbcTemplate

class Scenario3190SeedTest {
    private val repo = Path.of("..").toAbsolutePath().normalize()

    @Test fun `190 rulers retain their full starting roster through the first monthly assessment`() {
        val scenario = ScenarioJson.loadScenario(Files.readString(
            repo.resolve("infra/src/main/resources/scenario/scenario_3190.json")))
        val generals = scenario.generals.associateBy { it.name }
        val importer = ScenarioImporter(scenario, emptyList(), "scenario_3190", artifactsRoot = repo)
        val initialByLord = importer.initialRetainers().groupBy { it.master }
        for (ruler in scenario.rulers) {
            val lord = generals.getValue(ruler.general)
            val cards = initialByLord[lord.name].orEmpty().mapIndexed { index, relation ->
                val person = generals.getValue(relation.general)
                RenownAssessment.RetainerCard(index + 1,
                    RenownRules.personCost(person.leadership, person.strength, person.intel,
                        person.politics, person.charm), 100)
            }
            val cost = cards.sumOf { it.cost }
            val capacity = assertNotNull(lord.personPolicy).renownCapacity
            assertEquals(cost + RenownRules.INITIAL_CAPACITY, capacity, "${lord.name} starting allowance")
            val before = capacity.coerceIn(RenownAssessment.CANON.floor, RenownAssessment.CANON.ceiling)
            assertEquals(capacity, before, "${lord.name} would be truncated before the monthly assessment")
            val firstMonth = RenownAssessment.assess(1, before, RenownAssessment.Tally(),
                RenownAssessment.CANON, cards)
            assertTrue(firstMonth.released.isEmpty(), "${lord.name} loses a starting retainer")
            if (lord.name == "공손찬") {
                assertEquals(71, cost)
                assertEquals(101, capacity)
                assertEquals(8, RenownAssessment.departures(30, cards).size,
                    "the old 30-capacity policy would shed eight of Gongson Zan's retainers")
            }
            if (lord.name == "동탁") {
                assertEquals(277, cost)
                assertEquals(307, capacity)
            }
        }
    }

    @Test fun `direct 190 seed rejects a ruler capacity above its starting roster budget`() {
        val scenario = ScenarioJson.loadScenario(Files.readString(
            repo.resolve("infra/src/main/resources/scenario/scenario_3190.json")))
        val gongson = scenario.generals.single { it.name == "공손찬" }
        assertEquals(101, assertNotNull(gongson.personPolicy).renownCapacity)
        val oversized = gongson.copy(personPolicy = gongson.personPolicy!!.copy(renownCapacity = 307))
        val direct = scenario.copy(
            generals = scenario.generals.map { if (it.name == gongson.name) oversized else it },
            baseGenerals = scenario.baseGenerals.map { if (it.name == gongson.name) oversized else it },
        )
        val cities = ScenarioJson.loadMapCities(Files.readString(
            repo.resolve("infra/src/main/resources/map/han-world-v3.json")))
        val importer = ScenarioImporter(direct, cities, "scenario_3190", artifactsRoot = repo)
        val error = assertFailsWith<IllegalArgumentException> {
            importer.validateSeedContract()
        }
        assertTrue(error.message.orEmpty().contains("Starting retinue for 공손찬 requires capacity 101, declared 307"))
        // importAdmitted validates this contract before its first JDBC call.
        val prewrite = assertFailsWith<IllegalArgumentException> {
            importer.importAdmitted(JdbcTemplate(), WorldId(1))
        }
        assertEquals(error.message, prewrite.message)
    }

    @Test fun `190 placement references real counties and keeps affiliated officers in their faction`() {
        val scenario = ScenarioJson.loadScenario(Files.readString(
            repo.resolve("infra/src/main/resources/scenario/scenario_3190.json")))
        val cityIds = ScenarioJson.loadMapCities(Files.readString(
            repo.resolve("infra/src/main/resources/map/han-world-v3.json"))).mapTo(hashSetOf()) { it.id }
        val ownerByCity = mutableMapOf<Int, Int>()
        scenario.nations.forEach { nation ->
            assertTrue(nation.cities.isNotEmpty(), "${nation.name} has no starting county")
            nation.cities.forEach { ref ->
                val cityId = assertNotNull(ref.toIntOrNull(), "${nation.name} has a non-numeric county: $ref")
                assertTrue(cityId in cityIds, "${nation.name} references missing county $cityId")
                assertEquals(null, ownerByCity.put(cityId, nation.id), "county $cityId has multiple owners")
            }
        }
        scenario.generals.forEach { general ->
            val cityId = general.locatedCity?.toIntOrNull()
            if (general.locatedCity != null) {
                assertNotNull(cityId, "${general.name} has a non-numeric county")
                assertTrue(cityId in cityIds, "${general.name} references missing county $cityId")
            }
            if (general.nationId > 0) {
                assertNotNull(cityId, "${general.name} has no starting county")
                assertEquals(general.nationId, ownerByCity[cityId],
                    "${general.name} starts outside their faction at county $cityId")
            }
        }
        assertNotNull(scenario.warehouses).warehouses.forEach { (cityId, stock) ->
            if (stock.money + stock.grain + stock.iron + stock.timber + stock.horses > 0) {
                assertTrue(cityId in ownerByCity, "starting inventory is outside all factions at county $cityId")
            }
        }
    }

    @Test fun `190 game anchors project to their commandery counties without expanding passes`() {
        val scenario = ScenarioJson.loadScenario(Files.readString(
            repo.resolve("infra/src/main/resources/scenario/scenario_3190.json")))
        val rawMap = opensamguk.infra.persistence.MetaJson.decode(Files.readString(
            repo.resolve("infra/src/main/resources/map/han-world-v3.json")))
        val commanderyByCity = (rawMap["cities"] as List<*>).associate { raw ->
            val city = raw as Map<*, *>
            val meta = city["meta"] as Map<*, *>
            (city["id"] as Int) to ((city["region"] as Int) to (meta["junCh"] as String))
        }
        val countyIds = assertNotNull(scenario.warehouses).warehouses.keys
        // RTK14 190.1 game anchors, with the mismapped Gongson Du anchor corrected to Liaodong Xiangping.
        // This is a game projection, not a claim of effective control in January 190.
        val anchors = mapOf(
            "공손도" to listOf(730), "공손찬" to listOf(715), "유비" to listOf(361),
            "유대" to listOf(1440), "유언" to listOf(613, 585, 587), "유우" to listOf(1593),
            "유표" to listOf(472, 458), "사섭" to listOf(736, 737), "공주" to listOf(85),
            "공융" to listOf(347), "손견" to listOf(474), "장양" to listOf(1543),
            "장초" to listOf(312), "장로" to listOf(593), "조조" to listOf(286),
            "동탁" to listOf(46, 1046, 1357, 1, 649, 1041), "원소" to listOf(161),
            "원술" to listOf(412, 22), "도겸" to listOf(299, 112), "한복" to listOf(223),
            "마등" to listOf(1368),
        )
        assertEquals(scenario.nations.map { it.name }.toSet(), anchors.keys)
        scenario.nations.forEach { nation ->
            val startingNodes = anchors.getValue(nation.name)
            val commanderies = startingNodes.filter { it in countyIds }.mapTo(hashSetOf()) {
                commanderyByCity.getValue(it)
            }
            val expected = startingNodes.toSet() + countyIds.filter { commanderyByCity.getValue(it) in commanderies }
            assertEquals(expected, nation.cities.map { it.toInt() }.toSet(),
                "${nation.name} must own each county in its game anchor commanderies")
        }
        assertEquals(426, scenario.nations.sumOf { it.cities.size })
        assertEquals(730, scenario.generals.single { it.name == "공손도" }.locatedCity?.toInt())
        assertEquals(0L, assertNotNull(scenario.warehouses).warehouses.getValue(993).money)
        assertEquals(6000L, assertNotNull(scenario.warehouses).warehouses.getValue(730).money)
    }

    @Test fun `190 historical seed has an explicit HWIHA roster and map4 inventory`() {
        val source = Files.readString(repo.resolve("infra/src/main/resources/scenario/scenario_3190.json"))
        val scenario = ScenarioJson.loadScenario(source)
        val raw = opensamguk.infra.persistence.MetaJson.decode(source)
        val cities = ScenarioJson.loadMapCities(Files.readString(
            repo.resolve("infra/src/main/resources/map/han-world-v3.json")))
        val importer = ScenarioImporter(scenario, cities, "scenario_3190", artifactsRoot = repo)

        assertEquals(190, scenario.startYear)
        assertEquals(opensamguk.logic.input.RuleProfile.HWIHA, scenario.ruleProfile)
        assertEquals(21, scenario.nations.size)
        assertEquals(1000, scenario.generals.size)
        assertEquals(249, scenario.generals.count { it.nationId > 0 })
        assertEquals(21, scenario.generals.count { it.lord == true })
        assertEquals(21, scenario.rulers.size)
        assertEquals(scenario.nations.map { it.name }.toSet(), scenario.rulers.map { it.nation }.toSet())
        scenario.rulers.forEach { declaration ->
            val nation = scenario.nations.single { it.name == declaration.nation }
            val general = scenario.generals.single { it.name == declaration.general }
            assertEquals(nation.id, general.nationId)
            assertTrue(general.lord == true)
        }
        val rulers = scenario.nations.map { nation ->
            val general = scenario.generals.single { it.nationId == nation.id && it.officerLevel == 12 }
            mapOf("nation" to nation.name, "general" to general.name)
        }
        assertEquals(rulers, raw["rulers"])
        assertEquals(rulers.map { it.getValue("general") }, raw["lords"])
        assertEquals(228, scenario.retainers.size)
        val initialRetainers = importer.initialRetainers()
        assertEquals(228, initialRetainers.size)
        assertEquals(scenario.retainers, initialRetainers)
        // The workbook appearance years activate 16 officers excluded by the old death-year gate.
        val newlyActiveNames = scenario.generals.filter { it.legacyActiveAtStart == false &&
            it.nationId > 0 && it.lord != true }.map { it.name }.toSet()
        assertEquals(16, newlyActiveNames.size)
        assertTrue(initialRetainers.map { it.general }.containsAll(newlyActiveNames))
        assertEquals(scenario.generals.filter { it.nationId > 0 && it.lord != true }.map { it.name }.toSet(),
            scenario.retainers.map { it.general }.toSet())
        assertEquals(1000, scenario.generals.count { it.personPolicy != null })
        assertEquals(1000, scenario.generals.mapNotNull { it.officerNumber }.toSet().size)
        assertEquals(1000, scenario.generals.mapNotNull { it.personPolicy?.officerId }.toSet().size)
        assertEquals(999, scenario.generals.count { general ->
            general.officerNumber != general.personPolicy!!.officerId - 10000
        })
        assertEquals(42, scenario.units.size)
        assertEquals(6, scenario.personBonds.values.sumOf { it.size })
        assertTrue(scenario.personBonds.values.flatten().all { it.evidenceIds == setOf("novel:三國演義:第一回") })
        // 2026-09-27 1428 판: 중복 합성 城 23곳을 거두고 동명 실결손 4곳을 더했다(창고는 promote_3190 --rewarehouse).
        assertEquals(1428, cities.size)
        val warehouses = assertNotNull(scenario.warehouses).warehouses
        assertEquals(1282, warehouses.size)
        assertEquals(21, warehouses.values.count { stock ->
            stock.money > 0 || stock.grain > 0 || stock.iron > 0 || stock.timber > 0 || stock.horses > 0 })
        assertEquals(169000L, warehouses.values.sumOf { it.money })
        assertEquals(169000L, warehouses.values.sumOf { it.grain })
        assertTrue(scenario.nations.all { it.gold == 0 && it.rice == 0 })
        importer.validateSeedContract()
        importer.validateWarehouseSeed()
    }

    @Test fun `190 ownership rejects duplicate self neutral and foreign nation declarations`() {
        val raw = opensamguk.infra.persistence.MetaJson.decode(Files.readString(
            repo.resolve("infra/src/main/resources/scenario/scenario_3190.json")))
        val declared = (raw["retainers"] as List<*>).map { it as Map<*, *> }
        fun rejected(extra: Map<String, String>) {
            val altered = raw.toMutableMap()
            altered["retainers"] = declared + extra
            assertFailsWith<IllegalArgumentException> {
                ScenarioJson.loadScenario(opensamguk.infra.persistence.MetaJson.encode(altered))
            }
        }
        val first = declared.first()
        rejected(mapOf("general" to first["general"].toString(), "master" to first["master"].toString()))
        rejected(mapOf("general" to "유비", "master" to "유비"))
        val neutral = (raw["general"] as List<*>).map { it as List<*> }.first { it[3] == 0 }[1].toString()
        rejected(mapOf("general" to neutral, "master" to "유비"))
        val differentLord = (raw["lords"] as List<*>).map { it.toString() }.first { it != first["master"] }
        val altered = raw.toMutableMap()
        altered["retainers"] = declared.mapIndexed { index, item ->
            if (index == 0) mapOf("general" to item["general"], "master" to differentLord) else item
        }
        assertFailsWith<IllegalArgumentException> {
            ScenarioJson.loadScenario(opensamguk.infra.persistence.MetaJson.encode(altered))
        }
    }
}
