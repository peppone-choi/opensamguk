package opensamguk.infra.seed

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Scenario3190SeedTest {
    private val repo = Path.of("..").toAbsolutePath().normalize()

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
