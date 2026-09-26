package opensamguk.engine.world

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.City
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.world.CreateManyNPCAction
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

class WorldActionContextRngTest {

    @Test
    fun `NPC nation candidate shuffle helper is deterministic but not a PHP parity claim`() {
        val hiddenSeed = "deterministic-event-shuffle"
        val year = 200
        val month = 1
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state = TurnWorldState(
                    id = 1,
                    currentYear = year,
                    currentMonth = month,
                    tickSeconds = 60,
                    lastTurnTime = Instant.EPOCH,
                    meta = linkedMapOf("hiddenSeed" to hiddenSeed),
                ),
                cities = (1..12).map { City(id = it, name = "c$it", nationId = 0, level = 5) },
                worldId = opensamguk.common.world.WorldId((TurnWorldState(
                    id = 1,
                    currentYear = year,
                    currentMonth = month,
                    tickSeconds = 60,
                    lastTurnTime = Instant.EPOCH,
                    meta = linkedMapOf("hiddenSeed" to hiddenSeed),
                )).id),
            ),
        )
        val context = WorldActionContext(
            env = mutableMapOf("year" to year, "month" to month),
            world = world,
            recorder = ChangeRecorder(),
            pipeline = GeneralActionPipeline(),
        )
        val candidates = world.listCities().sortedBy { it.id }.map { PerTurnOverlay.toLogicCity(it) }
        val first = context.shuffleNpcNationCandidates(candidates)
        val second = context.shuffleNpcNationCandidates(candidates)

        assertNotSame(candidates, first)
        assertEquals(candidates.map { it.id }, world.listCities().sortedBy { it.id }.map { it.id })
        assertEquals(candidates.map { it.id }.sorted(), first.map { it.id }.sorted())
        assertEquals(first.map { it.id }, second.map { it.id })
    }

    @Test
    fun `CreateManyNPC persists PHP formatted global action and typed history text`() {
        val year = 200
        val month = 5
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state = TurnWorldState(
                    id = 1,
                    currentYear = year,
                    currentMonth = month,
                    tickSeconds = 60,
                    lastTurnTime = Instant.EPOCH,
                    meta = linkedMapOf(
                        "hiddenSeed" to "8ebfeb6fa932a181ec9ef43b7473f4c9",
                        "startYear" to 184,
                        "turnterm" to 60,
                    ),
                ),
                cities = listOf(City(id = 1, name = "허창", nationId = 0, level = 5)),
                worldId = opensamguk.common.world.WorldId((TurnWorldState(
                    id = 1,
                    currentYear = year,
                    currentMonth = month,
                    tickSeconds = 60,
                    lastTurnTime = Instant.EPOCH,
                    meta = linkedMapOf(
                        "hiddenSeed" to "8ebfeb6fa932a181ec9ef43b7473f4c9",
                        "startYear" to 184,
                        "turnterm" to 60,
                    ),
                )).id),
            ),
        )
        val context = WorldActionContext(
            env = mutableMapOf("year" to year, "month" to month),
            world = world,
            recorder = ChangeRecorder(),
            pipeline = GeneralActionPipeline(),
        )

        CreateManyNPCAction(npcCount = 2, fillCnt = 0).run(context)

        val logs = world.consumeDirtyState().logs
        assertEquals(
            listOf("<C>●</>5월:장수 <C>2</>명이 <S>등장</>하였습니다."),
            logs.filter { it.scope == "global" && it.category == "action" }.map { it.text },
        )
        assertEquals(
            listOf("<R>★</>200년 5월:장수 <C>2</>명이 <S>등장</>했습니다."),
            logs.filter { it.scope == "global" && it.category == "history" }.map { it.text },
        )
    }
}
