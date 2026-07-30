package opensamguk.engine.world

import kotlinx.serialization.json.Json
import opensamguk.common.world.WorldId
import opensamguk.engine.config.EngineEventConfig
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.City
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.KvKey
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.logic.event.DeleteEventContext
import opensamguk.logic.event.EventDispatcher
import opensamguk.logic.event.EventStore
import opensamguk.logic.event.EventTarget
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.world.CityConstRegistry
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorldRaiseInvaderRestartTest {
    @Test
    fun `cold loaded RaiseInvader creates the PHP world effects and durable ending rows`() {
        val cityConst = CityConstRegistry.of("che")
        val invaderConstants = cityConst.all().values.filter { it.level == 4 }
        val safeConstant = cityConst.all().values.first { it.level != 4 }
        val invaderCities = invaderConstants.map {
            City(
                id = it.id,
                name = it.name,
                nationId = if (it == invaderConstants.first()) 1 else 0,
                level = it.level,
                populationMax = it.population,
                agricultureMax = it.agriculture,
                commerceMax = it.commerce,
                securityMax = it.security,
                defenceMax = it.defence,
                wallMax = it.wall,
            )
        }
        val safeCity = City(
            id = safeConstant.id,
            name = safeConstant.name,
            nationId = 1,
            level = safeConstant.level,
            populationMax = safeConstant.population,
            agricultureMax = safeConstant.agriculture,
            commerceMax = safeConstant.commerce,
            securityMax = safeConstant.security,
            defenceMax = safeConstant.defence,
            wallMax = safeConstant.wall,
        )
        val now = Instant.parse("0200-03-01T00:00:00Z")
        val state = TurnWorldState(
            id = 8,
            currentYear = 200,
            currentMonth = 3,
            tickSeconds = 3600,
            lastTurnTime = now,
            meta = linkedMapOf(
                "hiddenSeed" to "world-raise-invader-restart",
                "startYear" to 190,
                "turnterm" to 1,
                "isunited" to 2,
            ),
        )
        val originalGeneral = TurnGeneral(
            id = 1,
            name = "유비",
            nationId = 1,
            cityId = invaderConstants.first().id,
            troopId = 0,
            stats = GeneralStats(90, 90, 90),
            experience = 1_000,
            dedication = 1_000,
            officerLevel = 12,
            gold = 10,
            rice = 20,
            npcState = 0,
            turnTime = now,
        )
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state = state,
                generals = listOf(originalGeneral),
                cities = invaderCities + safeCity,
                nations = listOf(
                    Nation(
                        id = 1,
                        name = "촉",
                        color = "#ff0000",
                        capitalCityId = invaderConstants.first().id,
                        level = 2,
                        tech = 1000.0,
                    ),
                ),
                worldId = WorldId(8),
            ),
        )
        val recorder = ChangeRecorder()
        val coldStore = EventStore().also {
            it.loadRaw(
                id = 41,
                targetCode = "month",
                priority = 1000,
                conditionJson = Json.parseToJsonElement("true"),
                actionJson = Json.parseToJsonElement("""[["RaiseInvader",1,270,15000,3]]"""),
            )
            it.bindMutationSink(recorder::recordEventMutation)
        }
        val pipeline = GeneralActionPipeline()
        var unlocked = false
        EventDispatcher(coldStore, EngineEventConfig().eventActionFactory()).run(
            target = EventTarget.MONTH,
            contextFactory = { env ->
                env[DeleteEventContext.ENV_KEY] = coldStore
                WorldActionContext(
                    env,
                    world,
                    recorder,
                    pipeline,
                    unlockGame = { unlocked = true },
                )
            },
            envSupplier = {
                linkedMapOf<String, Any?>("year" to 200, "month" to 3)
            },
        )

        assertEquals(1, world.getState().meta["isunited"])
        assertEquals(safeConstant.id, world.getNationById(1)?.capitalCityId)
        assertEquals(999_999, world.getGeneralById(1)?.gold)
        assertEquals(invaderConstants.size, world.listNations().count { it.name.startsWith("ⓞ") })
        val createdInvaders = world.listGenerals().filter { it.npcState == 9 }
        assertEquals(invaderConstants.size * 11, createdInvaders.size)
        assertTrue(createdInvaders.all { "specage" !in it.meta })
        assertTrue(createdInvaders.all { "specage2" !in it.meta })
        assertEquals(invaderConstants.size + 1, recorder.eventInserts().size)
        assertEquals(false, recorder.kvDirty()[KvKey("game_env", "game_env", "block_change_scout")])
        assertTrue(unlocked)
        assertTrue(world.peekLogs().any { it.text.contains("각지의 이민족들이 <M>궐기</>합니다!") })
        val firstInvader = world.listNations().first { it.name.startsWith("ⓞ") }
        assertEquals(1, world.getDiplomacy(1, firstInvader.id)?.state)
        assertEquals(24, world.getDiplomacy(firstInvader.id, 1)?.term)
        val invaderOwnedCity = world.listCities().first { it.nationId == firstInvader.id }
        assertEquals(360_000, invaderOwnedCity.populationMax)
        assertEquals(invaderOwnedCity.populationMax, invaderOwnedCity.population)
        assertEquals(100_000, invaderOwnedCity.defenceMax)
        assertEquals(10_000, invaderOwnedCity.wallMax)

        val restartedStore = EventStore()
        recorder.eventInserts().forEach { row ->
            restartedStore.loadRaw(
                row.id,
                row.targetCode,
                row.priority,
                Json.parseToJsonElement(row.condition),
                Json.parseToJsonElement(row.action),
            )
        }
        val restartedActions = restartedStore.rowsFor(EventTarget.MONTH).flatMap { row -> row.actions.map { it.name } }
        assertEquals(invaderConstants.size, restartedActions.count { it == "AutoDeleteInvader" })
        assertEquals(1, restartedActions.count { it == "InvaderEnding" })
        assertFalse(restartedActions.any { it == "RaiseInvader" })
    }
}
