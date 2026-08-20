package opensamguk.engine.turn

import opensamguk.common.rng.MustNotBeReachedException
import opensamguk.common.world.WorldId
import opensamguk.engine.config.DaemonLoopConfig
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.actions.nation.NationActionResolverRegistry
import opensamguk.logic.ai.ChosenCommand
import opensamguk.logic.diplomacy.DiplomacyState
import opensamguk.logic.stats.GeneralActionPipeline
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProcessNationCommandInstantTest {

    private val now = Instant.parse("0200-03-01T12:00:00Z")

    @AfterTest
    fun resetResolvers() = NationActionResolverRegistry.clear()

    @Test
    fun `instant execution uses full constraints and leaves turn-last untouched`() {
        installDaemonResolvers()
        val world = world(diplomacyState = DiplomacyState.WAR)
        val recorder = ChangeRecorder()
        val processor = processor(world, recorder)

        val result = processor.processInstant(
            generalId = 10,
            nationCommand = ChosenCommand(
                "che_종전수락",
                linkedMapOf("destNationID" to 2L, "destGeneralID" to 20L),
            ),
        )

        assertIs<ProcessNationCommand.InstantResult.Allowed>(result)
        assertEquals(DiplomacyState.TRADE, world.getDiplomacy(1, 2)?.state)
        assertEquals(DiplomacyState.TRADE, world.getDiplomacy(2, 1)?.state)
        assertEquals(2, recorder.diplomacyUpdateDirty().size)
        assertTrue(world.getNationById(1)!!.meta.keys.none { it.startsWith("turn_last_") })
    }

    @Test
    fun `instant execution returns PHP getFailString for all three accept commands`() {
        installDaemonResolvers()
        val cases = listOf(
            InstantDenialCase(
                actionCode = "che_불가침수락",
                args = linkedMapOf(
                    "destNationID" to 2,
                    "destGeneralID" to 20,
                    "year" to 201,
                    "month" to 3,
                ),
                diplomacyState = DiplomacyState.WAR,
                reason = "아국과 이미 교전중입니다. 불가침 수락 실패.",
            ),
            InstantDenialCase(
                actionCode = "che_종전수락",
                args = linkedMapOf("destNationID" to 2, "destGeneralID" to 20),
                diplomacyState = DiplomacyState.TRADE,
                reason = "상대국과 선포, 전쟁중이지 않습니다. 종전 수락 실패.",
            ),
            InstantDenialCase(
                actionCode = "che_불가침파기수락",
                args = linkedMapOf("destNationID" to 2, "destGeneralID" to 20),
                diplomacyState = DiplomacyState.TRADE,
                reason = "불가침 중인 상대국에게만 가능합니다. 불가침 파기 수락 실패.",
            ),
        )

        for (case in cases) {
            val world = world(diplomacyState = case.diplomacyState)
            val recorder = ChangeRecorder()

            val result = processor(world, recorder).processInstant(
                generalId = 10,
                nationCommand = ChosenCommand(case.actionCode, case.args),
            )

            val denied = assertIs<ProcessNationCommand.InstantResult.Denied>(result, case.actionCode)
            assertEquals(case.reason, denied.reason, case.actionCode)
            assertTrue(recorder.diplomacyUpdateDirty().isEmpty(), case.actionCode)
            assertEquals(case.diplomacyState, world.getDiplomacy(1, 2)?.state, case.actionCode)
            assertTrue(
                world.getNationById(1)!!.meta.keys.none { it.startsWith("turn_last_") },
                case.actionCode,
            )
        }
    }

    @Test
    fun `instant execution formats invalid arguments with each resolved command name`() {
        val cases = listOf(
            Triple(
                "che_불가침수락",
                linkedMapOf<String, Any?>(
                    "destNationID" to 2,
                    "destGeneralID" to 20,
                    "year" to 183,
                    "month" to 3,
                ),
                "인자가 올바르지 않습니다. 불가침 수락 실패.",
            ),
            Triple(
                "che_종전수락",
                linkedMapOf<String, Any?>("destNationID" to 0, "destGeneralID" to 20),
                "인자가 올바르지 않습니다. 종전 수락 실패.",
            ),
            Triple(
                "che_불가침파기수락",
                linkedMapOf<String, Any?>("destNationID" to 2, "destGeneralID" to 10),
                "인자가 올바르지 않습니다. 불가침 파기 수락 실패.",
            ),
        )

        for ((actionCode, args, reason) in cases) {
            val recorder = ChangeRecorder()
            val result = processor(world(DiplomacyState.WAR), recorder).processInstant(
                generalId = 10,
                nationCommand = ChosenCommand(actionCode, args),
            )

            assertEquals(
                reason,
                assertIs<ProcessNationCommand.InstantResult.Denied>(result, actionCode).reason,
                actionCode,
            )
            assertTrue(recorder.diplomacyUpdateDirty().isEmpty(), actionCode)
        }
    }

    @Test
    fun `instant no-aggression treats prior displayed months as stale after arg validation`() {
        val cases = listOf(200 to 2, 199 to 12)

        for ((year, month) in cases) {
            val recorder = ChangeRecorder()
            val result = processor(world(DiplomacyState.TRADE), recorder).processInstant(
                generalId = 10,
                nationCommand = ChosenCommand(
                    "che_불가침수락",
                    linkedMapOf(
                        "destNationID" to 2,
                        "destGeneralID" to 20,
                        "year" to year,
                        "month" to month,
                    ),
                ),
            )

            assertEquals(
                "이미 기한이 지났습니다. 불가침 수락 실패.",
                assertIs<ProcessNationCommand.InstantResult.Denied>(result).reason,
                "$year/$month",
            )
            assertTrue(recorder.diplomacyUpdateDirty().isEmpty(), "$year/$month")
        }
    }

    @Test
    fun `instant execution rejects commands outside the three diplomatic accepts`() {
        val result = processor(world(DiplomacyState.TRADE), ChangeRecorder()).processInstant(
            generalId = 10,
            nationCommand = ChosenCommand("che_선전포고", linkedMapOf("destNationID" to 2)),
        )

        assertEquals(
            "처리할 수 없습니다.",
            assertIs<ProcessNationCommand.InstantResult.Denied>(result).reason,
        )
    }

    @Test
    fun `instant execution enforces the zero-draw NoRng path`() {
        installDaemonResolvers()
        NationActionResolverRegistry.register("che_종전수락") { ctx ->
            ctx.rng.nextInt(0, 2)
        }
        val world = world(diplomacyState = DiplomacyState.WAR)

        assertFailsWith<MustNotBeReachedException> {
            processor(world, ChangeRecorder()).processInstant(
                generalId = 10,
                nationCommand = ChosenCommand(
                    "che_종전수락",
                    linkedMapOf("destNationID" to 2, "destGeneralID" to 20),
                ),
            )
        }
    }

    private fun processor(world: InMemoryTurnWorld, recorder: ChangeRecorder) = ProcessNationCommand(
        world = world,
        recorder = recorder,
        hiddenSeed = "unused-by-instant",
        registry = CommandRegistry(GeneralActionPipeline()),
        startYear = 184,
    )

    private fun world(diplomacyState: Int): InMemoryTurnWorld = InMemoryTurnWorld(
        WorldSnapshot(
            state = TurnWorldState(
                id = 1,
                currentYear = 200,
                currentMonth = 3,
                tickSeconds = 3600,
                lastTurnTime = now,
                config = linkedMapOf("mapName" to "che"),
            ),
            generals = listOf(
                TurnGeneral(
                    id = 10,
                    name = "유비",
                    nationId = 1,
                    cityId = 5,
                    troopId = 0,
                    stats = GeneralStats(80, 70, 60),
                    experience = 0,
                    dedication = 0,
                    officerLevel = 12,
                    turnTime = now,
                ),
                TurnGeneral(
                    id = 20,
                    name = "조조",
                    nationId = 2,
                    cityId = 8,
                    troopId = 0,
                    stats = GeneralStats(80, 70, 60),
                    experience = 0,
                    dedication = 0,
                    officerLevel = 12,
                    turnTime = now,
                ),
            ),
            cities = listOf(
                City(id = 5, name = "업", nationId = 1, level = 6, supplyState = 1),
                City(id = 8, name = "허창", nationId = 2, level = 6, supplyState = 1),
            ),
            nations = listOf(
                Nation(id = 1, name = "촉", color = "#0f0"),
                Nation(id = 2, name = "위", color = "#00f"),
            ),
            diplomacy = listOf(
                TurnDiplomacy(1, 2, state = diplomacyState, term = 9),
                TurnDiplomacy(2, 1, state = diplomacyState, term = 9),
            ),
            worldId = WorldId(1),
        ),
    )

    private fun installDaemonResolvers() {
        val method = DaemonLoopConfig::class.java.getDeclaredMethod(
            "installNationActionResolvers",
            GeneralActionPipeline::class.java,
        )
        method.isAccessible = true
        method.invoke(DaemonLoopConfig(), GeneralActionPipeline())
    }

    private data class InstantDenialCase(
        val actionCode: String,
        val args: LinkedHashMap<String, Any?>,
        val diplomacyState: Int,
        val reason: String,
    )
}
