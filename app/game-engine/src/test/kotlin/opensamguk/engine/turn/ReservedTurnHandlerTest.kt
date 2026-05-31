package opensamguk.engine.turn

import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.statview.WorldEnvBuilder
import opensamguk.logic.stats.GeneralActionPipeline
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P1 Task F3 — the [ReservedTurnHandler] end-to-end: full constraints (the SAME `:logic` library) →
 * per-action RNG → resolve → [ChangeRecorder] (single dirty source) → dirty-free apply + logs.
 *
 * Determinism is asserted structurally (same world + same seed → identical post-state). The exact
 * float golden is pinned by the AREA G PHP oracle (G1/G2) once the captured `hiddenSeed` lands —
 * here `FIXTURE_HIDDEN_SEED` is the placeholder the resolve tests use.
 */
class ReservedTurnHandlerTest {

    private val t0 = Instant.parse("0200-01-01T12:34:00Z")
    private val pipeline = GeneralActionPipeline()
    private val registry = CommandRegistry(pipeline)

    // FIXTURE INPUT — replaced by the G1-captured golden hiddenSeed (UniqueConst::$hiddenSeed) before lock.
    private val FIXTURE_HIDDEN_SEED = "00000000000000000000000000000000"
    private val YEAR = 200
    private val MONTH = 1
    private val START_YEAR = 184

    private fun general(
        id: Int = 42,
        nationId: Int = 1,
        cityId: Int = 7,
        gold: Int = 100_000,
        intel: Int = 80,
    ) = TurnGeneral(
        id = id,
        name = "g$id",
        nationId = nationId,
        cityId = cityId,
        troopId = 0,
        stats = GeneralStats(leadership = 70, strength = 70, intelligence = intel),
        experience = 0,
        dedication = 0,
        officerLevel = 0,
        gold = gold,
        rice = 1000,
        injury = 0,
        turnTime = t0,
        meta = linkedMapOf("explevel" to 10, "intel_exp" to 3, "max_domestic_critical" to 0.0),
    )

    private fun city(
        id: Int = 7,
        nationId: Int = 1,
        agri: Int = 1000,
        agriMax: Int = 20000,
        supplyState: Int = 1,
        frontState: Int = 0,
    ) = City(
        id = id,
        name = "c$id",
        nationId = nationId,
        level = 5,
        agriculture = agri,
        agricultureMax = agriMax,
        commerce = 1000,
        commerceMax = 20000,
        supplyState = supplyState,
        frontState = frontState,
        meta = linkedMapOf("trust" to 50),   // INTEGER-valued trust (G1 invariant), typed Double in logic
    )

    private fun nation(id: Int = 1, level: Int = 2, capital: Int = 99) =
        Nation(id = id, name = "n$id", color = "#000", level = level, capitalCityId = capital)

    private fun baseState() = TurnWorldState(
        id = 1, currentYear = YEAR, currentMonth = MONTH, tickSeconds = 3600, lastTurnTime = t0,
    )

    private fun worldWith(
        generals: List<TurnGeneral> = listOf(general()),
        cities: List<City> = listOf(city()),
        nations: List<Nation> = listOf(nation()),
    ) = InMemoryTurnWorld(WorldSnapshot(baseState(), generals, cities, nations))

    private fun handlerFor(world: InMemoryTurnWorld) =
        ReservedTurnHandler(world, registry, FIXTURE_HIDDEN_SEED, START_YEAR)

    @Test
    fun `available general che_농지개간 increases agriculture decreases gold pushes log and records dirty`() {
        val world = worldWith()
        val handler = handlerFor(world)

        val outcome = handler.handle(42, "che_농지개간", YEAR, MONTH, "12:34")

        assertFalse(outcome.fellBack, "AVAILABLE general resolves the requested action, not the fallback")
        assertEquals("che_농지개간", outcome.definition.key)
        assertNull(outcome.denyReason)
        assertEquals(1, outcome.logs.size, "exactly one action log (no level cross in P1)")

        // post-state visible in the world (dirty-free apply wrote the engine rows)
        val postCity = world.getCityById(7)!!
        val postGeneral = world.getGeneralById(42)!!
        assertTrue(postCity.agriculture > 1000, "agriculture increased: ${postCity.agriculture}")
        assertTrue(postGeneral.gold < 100_000, "gold decreased by reqGold: ${postGeneral.gold}")
        assertEquals(4, (postGeneral.meta["intel_exp"] as Number).toInt(), "intel_exp incremented 3 -> 4")

        // ChangeRecorder is the SINGLE dirty source — the resolver never touched world.updateGeneral/updateCity
        assertTrue(handler.recorder.isDirty, "recorder marked the mutation dirty")
        assertEquals(setOf(42), handler.recorder.dirtyGeneralIds())
        assertEquals(setOf(7), handler.recorder.dirtyCityIds())

        // the world's OWN dirty set stays empty (dirty-free apply path — flush reads the recorder, not the world)
        val worldDirty = world.consumeDirtyState()
        assertTrue(worldDirty.generals.isEmpty(), "dirty-free apply never marks the world general dirty")
        assertTrue(worldDirty.cities.isEmpty(), "dirty-free apply never marks the world city dirty")
        assertEquals(1, worldDirty.logs.size, "the action log was pushed to the world")
    }

    @Test
    fun `recorded patch matches the applied world post-state`() {
        val world = worldWith()
        val handler = handlerFor(world)

        handler.handle(42, "che_농지개간", YEAR, MONTH, "12:34")

        val cityPatch = handler.recorder.cityPatches().single()
        assertEquals(7, cityPatch.id)
        assertEquals(world.getCityById(7)!!.agriculture, cityPatch.columns["agriculture"],
            "recorder's agriculture patch equals the world's applied post-state")
    }

    @Test
    fun `blocked general non-owned city falls back to rest with deny reason and no economic mutation`() {
        // general's city is owned by nation 2, not the general's nation 1 → OccupiedCity Deny.
        val world = worldWith(
            generals = listOf(general(cityId = 7)),
            cities = listOf(city(id = 7, nationId = 2)),
            nations = listOf(nation(id = 1), nation(id = 2)),
        )
        val handler = handlerFor(world)

        val outcome = handler.handle(42, "che_농지개간", YEAR, MONTH, "12:34")

        assertTrue(outcome.fellBack, "denied turn falls back to 휴식")
        assertEquals("휴식", outcome.definition.key)
        assertEquals("아국이 아닙니다.", outcome.denyReason, "OccupiedCity deny reason (PHP getFailString)")

        // no economic mutation
        assertEquals(1000, world.getCityById(7)!!.agriculture, "agriculture untouched on a denied turn")
        assertEquals(100_000, world.getGeneralById(42)!!.gold, "gold untouched on a denied turn")
        assertFalse(handler.recorder.isDirty, "nothing recorded dirty on a denied turn")

        // the deny-reason log was pushed (휴식-fallback log)
        val worldDirty = world.consumeDirtyState()
        assertEquals(1, worldDirty.logs.size, "the deny-reason log was pushed")
        assertTrue(worldDirty.logs.single().text.contains("아국이 아닙니다."), "deny log carries the reason")
    }

    @Test
    fun `insufficient gold falls back with the funds deny reason`() {
        val world = worldWith(generals = listOf(general(gold = 0)))
        val handler = handlerFor(world)

        val outcome = handler.handle(42, "che_농지개간", YEAR, MONTH, "12:34")

        assertTrue(outcome.fellBack)
        assertEquals("자금이 모자랍니다.", outcome.denyReason, "ReqGeneralGold deny reason")
        assertEquals(0, world.getGeneralById(42)!!.gold, "gold untouched")
        assertFalse(handler.recorder.isDirty)
    }

    @Test
    fun `determinism same world and seed yield identical post-state across two runs`() {
        val worldA = worldWith()
        handlerFor(worldA).handle(42, "che_농지개간", YEAR, MONTH, "12:34")

        val worldB = worldWith()
        handlerFor(worldB).handle(42, "che_농지개간", YEAR, MONTH, "12:34")

        val a = worldA.getGeneralById(42)!!
        val b = worldB.getGeneralById(42)!!
        assertEquals(a.gold, b.gold)
        assertEquals(a.experience, b.experience)
        assertEquals(a.dedication, b.dedication)
        assertEquals(a.meta["intel_exp"], b.meta["intel_exp"])
        assertEquals(a.meta["max_domestic_critical"], b.meta["max_domestic_critical"])
        assertEquals(worldA.getCityById(7)!!.agriculture, worldB.getCityById(7)!!.agriculture)
    }

    @Test
    fun `full-mode env equals the precheck env for the same fixture (single shared env-builder)`() {
        val world = worldWith()
        val handler = handlerFor(world)

        val outcome = handler.handle(42, "che_농지개간", YEAR, MONTH, "12:34")

        // The precheck call site (E2 PrecheckStateViewFactory) builds its env through the SAME helper.
        val precheckEnv = WorldEnvBuilder.envMap(YEAR, START_YEAR)

        // key-for-key equality proves the one shared helper — neither call site can drift (P1 #7).
        assertEquals(precheckEnv, outcome.env, "full-mode env == precheck env (same WorldEnvBuilder)")
        assertEquals(precheckEnv.keys.toList(), outcome.env.keys.toList(), "env key order identical")
        assertEquals(YEAR, outcome.env["year"])
        assertEquals(START_YEAR, outcome.env["startYear"])
        assertEquals((YEAR - START_YEAR + 10) * 2, outcome.env["develCost"], "develCost = (year-startYear+10)*2")
    }

    @Test
    fun `lifecycle drains all due generals in one pass`() {
        val world = worldWith(
            generals = listOf(general(id = 42), general(id = 43)),
            cities = listOf(city(id = 7)),
        )
        val handler = handlerFor(world)
        val lifecycle = TurnDaemonLifecycle(world, handler) {
            opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn("che_농지개간", "")
        }

        val runTime = world.getState().lastTurnTime  // both generals' turnTime == lastTurnTime → due
        val handled = lifecycle.runTick(runTime)

        assertEquals(2, handled.size, "both due generals processed in one pass")
        assertEquals(listOf(42, 43), handled.map { it.generalId }, "deterministic order: ascending id")
        assertNotNull(handled.first { it.generalId == 42 })
        // both generals share the one city; the recorder accumulates across the pass (one flush boundary)
        assertEquals(setOf(42, 43), handler.recorder.dirtyGeneralIds())
        assertEquals(setOf(7), handler.recorder.dirtyCityIds())
    }
}
