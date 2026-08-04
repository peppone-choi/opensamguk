package opensamguk.engine.config

import opensamguk.common.world.WorldId
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.LogEntryDraft
import opensamguk.engine.turn.TurnDaemonLifecycle
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.logic.event.EventTarget
import opensamguk.logic.tick.CheckStatistic
import opensamguk.logic.tick.EventDispatcher
import opensamguk.logic.tick.GameDate
import opensamguk.logic.tick.MonthlyPipeline
import opensamguk.logic.tick.MonthlyRngFactory
import opensamguk.logic.tick.PostUpdateMonthly
import opensamguk.logic.tick.PreUpdateMonthly
import opensamguk.logic.tick.ServerClock
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals

class V1CalendarCatchupTest {

    private val startYear = 181
    private val turnTerm = 60

    @Test
    fun `four phase catch-up exposes the monthly boundary date to the following drain and AI`() {
        val replay = replay(listOf(4))

        assertEquals(
            listOf(
                GameDate(181, 1, 1),
                GameDate(181, 1, 2),
                GameDate(181, 1, 3),
                GameDate(181, 2, 1),
            ),
            replay.drainAndAiDates,
        )
        assertEquals(
            listOf(
                "PRE_MONTH:181/1/3|181/1/3",
                "MONTH:181/2/1|181/2/1",
                "POST:181/2/1",
            ),
            replay.events,
        )
        assertEquals(listOf(GameDate(181, 2, 1)), replay.months)
        assertEquals(GameDate(181, 2, 2), replay.finalDate)
    }

    @Test
    fun `twelve single phase ticks equal one twelve-boundary catch-up in month order and final date`() {
        val catchup = replay(listOf(12))
        val singles = replay(List(12) { 1 })
        val expectedMonths = (2..5).map { GameDate(181, it, 1) }

        assertEquals(expectedMonths, catchup.months)
        assertEquals(catchup.months, singles.months)
        assertEquals(catchup.events, singles.events)
        assertEquals(GameDate(181, 5, 1), catchup.finalDate)
        assertEquals(catchup.finalDate, singles.finalDate)
    }

    @Test
    fun `thirty six phase boundaries run twelve monthly pipelines and reach the next year`() {
        val replay = replay(listOf(36))
        val expectedMonths = (2..12).map { GameDate(181, it, 1) } + GameDate(182, 1, 1)

        assertEquals(expectedMonths, replay.months)
        assertEquals(12, replay.months.size)
        assertEquals(GameDate(182, 1, 1), replay.finalDate)
    }

    @Test
    fun `log dates are fixed at creation time across a multi-boundary catch-up`() {
        val start = ServerClock.cutTurn(Instant.parse("0181-01-01T00:00:00Z"), turnTerm)
        val world = world(start)

        world.pushLog(LogEntryDraft(scope = "global", category = "history", text = "p1"))
        world.setCurrentDate(181, 1, 2)
        world.pushLog(LogEntryDraft(scope = "global", category = "history", text = "p2"))
        world.setCurrentDate(181, 1, 3)
        world.pushLog(LogEntryDraft(scope = "global", category = "history", text = "p3"))
        world.setCurrentDate(181, 2, 1)
        world.pushLog(LogEntryDraft(scope = "global", category = "history", text = "m2"))
        world.pushLog(
            LogEntryDraft(
                scope = "global",
                category = "history",
                text = "explicit",
                year = 190,
                month = 7,
                phase = 2,
            ),
        )

        val payload = DatabaseHooks.toFlushPayload(world, ChangeRecorder(), world.consumeDirtyState())

        assertEquals(
            listOf(
                GameDate(181, 1, 1),
                GameDate(181, 1, 2),
                GameDate(181, 1, 3),
                GameDate(181, 2, 1),
                GameDate(190, 7, 2),
            ),
            payload.logEntries.map { GameDate(it.year, it.month, it.phase) },
        )
    }

    private fun replay(boundarySteps: List<Int>): Replay {
        val start = ServerClock.cutTurn(Instant.parse("0181-01-01T00:00:00Z"), turnTerm)
        val world = world(start)
        val drainAndAiDates = mutableListOf<GameDate>()
        val events = mutableListOf<String>()
        val months = mutableListOf<GameDate>()
        val pipeline = MonthlyPipeline(
            monthlyRngFactory = MonthlyRngFactory { _, _ -> Unit },
            clock = v1MonthlyClock(world, startYear, turnTerm),
            preUpdateMonthly = PreUpdateMonthly { true },
            checkStatistic = CheckStatistic { },
            postUpdateMonthly = PostUpdateMonthly {
                events += "POST:${stateDate(world).asText()}"
            },
        )
        val driver = TurnDaemonLifecycle.MonthBoundaryDriver(
            drain = { drainAndAiDates += stateDate(world) },
            runMonth = { nextTurn ->
                val oldDate = ServerClock.turnDate(
                    ServerClock.subTurn(nextTurn, turnTerm),
                    startYear,
                    start,
                    turnTerm,
                )
                pipeline.runMonth(
                    nextTurn = nextTurn,
                    startYear = startYear,
                    startTime = start,
                    turnTerm = turnTerm,
                    oldYear = oldDate.year,
                    oldMonth = oldDate.month,
                    oldPhase = oldDate.phase,
                    dispatcher = EventDispatcher { target, env ->
                        events += "${target.name}:${GameDate(env.year, env.month, env.phase).asText()}|${stateDate(world).asText()}"
                        if (target == EventTarget.MONTH) {
                            months += GameDate(env.year, env.month, env.phase)
                        }
                    },
                )
            },
            runMonthWhen = { nextTurn -> dateAt(nextTurn, start).phase == 1 },
            advanceNonMonthlyBoundary = { nextTurn ->
                dateAt(nextTurn, start).let { date ->
                    world.setCurrentDate(date.year, date.month, date.phase)
                }
            },
        )
        var previous = start
        boundarySteps.forEach { steps ->
            val now = ServerClock.addTurn(previous, turnTerm, steps)
            driver.run(previous, now, turnTerm, isUnitedState = 0)
            world.setLastTurnTime(now)
            previous = now
        }
        return Replay(drainAndAiDates, events, months, stateDate(world))
    }

    private fun dateAt(nextTurn: Instant, start: Instant): GameDate =
        ServerClock.turnDate(nextTurn, startYear, start, turnTerm)

    private fun world(start: Instant): InMemoryTurnWorld = InMemoryTurnWorld(
        WorldSnapshot(
            state = TurnWorldState(
                id = 1,
                currentYear = startYear,
                currentMonth = 1,
                currentPhase = 1,
                tickSeconds = turnTerm * 60,
                lastTurnTime = start,
                meta = mapOf("startYear" to startYear, "startTime" to start.toString()),
            ),
            worldId = WorldId(1),
        ),
    )

    private fun stateDate(world: InMemoryTurnWorld): GameDate = world.getState().let {
        GameDate(it.currentYear, it.currentMonth, it.currentPhase)
    }

    private fun GameDate.asText(): String = "$year/$month/$phase"

    private data class Replay(
        val drainAndAiDates: List<GameDate>,
        val events: List<String>,
        val months: List<GameDate>,
        val finalDate: GameDate,
    )
}
