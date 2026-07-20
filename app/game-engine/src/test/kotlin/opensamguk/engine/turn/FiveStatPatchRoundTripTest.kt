package opensamguk.engine.turn

import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.stats.GeneralActionPipeline
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class FiveStatPatchRoundTripTest {
    private val t0 = Instant.parse("0200-01-01T00:00:00Z")

    private fun engineGeneral() = TurnGeneral(
        id = 10,
        name = "유비",
        nationId = 1,
        cityId = 5,
        troopId = 0,
        stats = GeneralStats(leadership = 80, strength = 70, intelligence = 60, politics = 44, charm = 55),
        experience = 0,
        dedication = 0,
        officerLevel = 1,
        gold = 100,
        rice = 100,
        turnTime = t0,
    )

    private fun world(general: TurnGeneral = engineGeneral()) = InMemoryTurnWorld(
        WorldSnapshot(
            state = TurnWorldState(id = 1, currentYear = 200, currentMonth = 1, tickSeconds = 3600, lastTurnTime = t0),
            generals = listOf(general),
            cities = listOf(City(id = 5, name = "업", nationId = 1, level = 6)),
            nations = listOf(Nation(id = 1, name = "촉", color = "#0f0")),
            worldId = opensamguk.common.world.WorldId((TurnWorldState(id = 1, currentYear = 200, currentMonth = 1, tickSeconds = 3600, lastTurnTime = t0)).id),
        ),
    )

    @Test
    fun `ReservedTurnHandler patch reflects logic politics and charm back to engine stats`() {
        val engine = engineGeneral()
        val logic = PerTurnOverlay.toLogicGeneral(engine).copy(politics = 77, charm = 88)
        val method = ReservedTurnHandler.Companion::class.java.getDeclaredMethod(
            "applyGeneralPatch",
            TurnGeneral::class.java,
            opensamguk.logic.domain.General::class.java,
        )
        method.isAccessible = true

        val patched = method.invoke(ReservedTurnHandler.Companion, engine, logic) as TurnGeneral

        assertEquals(77, patched.stats.politics)
        assertEquals(88, patched.stats.charm)
    }

    @Test
    fun `ProcessNationCommand patch reflects logic politics and charm back to engine stats`() {
        val engine = engineGeneral()
        val logic = PerTurnOverlay.toLogicGeneral(engine).copy(politics = 66, charm = 99)
        val processor = ProcessNationCommand(
            world = world(engine),
            recorder = ChangeRecorder(),
            hiddenSeed = "seed",
            registry = CommandRegistry(GeneralActionPipeline()),
            startYear = 200,
        )
        val method = ProcessNationCommand::class.java.getDeclaredMethod(
            "applyLogicToGeneral",
            TurnGeneral::class.java,
            opensamguk.logic.domain.General::class.java,
        )
        method.isAccessible = true

        val patched = method.invoke(processor, engine, logic) as TurnGeneral

        assertEquals(66, patched.stats.politics)
        assertEquals(99, patched.stats.charm)
    }

    @Test
    fun `ReservedTurnHandler rebirth preserves politics and charm`() {
        val engine = engineGeneral().copy(
            stats = GeneralStats(leadership = 80, strength = 70, intelligence = 60, politics = 33, charm = 77),
            age = 70,
        )
        val world = world(engine)
        val handler = ReservedTurnHandler(
            world = world,
            registry = CommandRegistry(GeneralActionPipeline()),
            hiddenSeed = "seed",
            startYear = 200,
        )
        val method = ReservedTurnHandler::class.java.getDeclaredMethod(
            "rebirth",
            TurnGeneral::class.java,
            LifecycleEnv::class.java,
        )
        method.isAccessible = true

        method.invoke(
            handler,
            engine,
            LifecycleEnv(baselineKillturn = 10, year = 200, month = 1, turnTerm = 60),
        )

        val reborn = world.getGeneralById(10)!!
        assertEquals(33, reborn.stats.politics)
        assertEquals(77, reborn.stats.charm)
    }
}
