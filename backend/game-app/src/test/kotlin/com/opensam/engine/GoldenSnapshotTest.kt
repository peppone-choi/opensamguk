package com.opensam.engine

import com.opensam.entity.City
import com.opensam.entity.General
import com.opensam.entity.Nation
import com.opensam.entity.WorldState
import com.opensam.test.InMemoryTurnHarness
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

class GoldenSnapshotTest {

    @Test
    fun `multi-turn snapshot is deterministic and matches golden values`() {
        val left = runScenario()
        val right = runScenario()

        assertEquals(left, right)
        assertEquals(expectedSnapshot(), left)
    }

    private fun runScenario(): Snapshot {
        val harness = InMemoryTurnHarness()
        val world = WorldState(
            id = 1,
            name = "golden-world",
            scenarioCode = "test",
            currentYear = 200,
            currentMonth = 1,
            tickSeconds = 60,
            updatedAt = OffsetDateTime.now().minusSeconds(90),
        )

        val nationWei = Nation(
            id = 1,
            worldId = 1,
            name = "위",
            color = "#111111",
            level = 3,
            gold = 10000,
            rice = 10000,
            strategicCmdLimit = 0,
        )
        val nationShu = Nation(
            id = 2,
            worldId = 1,
            name = "촉",
            color = "#228833",
            level = 3,
            gold = 9000,
            rice = 8000,
            strategicCmdLimit = 0,
        )

        val cityWei = createCity(id = 1, nationId = 1, name = "허창")
        val cityShu = createCity(id = 2, nationId = 2, name = "성도")
        val cityNeutral = createCity(id = 3, nationId = 0, name = "중립")

        val g1 = createGeneral(id = 1, nationId = 1, cityId = 1, name = "조조", leadership = 80, crew = 200, train = 60, atmos = 70)
        val g2 = createGeneral(id = 2, nationId = 1, cityId = 1, name = "하후돈", leadership = 60, crew = 300, train = 40, atmos = 65)
        val g3 = createGeneral(id = 3, nationId = 2, cityId = 2, name = "유비", leadership = 75, crew = 250, train = 55, atmos = 75)
        val g4 = createGeneral(id = 4, nationId = 2, cityId = 2, name = "관우", leadership = 50, crew = 400, train = 30, atmos = 60)

        harness.putWorld(world)
        harness.putNation(nationWei)
        harness.putNation(nationShu)
        harness.putCity(cityWei)
        harness.putCity(cityShu)
        harness.putCity(cityNeutral)
        harness.putGeneral(g1)
        harness.putGeneral(g2)
        harness.putGeneral(g3)
        harness.putGeneral(g4)

        harness.queueGeneralTurn(generalId = 1, actionCode = "훈련")
        harness.queueGeneralTurn(generalId = 2, actionCode = "훈련")
        harness.queueGeneralTurn(generalId = 3, actionCode = "훈련")
        harness.queueGeneralTurn(generalId = 4, actionCode = "훈련")

        harness.turnService.processWorld(world)

        assertTrue(harness.generalTurnsFor(1).isEmpty())
        assertTrue(harness.generalTurnsFor(2).isEmpty())
        assertTrue(harness.generalTurnsFor(3).isEmpty())
        assertTrue(harness.generalTurnsFor(4).isEmpty())

        val generals = harness.generalRepository.findByWorldId(1).sortedBy { it.id }
        val nations = harness.nationRepository.findByWorldId(1).sortedBy { it.id }
        val cities = harness.cityRepository.findByWorldId(1).sortedBy { it.id }

        return Snapshot(
            year = world.currentYear.toInt(),
            month = world.currentMonth.toInt(),
            generals = generals.map {
                GeneralState(
                    id = it.id,
                    crew = it.crew,
                    train = it.train.toInt(),
                    atmos = it.atmos.toInt(),
                    experience = it.experience,
                    dedication = it.dedication,
                )
            },
            nations = nations.map {
                NationState(
                    id = it.id,
                    gold = it.gold,
                    rice = it.rice,
                    strategicCmdLimit = it.strategicCmdLimit.toInt(),
                )
            },
            cities = cities.map {
                CityState(
                    id = it.id,
                    nationId = it.nationId,
                    agri = it.agri,
                    comm = it.comm,
                    secu = it.secu,
                    pop = it.pop,
                )
            },
        )
    }

    private fun expectedSnapshot(): Snapshot {
        return Snapshot(
            year = 200,
            month = 2,
            generals = listOf(
                GeneralState(id = 1, crew = 200, train = 62, atmos = 63, experience = 100, dedication = 70),
                GeneralState(id = 2, crew = 300, train = 41, atmos = 58, experience = 100, dedication = 70),
                GeneralState(id = 3, crew = 250, train = 57, atmos = 67, experience = 100, dedication = 70),
                GeneralState(id = 4, crew = 400, train = 31, atmos = 54, experience = 100, dedication = 70),
            ),
            nations = listOf(
                NationState(id = 1, gold = 10000, rice = 10000, strategicCmdLimit = 0),
                NationState(id = 2, gold = 9000, rice = 8000, strategicCmdLimit = 0),
            ),
            cities = listOf(
                CityState(id = 1, nationId = 1, agri = 500, comm = 500, secu = 500, pop = 10000),
                CityState(id = 2, nationId = 2, agri = 500, comm = 500, secu = 500, pop = 10000),
                CityState(id = 3, nationId = 0, agri = 500, comm = 500, secu = 500, pop = 10000),
            ),
        )
    }

    private fun createCity(id: Long, nationId: Long, name: String): City {
        return City(
            id = id,
            worldId = 1,
            name = name,
            nationId = nationId,
            supplyState = 1,
            frontState = 0,
            pop = 10000,
            popMax = 50000,
            agri = 500,
            agriMax = 1000,
            comm = 500,
            commMax = 1000,
            secu = 500,
            secuMax = 1000,
            trust = 80,
            def = 500,
            defMax = 1000,
            wall = 500,
            wallMax = 1000,
        )
    }

    private fun createGeneral(
        id: Long,
        nationId: Long,
        cityId: Long,
        name: String,
        leadership: Short,
        crew: Int,
        train: Short,
        atmos: Short,
    ): General {
        return General(
            id = id,
            worldId = 1,
            name = name,
            nationId = nationId,
            cityId = cityId,
            leadership = leadership,
            strength = 70,
            intel = 70,
            politics = 60,
            charm = 60,
            crew = crew,
            crewType = 0,
            train = train,
            atmos = atmos,
            gold = 500,
            rice = 500,
            npcState = 0,
            turnTime = OffsetDateTime.now(),
        )
    }

    private data class Snapshot(
        val year: Int,
        val month: Int,
        val generals: List<GeneralState>,
        val nations: List<NationState>,
        val cities: List<CityState>,
    )

    private data class GeneralState(
        val id: Long,
        val crew: Int,
        val train: Int,
        val atmos: Int,
        val experience: Int,
        val dedication: Int,
    )

    private data class NationState(
        val id: Long,
        val gold: Int,
        val rice: Int,
        val strategicCmdLimit: Int,
    )

    private data class CityState(
        val id: Long,
        val nationId: Long,
        val agri: Int,
        val comm: Int,
        val secu: Int,
        val pop: Int,
    )
}
