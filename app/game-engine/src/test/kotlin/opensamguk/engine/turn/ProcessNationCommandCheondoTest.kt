package opensamguk.engine.turn

import opensamguk.common.constants.CityConst
import opensamguk.common.world.WorldId
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.ai.ChosenCommand
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.event.StaticEventHandler
import opensamguk.logic.stats.GeneralActionPipeline
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ProcessNationCommandCheondoTest {

    private val now = Instant.parse("0200-03-01T00:00:00Z")

    private data class StaticEventObservation(
        val generalId: Int,
        val noDestGeneral: Boolean,
        val month: Any?,
        val destCityId: Any?,
        val inheritanceWriteCount: Int,
        val completedLogScopes: List<String>,
    )

    @AfterTest
    fun clearStaticEventHandlers() = StaticEventHandler.clear()

    private fun world(
        ownedCityIds: List<Int>,
        treasury: Int = 100_000_000,
        userId: String? = "77",
        power: Int = 9_000,
        tech: Double = 1_234.5,
        isUnited: Int = 0,
    ): InMemoryTurnWorld =
        InMemoryTurnWorld(
            WorldSnapshot(
                state = TurnWorldState(
                    id = 1,
                    currentYear = 200,
                    currentMonth = 3,
                    tickSeconds = 3600,
                    lastTurnTime = now,
                    meta = linkedMapOf("isunited" to isUnited),
                ),
                generals = listOf(
                    TurnGeneral(
                        id = 10,
                        userId = userId,
                        name = "유비",
                        nationId = 1,
                        cityId = 1,
                        troopId = 0,
                        stats = GeneralStats(80, 70, 60),
                        experience = 0,
                        dedication = 0,
                        officerLevel = 12,
                        gold = 100,
                        turnTime = now,
                    ),
                ),
                cities = ownedCityIds.map { cityId ->
                    City(
                        id = cityId,
                        name = checkNotNull(CityConst.byId(cityId)).name,
                        nationId = 1,
                        level = 6,
                        supplyState = 1,
                    )
                },
                nations = listOf(
                    Nation(
                        id = 1,
                        name = "촉",
                        color = "#0f0",
                        capitalCityId = 1,
                        gold = treasury,
                        rice = treasury,
                        power = power,
                        tech = tech,
                        meta = linkedMapOf("capset" to 3),
                    ),
                ),
                worldId = WorldId(1),
            ),
        )

    private fun processor(world: InMemoryTurnWorld, recorder: ChangeRecorder = ChangeRecorder()): ProcessNationCommand = ProcessNationCommand(
        world = world,
        recorder = recorder,
        hiddenSeed = "seed",
        registry = CommandRegistry(GeneralActionPipeline()),
        startYear = 184,
    )

    @Test
    fun `adjacent owned destination uses one-hop cost two turn stack and matching reward`() {
        val world = world(ownedCityIds = listOf(1, 9))
        val trialWrites = mutableListOf<List<Any?>>()
        val recorder = ChangeRecorder(kvWriteObserver = { key, value ->
            if (key == KvKey("nation_env", "1", "last천도Trial")) {
                val trial = value as? List<*> ?: error("last천도Trial must be a list")
                trialWrites += trial.map { it }
            }
        })
        val proc = processor(world, recorder)
        val command = ChosenCommand("che_천도", linkedMapOf("destCityID" to 9))
        val expectedTrial = listOf<Any?>(12, now.toString())
        val staticEvents = mutableListOf<StaticEventObservation>()
        StaticEventHandler.register("che_천도") { general, destGeneral, env, params ->
            staticEvents += StaticEventObservation(
                generalId = general.id,
                noDestGeneral = destGeneral == null,
                month = env["month"],
                destCityId = params["destCityID"],
                inheritanceWriteCount = recorder.inheritanceKvWrites().size,
                completedLogScopes = world.peekLogs().takeLast(6).map { "${it.scope}:${it.category}" },
            )
        }

        var last = proc.process(10, 12, command, LastTurn(), 200, 3, "12:00")
        assertEquals("천도", last.command)
        assertEquals(1, last.term, "distance 1 must begin the distance*2 stack")
        assertEquals(1, world.getNationById(1)!!.capitalCityId)
        assertEquals(listOf(expectedTrial), trialWrites, "initial poll records last천도Trial")

        last = proc.process(10, 12, command, last, 200, 3, "12:00")
        assertEquals(2, last.term, "distance 1 must require a second reservation turn")
        assertEquals(1, world.getNationById(1)!!.capitalCityId)
        assertEquals(listOf(expectedTrial, expectedTrial), trialWrites, "progress poll records last천도Trial")

        last = proc.process(10, 12, command, last, 200, 3, "12:00")
        assertEquals(0, last.term)
        assertEquals(
            listOf(expectedTrial, expectedTrial, expectedTrial),
            trialWrites,
            "completion poll records last천도Trial before resolving",
        )
        assertEquals(9, world.getNationById(1)!!.capitalCityId)
        assertEquals(4, (world.getNationById(1)!!.meta["capset"] as Number).toInt())
        assertEquals(15, world.getGeneralById(10)!!.experience, "5 * (distance*2 + 1)")
        assertEquals(15, world.getGeneralById(10)!!.dedication, "5 * (distance*2 + 1)")
        assertEquals(100_000_000, world.getNationById(1)!!.gold, "천도 cost is a precondition, not a debit")
        assertEquals(100_000_000, world.getNationById(1)!!.rice, "천도 cost is a precondition, not a debit")
        assertEquals(9_000, world.getNationById(1)!!.power, "capital relocation preserves nation power")
        assertEquals(1_234.5, world.getNationById(1)!!.tech, "capital relocation preserves nation tech")
        assertEquals(
            listOf(3.0, null),
            recorder.inheritanceKvWrites().single().value,
            "successful 천도 records one active_action increment with its configured coefficient",
        )
        assertEquals("inheritance", recorder.inheritanceKvWrites().single().table)
        assertEquals("inheritance_77", recorder.inheritanceKvWrites().single().namespace)
        assertEquals("active_action", recorder.inheritanceKvWrites().single().key)
        assertEquals(
            listOf(
                LogEntryDraft(
                    "general",
                    "history",
                    "<C>●</>200년 3월:<G><b>남피</b></>로 <M>천도</>명령",
                    generalId = 10,
                    nationId = 1,
                    year = 200,
                    month = 3,
                    phase = 1,
                ),
                LogEntryDraft(
                    "general",
                    "action",
                    "<C>●</><Y>30품관</>으로 <C>승급</>하여 봉록이 <C>600</>으로 <C>상승</>했습니다!",
                    generalId = 10,
                    nationId = 1,
                    year = 200,
                    month = 3,
                    phase = 1,
                ),
                LogEntryDraft(
                    "general",
                    "action",
                    "<C>●</>3월:<G><b>남피</b></>로 천도했습니다. <1>12:00</>",
                    generalId = 10,
                    nationId = 1,
                    year = 200,
                    month = 3,
                    phase = 1,
                ),
                LogEntryDraft(
                    "nation",
                    "history",
                    "<C>●</>200년 3월:<Y>유비</>가 <G><b>남피</b></>로 <M>천도</> 명령",
                    nationId = 1,
                    year = 200,
                    month = 3,
                    phase = 1,
                ),
                LogEntryDraft(
                    "global",
                    "history",
                    "<C>●</>200년 3월:<S><b>【천도】</b></><D><b>촉</b></>이 <G><b>남피</b></>로 <M>천도</>하였습니다.",
                    generalId = 10,
                    nationId = 1,
                    year = 200,
                    month = 3,
                    phase = 1,
                ),
                LogEntryDraft(
                    "global",
                    "action",
                    "<C>●</>3월:<Y>유비</>가 <G><b>남피</b></>로 <M>천도</>를 명령하였습니다.",
                    generalId = 10,
                    nationId = 1,
                    year = 200,
                    month = 3,
                    phase = 1,
                ),
            ),
            world.peekLogs().takeLast(6),
            "PHP ActionLogger scopes remain in flush order",
        )
        assertEquals(
            listOf(
                StaticEventObservation(
                    generalId = 10,
                    noDestGeneral = true,
                    month = 3,
                    destCityId = 9,
                    inheritanceWriteCount = 1,
                    completedLogScopes = listOf(
                        "general:history",
                        "general:action",
                        "general:action",
                        "nation:history",
                        "global:history",
                        "global:action",
                    ),
                ),
            ),
            staticEvents,
            "static event fires once after inheritance and ordered logs",
        )
    }

    @Test
    fun `united world completes cheondo without active action inheritance write`() {
        val world = world(ownedCityIds = listOf(1, 9), isUnited = 1)
        val recorder = ChangeRecorder()
        val proc = processor(world, recorder)
        val command = ChosenCommand("che_천도", linkedMapOf("destCityID" to 9))

        var last = LastTurn()
        repeat(3) {
            last = proc.process(10, 12, command, last, 200, 3, "12:00")
        }

        assertEquals(0, last.term)
        assertEquals(9, world.getNationById(1)!!.capitalCityId)
        assertEquals(emptyList(), recorder.inheritanceKvWrites())
    }

    @Test
    fun `unreachable owned destination falls back to fifty and is denied by its full cost`() {
        val world = world(ownedCityIds = listOf(1, 30))
        val recorder = ChangeRecorder()
        val proc = processor(world, recorder)
        val before = world.getNationById(1)!!

        val result = proc.process(
            10,
            12,
            ChosenCommand("che_천도", linkedMapOf("destCityID" to 30)),
            LastTurn(),
            200,
            3,
            "12:00",
        )

        assertEquals(LastTurn(), result)
        assertEquals(before.capitalCityId, world.getNationById(1)!!.capitalCityId)
        assertEquals(0, world.getGeneralById(10)!!.experience)
        assertEquals(null, recorder.kvDirty()[KvKey("nation_env", "1", "last천도Trial")])
    }
}
