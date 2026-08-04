package opensamguk.engine.turn

import opensamguk.common.world.WorldId
import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.ai.ChosenCommand
import opensamguk.logic.stats.GeneralActionPipeline
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HumanAutorunLifecycleTest {

    private val t0 = Instant.parse("0200-01-01T12:34:00Z")
    private val pipeline = GeneralActionPipeline()
    private val registry = CommandRegistry(pipeline)

    private val hiddenSeed = "00000000000000000000000000000000"
    private val year = 200
    private val month = 1
    private val startYear = 184
    private val currentYearMonth = year * 12 + month - 1

    @Test
    fun `human before autorun limit lets AI replace the reserved general turn`() {
        var hookCalled = false
        val fixture = fixture(
            general = general(meta = linkedMapOf("autorun_limit" to currentYearMonth + 1)),
            aiHook = { _, _ ->
                hookCalled = true
                ChosenCommand("che_농지개간", emptyMap())
            },
        )

        val outcome = fixture.handler.handle(42, ReservedTurn("휴식", ""), year, month, "12:34")

        assertTrue(hookCalled, "strictly future autorun_limit creates PHP's GeneralAI for a human")
        assertEquals("che_농지개간", outcome.definition.key, "the shared AI gate replaces the general reservation")
    }

    @Test
    fun `human at autorun limit boundary keeps the reserved general turn`() {
        var hookCalled = false
        val fixture = fixture(
            general = general(meta = linkedMapOf("autorun_limit" to currentYearMonth)),
            aiHook = { _, _ ->
                hookCalled = true
                ChosenCommand("che_농지개간", emptyMap())
            },
        )

        val outcome = fixture.handler.handle(42, ReservedTurn("휴식", ""), year, month, "12:34")

        assertFalse(hookCalled, "PHP uses strict currentYM < autorun_limit, never <=")
        assertEquals("휴식", outcome.definition.key)
    }

    @Test
    fun `human before autorun limit lets AI choose the nation turn`() {
        var nationHookCalled = false
        val fixture = fixture(
            general = general(
                officerLevel = 5,
                meta = linkedMapOf("autorun_limit" to currentYearMonth + 1),
            ),
            withNationProcessor = true,
            chooseNationTurn = { _, reserved ->
                nationHookCalled = true
                ChosenCommand(reserved.actionCode, emptyMap())
            },
        )

        fixture.lifecycle.runTick(t0.plusSeconds(1))

        assertTrue(nationHookCalled, "the same PHP \$ai eligibility gates the nation pass")
    }

    @Test
    fun `human autorun keeps the nested nation setting gate`() {
        var nationHookCalled = false
        val fixture = fixture(
            general = general(
                officerLevel = 5,
                meta = linkedMapOf(
                    "autorun_limit" to currentYearMonth + 1,
                    "use_auto_nation_turn" to 0,
                ),
            ),
            withNationProcessor = true,
            chooseNationTurn = { _, reserved ->
                nationHookCalled = true
                ChosenCommand(reserved.actionCode, emptyMap())
            },
        )

        fixture.lifecycle.runTick(t0.plusSeconds(1))

        assertFalse(nationHookCalled, "use_auto_nation_turn=0 prevents only the nation AI substitution")
    }

    @Test
    fun `human non-rest general turn refreshes autorun limit using PHP integer division`() {
        val fixture = fixture(
            general = general(),
            generalReserved = ReservedTurn("che_농지개간", ""),
            limitMinutes = 125,
            turnTerm = 60,
        )

        fixture.lifecycle.runTick(t0.plusSeconds(1))

        assertAutorunLimit(fixture, currentYearMonth + 2)
    }

    @Test
    fun `human non-rest nation turn refreshes autorun limit even when general turn rests`() {
        val fixture = fixture(
            general = general(officerLevel = 5),
            withNationProcessor = true,
            generalReserved = ReservedTurn("휴식", ""),
            nationReserved = ReservedTurn("unregistered_nation_turn", ""),
            limitMinutes = 125,
            turnTerm = 60,
        )

        fixture.lifecycle.runTick(t0.plusSeconds(1))

        assertAutorunLimit(fixture, currentYearMonth + 2)
    }

    @Test
    fun `autorun limit refresh requires a human non-rest reserved turn`() {
        val npcFixture = fixture(
            general = general(npcState = 2),
            generalReserved = ReservedTurn("che_농지개간", ""),
            limitMinutes = 125,
            turnTerm = 60,
        )
        npcFixture.lifecycle.runTick(t0.plusSeconds(1))

        val restFixture = fixture(
            general = general(),
            generalReserved = ReservedTurn("휴식", ""),
            limitMinutes = 125,
            turnTerm = 60,
        )
        restFixture.lifecycle.runTick(t0.plusSeconds(1))

        assertNull(npcFixture.world.getGeneralById(42)!!.meta["autorun_limit"], "NPC turns never refresh the human window")
        assertNull(restFixture.world.getGeneralById(42)!!.meta["autorun_limit"], "a rest-only turn is not a reserved-action refresh")
    }

    private fun assertAutorunLimit(fixture: Fixture, expected: Int) {
        val stored = fixture.world.getGeneralById(42)!!.meta["autorun_limit"] as? Number
        assertEquals(expected, stored?.toInt(), "world state carries the PHP currentYM + intdiv window")
        assertTrue(
            fixture.handler.recorder.generalPatches().any { patch ->
                patch.id == 42 && (patch.meta["autorun_limit"] as? Number)?.toInt() == expected
            },
            "the refresh is a ChangeRecorder delta, not an inline daemon write",
        )
        assertTrue(fixture.world.consumeDirtyState().generals.isEmpty(), "the world dirty set remains unused by the daemon")
    }

    private fun fixture(
        general: TurnGeneral,
        generalReserved: ReservedTurn = ReservedTurn("휴식", ""),
        nationReserved: ReservedTurn = ReservedTurn("휴식", ""),
        withNationProcessor: Boolean = false,
        chooseNationTurn: ((Int, ReservedTurn) -> ChosenCommand)? = null,
        aiHook: ((Int, ReservedTurn) -> ChosenCommand)? = null,
        limitMinutes: Int? = null,
        turnTerm: Int = 60,
    ): Fixture {
        val config = limitMinutes?.let { linkedMapOf<String, Any?>("autorun_user" to linkedMapOf("limit_minutes" to it)) }
            ?: emptyMap()
        val state = TurnWorldState(
            id = 1,
            currentYear = year,
            currentMonth = month,
            tickSeconds = 3600,
            lastTurnTime = t0,
            config = config,
        )
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state = state,
                generals = listOf(general),
                cities = listOf(city()),
                nations = listOf(nation()),
                worldId = WorldId(state.id),
            ),
        )
        val recorder = ChangeRecorder()
        val handler = ReservedTurnHandler(
            world = world,
            registry = registry,
            hiddenSeed = hiddenSeed,
            startYear = startYear,
            aiHook = aiHook,
            recorder = recorder,
        )
        val lifecycle = TurnDaemonLifecycle(
            world = world,
            handler = handler,
            nationProcessor = if (withNationProcessor) ProcessNationCommand(world, recorder, hiddenSeed) else null,
            reservedNationActionOf = { _, _ -> nationReserved },
            chooseNationTurn = chooseNationTurn,
            lifecycleEnvOf = { current, date ->
                LifecycleEnv(
                    baselineKillturn = 80,
                    year = current.currentYear,
                    month = current.currentMonth,
                    turnTerm = turnTerm,
                    turnTimeHm = date,
                )
            },
            reservedActionOf = { generalReserved },
        )
        return Fixture(world, handler, lifecycle)
    }

    private fun general(
        officerLevel: Int = 0,
        npcState: Int = 0,
        meta: Map<String, Any?> = linkedMapOf(),
    ): TurnGeneral = TurnGeneral(
        id = 42,
        name = "g42",
        nationId = 1,
        cityId = 7,
        troopId = 0,
        stats = GeneralStats(leadership = 70, strength = 70, intelligence = 80),
        experience = 0,
        dedication = 0,
        officerLevel = officerLevel,
        gold = 100_000,
        rice = 1_000,
        npcState = npcState,
        turnTime = t0,
        meta = linkedMapOf<String, Any?>(
            "explevel" to 10,
            "intel_exp" to 3,
            "max_domestic_critical" to 0.0,
            "killturn" to 80,
        ).apply { putAll(meta) },
    )

    private fun city(): City = City(
        id = 7,
        name = "c7",
        nationId = 1,
        level = 5,
        agriculture = 1_000,
        agricultureMax = 20_000,
        commerce = 1_000,
        commerceMax = 20_000,
        supplyState = 1,
        frontState = 0,
        meta = linkedMapOf("trust" to 50),
    )

    private fun nation(): Nation = Nation(
        id = 1,
        name = "n1",
        color = "#000",
        level = 2,
        capitalCityId = 7,
    )

    private data class Fixture(
        val world: InMemoryTurnWorld,
        val handler: ReservedTurnHandler,
        val lifecycle: TurnDaemonLifecycle,
    )
}
