package opensamguk.engine.world

import opensamguk.common.world.WorldId
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.City
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.KvKey
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.logic.betting.BettingInfo
import opensamguk.logic.event.DeleteEventContext
import opensamguk.logic.event.EventCondition
import opensamguk.logic.event.EventDispatcher
import opensamguk.logic.event.EventStore
import opensamguk.logic.event.EventTarget
import opensamguk.logic.event.OpenNationBettingAction
import opensamguk.logic.event.WorldActions
import opensamguk.logic.stats.GeneralActionPipeline
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorldActionContextNationBettingTest {

    @Test
    fun `open schedules destroy nation finish and the live dispatcher closes it`() {
        val state = TurnWorldState(
            id = 4,
            currentYear = 200,
            currentMonth = 3,
            tickSeconds = 3600,
            lastTurnTime = Instant.parse("0200-03-01T00:00:00Z"),
        )
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state = state,
                generals = listOf(
                    TurnGeneral(
                        id = 10,
                        userId = "77",
                        name = "유비",
                        nationId = 1,
                        cityId = 1,
                        troopId = 0,
                        stats = GeneralStats(70, 70, 70),
                        experience = 0,
                        dedication = 0,
                        officerLevel = 12,
                        npcState = 0,
                        turnTime = state.lastTurnTime,
                    ),
                ),
                cities = listOf(City(id = 1, name = "성도", nationId = 1, level = 5)),
                nations = listOf(Nation(id = 1, name = "촉", color = "#ff0000", level = 1, power = 500)),
                worldId = WorldId(4),
            ),
        )
        var messageId = 0
        val recorder = ChangeRecorder(messageIdAllocator = { ++messageId })
        val store = EventStore()
        store.bindMutationSink(recorder::recordEventMutation)
        val pipeline = GeneralActionPipeline()
        val openEnv = mutableMapOf<String, Any?>(
            "year" to 200,
            "month" to 3,
            DeleteEventContext.ENV_KEY to store,
        )
        val openContext = WorldActionContext(openEnv, world, recorder, pipeline)

        OpenNationBettingAction(nationCnt = 1, bonusPoint = 2_000).run(openContext)

        val finishRow = store.rowsFor(EventTarget.DESTROY_NATION).single()
        assertTrue(finishRow.condition is EventCondition.RemainNation)
        assertEquals(listOf("FinishNationBetting", "DeleteEvent"), finishRow.actions.map { it.name })
        assertEquals(1, recorder.bettingInserts().size)
        assertEquals(1, recorder.createdMessages().size)

        val dispatcher = EventDispatcher(store, WorldActions.register(opensamguk.logic.event.EventActionFactory()))
        dispatcher.run(
            target = EventTarget.DESTROY_NATION,
            contextFactory = { env ->
                env[DeleteEventContext.ENV_KEY] = store
                WorldActionContext(env, world, recorder, pipeline)
            },
            envSupplier = {
                linkedMapOf<String, Any?>(
                    "year" to 200,
                    "month" to 3,
                    EventCondition.REMAIN_NATION_COUNT_KEY to 1,
                )
            },
        )

        @Suppress("UNCHECKED_CAST")
        val persisted = recorder.kvDirty()[KvKey("betting", "betting", "id_1")] as Map<String, Any?>
        val info = requireNotNull(BettingInfo.fromKvMap(persisted))
        assertTrue(info.finished)
        assertEquals(listOf(0), info.winner)
        assertFalse(store.allRows().any { it.id == finishRow.id })
        assertEquals(listOf(finishRow.id), recorder.eventDeletes())
        assertTrue(world.peekLogs().any { "내기의 결과가 나왔습니다!" in it.text })
    }
}
