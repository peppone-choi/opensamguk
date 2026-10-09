package opensamguk.infra.seed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ScenarioReferenceValidatorTest {
    private val cities = ScenarioJson.loadMapCities(resource("map/che.json"))

    private fun scenario() = ScenarioJson.loadScenario(
        """
        {
          "title":"reference validation", "startYear":200,
          "nation":[
            ["첫나라","#112233",0,0,"",0,"유가",1,["낙양"]],
            ["둘째나라","#445566",0,0,"",0,"유가",1,["성도"]]
          ],
          "general":[[0,"처음",null,1,null,60,60,60,1,170,250,null,null]],
          "general_ex":[[999,"후발",null,2,"성도",60,60,60,1,200,280,null,null]],
          "general_neutral":[[null,"재야",null,0,"강주",60,60,60,0,170,250,null,null]],
          "diplomacy":[[1,2,0,3]]
        }
        """.trimIndent(),
    )

    @Test
    fun `valid names and numeric city ids preserve directional diplomacy and null RNG inputs`() {
        val source = scenario()
        val luoyang = cities.single { it.name == "낙양" }.id
        val valid = source.copy(nations = source.nations.mapIndexed { index, nation ->
            if (index == 0) nation.copy(cities = listOf(luoyang.toString())) else nation
        })
        ScenarioReferenceValidator.validate(valid, cities)
        assertEquals(null, valid.generals.first().locatedCity)
        assertEquals(0, valid.generals.first().affinity)
        assertEquals(null, valid.generals.last().affinity)
        assertEquals(listOf(ScenarioDiplomacy(1, 2, 0, 3)), valid.diplomacy)
        assertEquals(source.generals, valid.generals)
    }

    @Test
    fun `a location outside the general's nation's holdings remains valid`() {
        val source = scenario()
        val changed = source.copy(generals = source.generals.map { it.copy(locatedCity = "강주") })
        ScenarioReferenceValidator.validate(changed, cities)
    }

    @Test
    fun `unknown ownership names and ids are rejected`() {
        val source = scenario()
        for (ref in listOf("없는현", "999999")) {
            val invalid = source.copy(nations = listOf(source.nations.first().copy(cities = listOf(ref))))
            val error = assertFailsWith<IllegalArgumentException> { ScenarioReferenceValidator.validate(invalid, cities) }
            assertTrue(error.message.orEmpty().contains("unknown city: $ref"))
        }
    }

    @Test
    fun `same or different owners cannot declare the same resolved city twice`() {
        val source = scenario()
        val id = cities.single { it.name == "낙양" }.id.toString()
        for (nations in listOf(
            listOf(source.nations.first().copy(cities = listOf("낙양", "낙양"))),
            listOf(source.nations.first().copy(cities = listOf("낙양", id))),
            listOf(source.nations.first(), source.nations.last().copy(cities = listOf(id))),
        )) {
            val error = assertFailsWith<IllegalArgumentException> {
                ScenarioReferenceValidator.validate(source.copy(nations = nations), cities)
            }
            assertTrue(error.message.orEmpty().contains("duplicate ownership"))
        }
    }

    @Test
    fun `invalid explicit locations never become an omitted RNG location`() {
        val source = scenario()
        for (ref in listOf("없는현", "999999", "")) {
            val invalid = source.copy(generals = listOf(source.generals.first().copy(locatedCity = ref)))
            val error = assertFailsWith<IllegalArgumentException> { ScenarioReferenceValidator.validate(invalid, cities) }
            assertTrue(error.message.orEmpty().contains("unknown locatedCity: $ref"))
        }
    }

    @Test
    fun `extended future and neutral roster references are checked before selection`() {
        val source = scenario()
        for (index in source.generals.indices) {
            val invalid = source.copy(generals = source.generals.mapIndexed { i, general ->
                if (i == index) general.copy(locatedCity = "없는현") else general
            })
            assertFailsWith<IllegalArgumentException> { ScenarioReferenceValidator.validate(invalid, cities) }
        }
    }

    @Test
    fun `separate import rosters cannot bypass validation with an unchanged aggregate`() {
        val source = scenario()
        val invalid = source.generals.first().copy(locatedCity = "없는현")
        for (changed in listOf(
            source.copy(baseGenerals = listOf(invalid)),
            source.copy(generalEx = listOf(invalid)),
            source.copy(generalNeutral = listOf(invalid)),
        )) {
            assertFailsWith<IllegalArgumentException> { ScenarioReferenceValidator.validate(changed, cities) }
        }
    }

    @Test
    fun `missing neutral negative and self diplomacy endpoints are rejected`() {
        val source = scenario()
        for ((me, you) in listOf(1 to 3, 3 to 1, 0 to 1, 1 to 0, -1 to 2, 1 to 1)) {
            val invalid = source.copy(diplomacy = listOf(ScenarioDiplomacy(me, you, 2, 0)))
            val error = assertFailsWith<IllegalArgumentException> { ScenarioReferenceValidator.validate(invalid, cities) }
            assertTrue(error.message.orEmpty().contains("nonexistent ordered pair: $me -> $you"))
        }
    }

    @Test
    fun `references must resolve against the selected map rather than a global city catalogue`() {
        val source = scenario()
        val selected = cities.filterNot { it.name == "강주" }
        assertFailsWith<IllegalArgumentException> { ScenarioReferenceValidator.validate(source, selected) }
        ScenarioReferenceValidator.validate(source, cities)
    }

    @Test
    fun `both current packaged scenarios and their map resources retain valid references`() {
        for (code in listOf(3190, 990002)) {
            val source = ScenarioJson.loadScenario(resource("scenario/scenario_$code.json"))
            val config = source.map + source.const
            val map = MapJson.resourceCode(config["mapName"] as? String ?: "han-world-v2")
            val mapCities = ScenarioJson.loadMapCities(resource("map/$map.json"))
            ScenarioReferenceValidator.validate(source, mapCities)
        }
    }

    private fun resource(path: String): String =
        requireNotNull(javaClass.classLoader.getResourceAsStream(path)) { "missing test resource: $path" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
}
