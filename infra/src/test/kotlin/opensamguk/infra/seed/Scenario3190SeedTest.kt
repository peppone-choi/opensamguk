package opensamguk.infra.seed

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Scenario3190SeedTest {
    private val repo = Path.of("..").toAbsolutePath().normalize()

    @Test fun `190 historical seed has an explicit HWIHA roster and map4 inventory`() {
        val scenario = ScenarioJson.loadScenario(Files.readString(
            repo.resolve("infra/src/main/resources/scenario/scenario_3190.json")))
        val cities = ScenarioJson.loadMapCities(Files.readString(
            repo.resolve("infra/src/main/resources/map/han-world-v3.json")))
        val importer = ScenarioImporter(scenario, cities, "scenario_3190", artifactsRoot = repo)

        assertEquals(190, scenario.startYear)
        assertEquals(opensamguk.logic.input.RuleProfile.HWIHA, scenario.ruleProfile)
        assertEquals(21, scenario.nations.size)
        assertEquals(280, scenario.generals.size)
        assertEquals(249, scenario.generals.count { it.nationId > 0 })
        assertEquals(21, scenario.generals.count { it.lord == true })
        assertEquals(280, scenario.generals.count { it.personPolicy != null })
        assertEquals(42, scenario.units.size)
        assertEquals(6, scenario.personBonds.values.sumOf { it.size })
        assertTrue(scenario.personBonds.values.flatten().all { it.evidenceIds == setOf("novel:三國演義:第一回") })
        assertEquals(1447, cities.size)
        val warehouses = assertNotNull(scenario.warehouses).warehouses
        assertEquals(1301, warehouses.size)
        assertEquals(21, warehouses.values.count { stock ->
            stock.money > 0 || stock.grain > 0 || stock.iron > 0 || stock.timber > 0 || stock.horses > 0 })
        assertEquals(169000L, warehouses.values.sumOf { it.money })
        assertEquals(169000L, warehouses.values.sumOf { it.grain })
        assertTrue(scenario.nations.all { it.gold == 0 && it.rice == 0 })
        importer.validateSeedContract()
        importer.validateWarehouseSeed()
    }
}
