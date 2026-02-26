package com.opensam.engine

import com.opensam.engine.ai.GeneralAI
import com.opensam.engine.war.BattleResult
import com.opensam.engine.war.BattleService
import com.opensam.entity.City
import com.opensam.entity.Diplomacy
import com.opensam.entity.General
import com.opensam.entity.Nation
import com.opensam.entity.WorldState
import com.opensam.model.ScenarioData
import com.opensam.service.ScenarioService
import com.opensam.test.InMemoryTurnHarness
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.atLeast
import org.mockito.Mockito.never
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import java.time.OffsetDateTime

class DuelSimulationTest {

    @Test
    fun `city conquest moves attacker to conquered city`() {
        val fx = setupDuel()
        val battleService = privateField<BattleService>(fx.harness, "battleService")
        doAnswer { invocation ->
            val attacker = invocation.arguments[0] as General
            val targetCity = invocation.arguments[1] as City
            targetCity.nationId = attacker.nationId
            BattleResult(attackerWon = true, cityOccupied = true)
        }.`when`(battleService).executeBattle(fx.dongKing, fx.city2, fx.world)

        fx.harness.turnService.processWorld(fx.world)

        assertEquals(2L, fx.dongKing.cityId)
        assertEquals(1L, fx.city2.nationId)
        verify(battleService).executeBattle(fx.dongKing, fx.city2, fx.world)
    }

    @Test
    fun `failed occupation keeps attacker in original city`() {
        val fx = setupDuel()
        val battleService = privateField<BattleService>(fx.harness, "battleService")
        `when`(
            battleService.executeBattle(
                fx.dongKing,
                fx.city2,
                fx.world,
            )
        ).thenReturn(BattleResult(attackerWon = true, cityOccupied = false))

        fx.harness.turnService.processWorld(fx.world)

        assertEquals(1L, fx.dongKing.cityId)
        assertEquals(2L, fx.city2.nationId)
        verify(battleService).executeBattle(fx.dongKing, fx.city2, fx.world)
    }

    @Test
    fun `multi turn duel triggers unification check after conquest`() {
        val fx = setupDuel()
        val battleService = privateField<BattleService>(fx.harness, "battleService")
        doAnswer { invocation ->
            val attacker = invocation.arguments[0] as General
            val targetCity = invocation.arguments[1] as City
            targetCity.nationId = attacker.nationId
            BattleResult(attackerWon = true, cityOccupied = true)
        }.`when`(battleService).executeBattle(fx.dongKing, fx.city2, fx.world)

        fx.harness.turnService.processWorld(fx.world)
        fx.world.updatedAt = OffsetDateTime.now().minusSeconds(90)
        fx.harness.turnService.processWorld(fx.world)

        assertEquals(1L, fx.city2.nationId)
        verify(fx.harness.unificationService, atLeast(2)).checkAndSettleUnification(fx.world)
    }

    @Test
    fun `mixed user and npc generals process in same world tick`() {
        val fx = setupMixedDuel()
        val battleService = privateField<BattleService>(fx.harness, "battleService")
        doAnswer { invocation ->
            val attacker = invocation.arguments[0] as General
            val targetCity = invocation.arguments[1] as City
            targetCity.nationId = attacker.nationId
            BattleResult(attackerWon = true, cityOccupied = true)
        }.`when`(battleService).executeBattle(fx.dongKing, fx.city2, fx.world)

        fx.harness.turnService.processWorld(fx.world)

        // User general (npcState=0) consumed queued 출병 → battle → conquered
        assertEquals(2L, fx.dongKing.cityId)
        assertEquals(1L, fx.city2.nationId)
        verify(battleService).executeBattle(fx.dongKing, fx.city2, fx.world)

        // User general should NOT go through AI path
        val generalAI = privateField<GeneralAI>(fx.harness, "generalAI")
        verify(generalAI, never()).decideAndExecute(fx.dongKing, fx.world)

        // NPC generals (npcState=2) should go through AI path
        verify(generalAI).decideAndExecute(fx.dongOfficer, fx.world)
        verify(generalAI).decideAndExecute(fx.seoKing, fx.world)

        // 재야 general (npcState=1) should NOT go through AI (npcState < 2, no autorun)
        verify(generalAI, never()).decideAndExecute(fx.jaeya, fx.world)

        // Queued turn for user general should be consumed
        assertEquals(0, fx.harness.generalTurnsFor(fx.dongKing.id).size)

        // Unification check should have been called
        verify(fx.harness.unificationService).checkAndSettleUnification(fx.world)
    }


    private fun setupDuel(): DuelFixture {
        val harness = InMemoryTurnHarness()
        val world = WorldState(
            id = 1,
            name = "duel-world",
            scenarioCode = "test",
            currentYear = 200,
            currentMonth = 1,
            tickSeconds = 60,
            updatedAt = OffsetDateTime.now().minusSeconds(90),
        )
        world.config = mutableMapOf("mapName" to "duel", "mapCode" to "duel", "hiddenSeed" to "test")

        val dong = Nation(id = 1, worldId = 1, name = "동국", color = "#ff3300", level = 3, strategicCmdLimit = 10)
        val seo = Nation(id = 2, worldId = 1, name = "서국", color = "#0033ff", level = 3, strategicCmdLimit = 10)

        val city1 = City(
            id = 1, worldId = 1, name = "동성", level = 5, nationId = 1,
            supplyState = 1, frontState = 1, pop = 10000, popMax = 50000,
            agri = 500, agriMax = 1000, comm = 500, commMax = 1000,
            secu = 500, secuMax = 1000, trust = 100, def = 500, defMax = 1000, wall = 500, wallMax = 1000,
        )
        val city2 = City(
            id = 2, worldId = 1, name = "서성", level = 5, nationId = 2,
            supplyState = 1, frontState = 1, pop = 10000, popMax = 50000,
            agri = 500, agriMax = 1000, comm = 500, commMax = 1000,
            secu = 500, secuMax = 1000, trust = 100, def = 500, defMax = 1000, wall = 500, wallMax = 1000,
        )

        val baseTurnTime = OffsetDateTime.now().minusSeconds(120)
        val dongKing = General(
            id = 11, worldId = 1, name = "동왕", nationId = 1, cityId = 1,
            officerLevel = 12, npcState = 2, crew = 2000, train = 100, atmos = 100,
            leadership = 95, strength = 90, intel = 80, turnTime = baseTurnTime,
        )
        val dongOfficer = General(
            id = 12, worldId = 1, name = "동장", nationId = 1, cityId = 1,
            officerLevel = 8, npcState = 2, crew = 1500, train = 100, atmos = 100,
            leadership = 82, strength = 78, intel = 75, turnTime = baseTurnTime.plusSeconds(1),
        )
        val seoKing = General(
            id = 21, worldId = 1, name = "서왕", nationId = 2, cityId = 2,
            officerLevel = 12, npcState = 2, crew = 1800, train = 100, atmos = 100,
            leadership = 92, strength = 88, intel = 84, turnTime = baseTurnTime.plusSeconds(2),
        )

        harness.putWorld(world)
        harness.putNation(dong)
        harness.putNation(seo)
        harness.putCity(city1)
        harness.putCity(city2)
        harness.putGeneral(dongKing)
        harness.putGeneral(dongOfficer)
        harness.putGeneral(seoKing)
        val scenarioService = privateField<ScenarioService>(harness, "scenarioService")
        `when`(scenarioService.getScenario("test")).thenReturn(ScenarioData(startYear = 190))
        `when`(harness.diplomacyRepository.findByWorldIdAndIsDeadFalse(1L)).thenReturn(
            listOf(Diplomacy(worldId = 1, srcNationId = 1, destNationId = 2, stateCode = "선전포고", term = 12))
        )
        harness.queueGeneralTurn(generalId = 11, actionCode = "출병", arg = mutableMapOf("destCityId" to 2L))

        harness.queueNationTurn(nationId = 1, officerLevel = 12, actionCode = "Nation휴식")
        harness.queueNationTurn(nationId = 1, officerLevel = 8, actionCode = "Nation휴식")
        harness.queueNationTurn(nationId = 2, officerLevel = 12, actionCode = "Nation휴식")
        val generalAI = privateField<GeneralAI>(harness, "generalAI")
        doAnswer {
            dongKing.meta["aiArg"] = mutableMapOf("destCityId" to 2L)
            "출병"
        }.`when`(generalAI).decideAndExecute(dongKing, world)
        `when`(generalAI.decideAndExecute(dongOfficer, world)).thenReturn("휴식")
        `when`(generalAI.decideAndExecute(seoKing, world)).thenReturn("휴식")

        return DuelFixture(harness, world, city2, dongKing)
    }

    private fun <T> privateField(target: Any, fieldName: String): T {
        val field = target::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(target) as T
    }

    private data class DuelFixture(
        val harness: InMemoryTurnHarness,
        val world: WorldState,
        val city2: City,
        val dongKing: General,
        val dongOfficer: General = General(id = 0, worldId = 0, name = "", nationId = 0, cityId = 0, officerLevel = 0, npcState = 0, crew = 0, train = 0, atmos = 0, leadership = 0, strength = 0, intel = 0, turnTime = OffsetDateTime.now()),
        val seoKing: General = General(id = 0, worldId = 0, name = "", nationId = 0, cityId = 0, officerLevel = 0, npcState = 0, crew = 0, train = 0, atmos = 0, leadership = 0, strength = 0, intel = 0, turnTime = OffsetDateTime.now()),
        val jaeya: General = General(id = 0, worldId = 0, name = "", nationId = 0, cityId = 0, officerLevel = 0, npcState = 0, crew = 0, train = 0, atmos = 0, leadership = 0, strength = 0, intel = 0, turnTime = OffsetDateTime.now()),
    )

    private fun setupMixedDuel(): DuelFixture {
        val harness = InMemoryTurnHarness()
        val world = WorldState(
            id = 1,
            name = "mixed-duel-world",
            scenarioCode = "test",
            currentYear = 200,
            currentMonth = 1,
            tickSeconds = 60,
            updatedAt = OffsetDateTime.now().minusSeconds(90),
        )
        world.config = mutableMapOf("mapName" to "duel", "mapCode" to "duel", "hiddenSeed" to "test")

        val dong = Nation(id = 1, worldId = 1, name = "동국", color = "#ff3300", level = 3, strategicCmdLimit = 10)
        val seo = Nation(id = 2, worldId = 1, name = "서국", color = "#0033ff", level = 3, strategicCmdLimit = 10)

        val city1 = City(
            id = 1, worldId = 1, name = "동성", level = 5, nationId = 1,
            supplyState = 1, frontState = 1, pop = 10000, popMax = 50000,
            agri = 500, agriMax = 1000, comm = 500, commMax = 1000,
            secu = 500, secuMax = 1000, trust = 100, def = 500, defMax = 1000, wall = 500, wallMax = 1000,
        )
        val city2 = City(
            id = 2, worldId = 1, name = "서성", level = 5, nationId = 2,
            supplyState = 1, frontState = 1, pop = 10000, popMax = 50000,
            agri = 500, agriMax = 1000, comm = 500, commMax = 1000,
            secu = 500, secuMax = 1000, trust = 100, def = 500, defMax = 1000, wall = 500, wallMax = 1000,
        )

        val baseTurnTime = OffsetDateTime.now().minusSeconds(120)
        // USER general — npcState=0 (player-controlled king)
        val dongKing = General(
            id = 11, worldId = 1, name = "동왕", nationId = 1, cityId = 1,
            officerLevel = 12, npcState = 0, crew = 2000, train = 100, atmos = 100,
            leadership = 95, strength = 90, intel = 80, turnTime = baseTurnTime,
        )
        // NPC officer
        val dongOfficer = General(
            id = 12, worldId = 1, name = "동장", nationId = 1, cityId = 1,
            officerLevel = 8, npcState = 2, crew = 1500, train = 100, atmos = 100,
            leadership = 82, strength = 78, intel = 75, turnTime = baseTurnTime.plusSeconds(1),
        )
        // NPC king
        val seoKing = General(
            id = 21, worldId = 1, name = "서왕", nationId = 2, cityId = 2,
            officerLevel = 12, npcState = 2, crew = 1800, train = 100, atmos = 100,
            leadership = 92, strength = 88, intel = 84, turnTime = baseTurnTime.plusSeconds(2),
        )
        // 재야 (unaffiliated wanderer) — npcState=1
        val jaeya = General(
            id = 31, worldId = 1, name = "재야장수", nationId = 0, cityId = 1,
            officerLevel = 0, npcState = 1, crew = 0, train = 0, atmos = 0,
            leadership = 60, strength = 55, intel = 50, turnTime = baseTurnTime.plusSeconds(3),
        )

        harness.putWorld(world)
        harness.putNation(dong)
        harness.putNation(seo)
        harness.putCity(city1)
        harness.putCity(city2)
        harness.putGeneral(dongKing)
        harness.putGeneral(dongOfficer)
        harness.putGeneral(seoKing)
        harness.putGeneral(jaeya)
        val scenarioService = privateField<ScenarioService>(harness, "scenarioService")
        `when`(scenarioService.getScenario("test")).thenReturn(ScenarioData(startYear = 190))
        `when`(harness.diplomacyRepository.findByWorldIdAndIsDeadFalse(1L)).thenReturn(
            listOf(Diplomacy(worldId = 1, srcNationId = 1, destNationId = 2, stateCode = "선전포고", term = 12))
        )

        // User queues 출병 turn (player's pre-submitted action)
        harness.queueGeneralTurn(generalId = 11, actionCode = "출병", arg = mutableMapOf("destCityId" to 2L))

        // Nation turns for all high officers
        harness.queueNationTurn(nationId = 1, officerLevel = 12, actionCode = "Nation휴식")
        harness.queueNationTurn(nationId = 1, officerLevel = 8, actionCode = "Nation휴식")
        harness.queueNationTurn(nationId = 2, officerLevel = 12, actionCode = "Nation휴식")

        // NPC AI stubs — only for npcState>=2 generals
        val generalAI = privateField<GeneralAI>(harness, "generalAI")
        `when`(generalAI.decideAndExecute(dongOfficer, world)).thenReturn("휴식")
        `when`(generalAI.decideAndExecute(seoKing, world)).thenReturn("휴식")
        // No AI stub for dongKing (npcState=0) or jaeya (npcState=1) — they don't use AI

        return DuelFixture(harness, world, city2, dongKing, dongOfficer, seoKing, jaeya)
    }

}
