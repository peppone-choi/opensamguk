package opensamguk.engine.turn

import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.ai.AiGeneralView
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.stats.GeneralActionPipeline
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiRuntimeWiringTest {
    private val turnTime = Instant.parse("0200-12-01T00:00:00Z")
    private val registry = CommandRegistry(GeneralActionPipeline())

    private fun general(
        id: Int = 1,
        officerLevel: Int = 1,
        role: GeneralRole = GeneralRole(),
        meta: Map<String, Any?> = linkedMapOf("killturn" to 1000, "belong" to 1),
    ) = TurnGeneral(
        id = id,
        name = "g$id",
        nationId = 1,
        cityId = 3,
        troopId = 0,
        stats = GeneralStats(leadership = 90, strength = 80, intelligence = 70),
        experience = 0,
        dedication = 0,
        officerLevel = officerLevel,
        role = role,
        gold = 100_000,
        rice = 100_000,
        crew = 0,
        train = 50,
        atmos = 50,
        npcState = 2,
        turnTime = turnTime,
        meta = meta,
    )

    private fun city() = City(
        id = 3,
        name = "낙양",
        nationId = 1,
        level = 8,
        population = 100_000,
        populationMax = 200_000,
        agriculture = 10_000,
        agricultureMax = 20_000,
        commerce = 10_000,
        commerceMax = 20_000,
        security = 10_000,
        securityMax = 20_000,
        defence = 10_000,
        defenceMax = 20_000,
        wall = 10_000,
        wallMax = 20_000,
        supplyState = 1,
        frontState = 1,
        meta = linkedMapOf("trust" to 80),
    )

    private fun world(
        month: Int = 12,
        generals: List<TurnGeneral>,
        nation: Nation = Nation(
            id = 1,
            name = "촉",
            color = "#0f0",
            capitalCityId = 3,
            gold = 100_000,
            rice = 100_000,
            tech = 1_500.0,
            level = 7,
            typeCode = "che_유가",
            meta = linkedMapOf("tech" to 0),
        ),
    ) = InMemoryTurnWorld(
        WorldSnapshot(
            state = TurnWorldState(1, 200, month, 60, turnTime),
            generals = generals,
            cities = listOf(city()),
            nations = listOf(nation, Nation(2, "위", "#f00", capitalCityId = 4)),
            diplomacy = listOf(TurnDiplomacy(1, 2, state = 0, term = 0)),
            worldId = opensamguk.common.world.WorldId((TurnWorldState(1, 200, month, 60, turnTime)).id),
        ),
    )

    private fun adapter(world: InMemoryTurnWorld) = AiTurnAdapter(
        world = world,
        registry = registry,
        hiddenSeed = "0".repeat(32),
        startYear = 184,
        turnTerm = 1,
    )

    @Test
    fun `general AI state drains into the general row recorder channel`() {
        val world = world(generals = listOf(general()))
        val recorder = ChangeRecorder(kvWriteObserver = world::applyKvDirtyFree)
        val adapter = adapter(world)

        adapter.chooseGeneralTurn(1, ReservedTurn("휴식", ""))
        adapter.drainGeneralPassDeltas(recorder)

        assertEquals(80, (world.getGeneralById(1)!!.meta["defence_train"] as Number).toInt())
        assertEquals(setOf(1), recorder.dirtyGeneralIds())
        assertTrue(recorder.kvDirty().keys.none { it.namespace == "1" && it.key == "defence_train" })
    }

    @Test
    fun `production lifecycle drains AI state after command and killturn processing`() {
        val world = world(generals = listOf(general()))
        val recorder = ChangeRecorder(kvWriteObserver = world::applyKvDirtyFree)
        val adapter = adapter(world)
        val handler = ReservedTurnHandler(
            world = world,
            registry = registry,
            hiddenSeed = "0".repeat(32),
            startYear = 184,
            recorder = recorder,
            aiHook = adapter::chooseGeneralTurn,
        )
        val lifecycle = TurnDaemonLifecycle(
            world = world,
            handler = handler,
            beginGeneralTurn = adapter::beginGeneralTurn,
            lifecycleEnvOf = { state, date ->
                LifecycleEnv(4_800, state.currentYear, state.currentMonth, 1, turnTimeHm = date)
            },
            pullGeneralTurnOf = { adapter.drainGeneralPassDeltas(recorder) },
            reservedActionOf = { ReservedTurn("휴식", "") },
        )

        lifecycle.runTick(turnTime.plusSeconds(60))

        val after = world.getGeneralById(1)!!
        assertEquals(80, (after.meta["defence_train"] as Number).toInt())
        assertEquals(999, (after.meta["killturn"] as Number).toInt())
        assertEquals(setOf(1), recorder.dirtyGeneralIds())
    }

    @Test
    fun `nation pass drains row fields and nation env through distinct recorder channels`() {
        val ruler = general(
            officerLevel = 12,
            meta = linkedMapOf("killturn" to 1000, "belong" to 1, "use_auto_nation_turn" to 0),
        )
        val world = world(generals = listOf(ruler))
        val recorder = ChangeRecorder(kvWriteObserver = world::applyKvDirtyFree)
        val adapter = adapter(world)

        adapter.chooseNationTurn(1, ReservedTurn("휴식", ""), LastTurn())
        adapter.drainNationPassDeltas(recorder)

        assertEquals(1, (world.getGeneralById(1)!!.meta["use_auto_nation_turn"] as Number).toInt())
        assertEquals(0, (world.getNationById(1)!!.meta["war"] as Number).toInt())
        assertTrue(world.getNationById(1)!!.meta["rate"] is Number)
        assertEquals(setOf(1), recorder.dirtyGeneralIds())
        assertEquals(setOf(1), recorder.dirtyNationIds())
        assertTrue(
            recorder.kvDirty().keys.any {
                it.table == "nation_env" && it.namespace == "1" && it.key == "last_attackable"
            },
        )
    }

    @Test
    fun `AI reads the dedicated nation tech column instead of stale meta`() {
        val world = world(generals = listOf(general()))

        assertEquals(1_500, adapter(world).nationTechOf(1))
    }

    @Test
    fun `recruit population score uses each generals live action modules`() {
        val candidate = general(role = GeneralRole(specialWar = "che_징병"))
        val world = world(generals = listOf(candidate))
        val view = AiGeneralView(
            general = PerTurnOverlay.toLogicGeneral(candidate),
            recentWarSeconds = null,
            reservedCommandName = null,
            fullLeadership = 90.0,
        )

        assertEquals(0.0, adapter(world).recruitPopScoreOf(view))
    }
}
