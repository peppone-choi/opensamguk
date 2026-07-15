package opensamguk.engine.world

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.City
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.world.CityConstRegistry
import opensamguk.logic.world.CreateManyNPCAction
import opensamguk.logic.world.checkEmperior
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

    @Test
    fun `checkEmperior uses scenario static city count instead of loaded city row count`() {
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state = TurnWorldState(
                    id = 1,
                    currentYear = 200,
                    currentMonth = 5,
                    tickSeconds = 60,
                    lastTurnTime = Instant.EPOCH,
                    meta = linkedMapOf("map" to "miniche", "isunited" to 0),
                ),
                nations = listOf(Nation(id = 1, name = "촉", color = "#000", level = 1)),
                cities = listOf(City(id = 1, name = "낙양", nationId = 1, level = 5)),
            ),
        )
        val context = WorldActionContext(
            env = mutableMapOf("year" to 200, "month" to 5),
            world = world,
            recorder = ChangeRecorder(),
            pipeline = GeneralActionPipeline(),
        )

        checkEmperior(context)

        assertEquals(78, context.totalCityCount())
        assertEquals(0, world.getState().meta["isunited"])
        assertEquals(emptyList(), world.consumeDirtyState().logs)
    }

    @Test
    fun `checkEmperior reaching PHP 725 completes national history and isunited writes`() {
        val cityConst = CityConstRegistry.of("miniche")
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state = TurnWorldState(
                    id = 1,
                    currentYear = 200,
                    currentMonth = 5,
                    tickSeconds = 60,
                    lastTurnTime = Instant.EPOCH,
                    meta = linkedMapOf("map" to "miniche", "isunited" to 0),
                ),
                nations = listOf(Nation(id = 1, name = "촉", color = "#000", level = 1)),
                cities = cityConst.all().keys.map { City(id = it, name = "city$it", nationId = 1, level = 5) },
            ),
        )
        val context = WorldActionContext(
            env = mutableMapOf("year" to 200, "month" to 5),
            world = world,
            recorder = ChangeRecorder(),
            pipeline = GeneralActionPipeline(),
        )

        checkEmperior(context)

        assertEquals(2, world.getState().meta["isunited"])
        assertEquals(
            listOf("<C>●</>200년 5월:<D><b>촉</b></>이 전토를 통일"),
            world.consumeDirtyState().logs.filter { it.scope == "nation" && it.category == "history" }.map { it.text },
        )
    }

    @Test
    fun `national history context applies PHP default YEAR_MONTH formatting`() {
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state = TurnWorldState(
                    id = 1,
                    currentYear = 200,
                    currentMonth = 5,
                    tickSeconds = 60,
                    lastTurnTime = Instant.EPOCH,
                    meta = linkedMapOf("isunited" to 0),
                ),
                nations = listOf(Nation(id = 1, name = "촉", color = "#000", level = 1)),
                cities = listOf(City(id = 1, name = "city1", nationId = 1, level = 5)),
            ),
        )
        val context = WorldActionContext(
            env = mutableMapOf("year" to 200, "month" to 5),
            world = world,
            recorder = ChangeRecorder(),
            pipeline = GeneralActionPipeline(),
        )

        context.pushNationalHistoryLog(1, "<D><b>촉</b></>이 전토를 통일")

        assertEquals(
            listOf("<C>●</>200년 5월:<D><b>촉</b></>이 전토를 통일"),
            world.consumeDirtyState().logs.filter { it.scope == "nation" && it.category == "history" }.map { it.text },
        )
    }
}
