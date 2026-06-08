package opensamguk.gameapi.controller

import opensamguk.gameapi.read.CityReadEntity
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.NationReadEntity
import opensamguk.gameapi.read.NationReadRepository
import opensamguk.gameapi.read.WorldStateReadEntity
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.logic.stats.GeneralActionPipeline
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.Optional
import kotlin.test.assertEquals

class SimulatorControllerTest {
    private val generals = mock(GeneralReadRepository::class.java)
    private val cities = mock(CityReadRepository::class.java)
    private val nations = mock(NationReadRepository::class.java)
    private val world = mock(WorldStateReadRepository::class.java)

    @Test
    fun `simulate-battle reuses BattleSimPreview and does not fabricate winner damage turns or logs`() {
        val attacker = general(id = 1, nationId = 1, cityId = 100, crew = 5000)
        val defender = general(id = 2, nationId = 2, cityId = 200, crew = 4000)
        `when`(generals.findById(1)).thenReturn(Optional.of(attacker))
        `when`(generals.findById(2)).thenReturn(Optional.of(defender))
        `when`(cities.findById(100)).thenReturn(Optional.of(city(100, 1)))
        `when`(cities.findById(200)).thenReturn(Optional.of(city(200, 2)))
        `when`(nations.findById(1)).thenReturn(Optional.of(nation(1)))
        `when`(nations.findById(2)).thenReturn(Optional.of(nation(2)))
        `when`(world.findAll()).thenReturn(
            listOf(
                WorldStateReadEntity(
                    id = 1,
                    currentYear = 1010,
                    currentMonth = 1,
                    config = linkedMapOf("startyear" to 1010),
                ),
            ),
        )

        val response = SimulatorController(generals, cities, nations, world, GeneralActionPipeline())
            .simulateBattle(
                mapOf(
                    "attackerGeneralId" to 1,
                    "defenderGeneralId" to 2,
                    "seed" to "1234abcd".repeat(4),
                    "repeatCnt" to 9999,
                ),
            )

        assertEquals(200, response.statusCode.value())
        val body = response.body!!
        assertEquals(true, body["result"])
        assertEquals("success", body["reason"])
        assertEquals(1, body["repeatCnt"])
        assertTrue((body["phase"] as Number).toDouble() >= 0.0)
        assertTrue((body["killed"] as Number).toDouble() >= 0.0)
        assertTrue(body["attackerSkills"] is Map<*, *>)
        assertFalse(body.containsKey("winner"))
        assertFalse(body.containsKey("attackerWon"))
        assertFalse(body.containsKey("damageDealt"))
        assertFalse(body.containsKey("damageReceived"))
        assertFalse(body.containsKey("turns"))
        assertFalse(body.containsKey("log"))
    }

    @Test
    fun `simulate-battle rejects missing live rows instead of inventing combatants`() {
        `when`(generals.findById(1)).thenReturn(Optional.empty())

        val response = SimulatorController(generals, cities, nations, world, GeneralActionPipeline())
            .simulateBattle(mapOf("attackerGeneralId" to 1, "defenderGeneralId" to 2))

        assertEquals(404, response.statusCode.value())
        assertEquals("attacker not found", response.body?.get("error"))
    }

    private fun general(id: Int, nationId: Int, cityId: Int, crew: Int) = GeneralReadEntity(
        id = id,
        name = "g$id",
        nationId = nationId,
        cityId = cityId,
        leadership = 80,
        strength = 80,
        intel = 70,
        injury = 0,
        experience = 0,
        dedication = 0,
        officerLevel = 5,
        gold = 1000,
        rice = 10000,
        crew = crew,
        crewTypeId = 1100,
        train = 100,
        atmos = 100,
        meta = linkedMapOf("defence_train" to 0),
    )

    private fun city(id: Int, nationId: Int) = CityReadEntity(
        id = id,
        name = "c$id",
        nationId = nationId,
        level = 0,
        defense = 500,
        defenseMax = 9999,
        wall = 500,
        wallMax = 9999,
        population = 100000,
        populationMax = 999999,
    )

    private fun nation(id: Int) = NationReadEntity(
        id = id,
        name = "n$id",
        color = "#000000",
        capitalCityId = 100,
        rice = 10000,
        tech = 0.0,
        level = 0,
    )
}
