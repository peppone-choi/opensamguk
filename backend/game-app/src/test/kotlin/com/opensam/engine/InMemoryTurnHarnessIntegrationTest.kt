package com.opensam.engine

import com.opensam.entity.City
import com.opensam.entity.General
import com.opensam.entity.Nation
import com.opensam.entity.WorldState
import com.opensam.test.InMemoryTurnHarness
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import java.time.OffsetDateTime

class InMemoryTurnHarnessIntegrationTest {

    @Test
    fun `tier2 reserved turn is consumed and world advances`() {
        val harness = InMemoryTurnHarness()
        val world = WorldState(
            id = 1,
            name = "test-world",
            scenarioCode = "test",
            currentYear = 200,
            currentMonth = 1,
            tickSeconds = 60,
            updatedAt = OffsetDateTime.now().minusSeconds(90),
        )
        val nation = Nation(
            id = 1,
            worldId = 1,
            name = "위",
            color = "#ffffff",
            level = 3,
            strategicCmdLimit = 10,
        )
        val city = City(
            id = 1,
            worldId = 1,
            name = "낙양",
            level = 5,
            nationId = 1,
            supplyState = 1,
            frontState = 0,
            pop = 10000,
            popMax = 50000,
            agri = 100,
            agriMax = 1000,
            comm = 100,
            commMax = 1000,
            secu = 100,
            secuMax = 1000,
            trust = 100,
            def = 100,
            defMax = 1000,
            wall = 100,
            wallMax = 1000,
        )
        val general = General(
            id = 1,
            worldId = 1,
            name = "조조",
            nationId = 1,
            cityId = 1,
            gold = 1000,
            rice = 1000,
            npcState = 0,
            turnTime = OffsetDateTime.now(),
        )

        harness.putWorld(world)
        harness.putNation(nation)
        harness.putCity(city)
        harness.putGeneral(general)
        harness.queueGeneralTurn(generalId = 1, actionCode = "휴식")

        harness.turnService.processWorld(world)

        assertTrue(harness.generalTurnsFor(1).isEmpty())
        assertEquals(2, world.currentMonth.toInt())
        assertEquals("휴식", general.lastTurn["command"])
    }

    @Test
    fun `tier3 monthly integration consumes nation turn and decrements strategic limit`() {
        val harness = InMemoryTurnHarness()
        val world = WorldState(
            id = 1,
            name = "test-world",
            scenarioCode = "test",
            currentYear = 200,
            currentMonth = 10,
            tickSeconds = 60,
            updatedAt = OffsetDateTime.now().minusSeconds(190),
        )
        val nation = Nation(
            id = 1,
            worldId = 1,
            name = "촉",
            color = "#00ff00",
            level = 4,
            strategicCmdLimit = 5,
        )
        val city = City(
            id = 1,
            worldId = 1,
            name = "성도",
            level = 5,
            nationId = 1,
            supplyState = 1,
            frontState = 0,
            pop = 12000,
            popMax = 50000,
            agri = 500,
            agriMax = 1000,
            comm = 500,
            commMax = 1000,
            secu = 500,
            secuMax = 1000,
            trust = 90,
            def = 500,
            defMax = 1000,
            wall = 500,
            wallMax = 1000,
        )
        val officer = General(
            id = 1,
            worldId = 1,
            name = "유비",
            nationId = 1,
            cityId = 1,
            officerLevel = 5,
            npcState = 0,
            turnTime = OffsetDateTime.now(),
        )

        harness.putWorld(world)
        harness.putNation(nation)
        harness.putCity(city)
        harness.putGeneral(officer)
        harness.queueNationTurn(nationId = 1, officerLevel = 5, actionCode = "Nation휴식")
        harness.queueGeneralTurn(generalId = 1, actionCode = "휴식")

        harness.turnService.processWorld(world)

        assertTrue(harness.nationTurnsFor(1, 5).isEmpty())
        assertEquals(1, world.currentMonth.toInt())
        assertEquals(2, nation.strategicCmdLimit.toInt())
        verify(harness.unificationService, times(3)).checkAndSettleUnification(world)
    }
}
