package opensamguk.engine.run

import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.logic.event.EventAction
import opensamguk.logic.event.EventActionContext
import opensamguk.logic.event.EventActionFactory
import opensamguk.logic.event.EventCondition
import opensamguk.logic.event.EventDispatcher
import opensamguk.logic.event.EventStore
import opensamguk.logic.event.EventTarget
import opensamguk.logic.event.RawAction
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class LiveRemainNationEnvTest {
    private val t0 = Instant.parse("0200-01-01T00:00:00Z")

    @Test
    fun `RemainNation count is live across rows in one event dispatch`() {
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state = TurnWorldState(id = 1, currentYear = 200, currentMonth = 1, tickSeconds = 3600, lastTurnTime = t0),
                nations = listOf(Nation(id = 1, name = "촉", color = "#0a0"), Nation(id = 2, name = "위", color = "#00a")),
            ),
        )
        val log = mutableListOf<String>()
        val store = EventStore()
        store.insert("month", 9000, EventCondition.RemainNation("==", 2), listOf(RawAction("RemoveWei", emptyList())))
        store.insert("month", 9000, EventCondition.RemainNation("==", 1), listOf(RawAction("Mark", emptyList())))
        val factory = EventActionFactory()
            .register("RemoveWei") {
                object : EventAction {
                    override fun run(ctx: EventActionContext) {
                        world.removeNation(2)
                    }
                }
            }
            .register("Mark") {
                object : EventAction {
                    override fun run(ctx: EventActionContext) {
                        log.add("count=${ctx.env[EventCondition.REMAIN_NATION_COUNT_KEY]}")
                    }
                }
            }

        EventDispatcher(store, factory).run(EventTarget.MONTH) {
            LiveRemainNationEnv(
                linkedMapOf("year" to 200, "month" to 1, "phase" to 1),
                nationCount = { world.listNations().size },
            )
        }

        assertEquals(listOf("count=1"), log)
    }
}
