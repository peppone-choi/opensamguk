package opensamguk.engine.turn

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

/**
 * P5 Task FM1 — the F-SEAM general-pass AI hook + the `(actionCode, argJson)` seam widening.
 *
 * Asserts the four contract points of the plan step (lines 437):
 *  1. `argJson` threads END-TO-END — a reserved command's stored `arg` jsonb reaches the resolver
 *     draft ctx (observable via [ReservedTurnHandler.HandledTurn.args]).
 *  2. the AI hook REPLACES the reserved command for AI-controlled generals and sets `autorunMode`
 *     ONLY when the chosen command differs from the reserved one (R-SEAM §2 `:332-336`).
 *  3. NON-AI generals are untouched (the hook never fires; the reserved command resolves as-is).
 *  4. RNG-seed parity is UNAFFECTED — the 6-component per-action seed still keys on `definition.key`
 *     (the short class name), NEVER the arg payload; AND the AI writes NO game-entity row inline
 *     (only meta-KV deltas via the [ChangeRecorder]/[AiTurnAdapter] delta seam).
 */
class AiHookTest {

    private val t0 = Instant.parse("0200-01-01T12:34:00Z")
    private val pipeline = GeneralActionPipeline()
    private val registry = CommandRegistry(pipeline)

    private val FIXTURE_HIDDEN_SEED = "00000000000000000000000000000000"
    private val YEAR = 200
    private val MONTH = 1
    private val START_YEAR = 184

    private fun general(
        id: Int = 42,
        nationId: Int = 1,
        cityId: Int = 7,
        gold: Int = 100_000,
        npcState: Int = 0,
    ) = TurnGeneral(
        id = id,
        name = "g$id",
        nationId = nationId,
        cityId = cityId,
        troopId = 0,
        stats = GeneralStats(leadership = 70, strength = 70, intelligence = 80),
        experience = 0,
        dedication = 0,
        officerLevel = 0,
        gold = gold,
        rice = 1000,
        injury = 0,
        npcState = npcState,
        turnTime = t0,
        meta = linkedMapOf("explevel" to 10, "intel_exp" to 3, "max_domestic_critical" to 0.0),
    )

    private fun city(id: Int = 7, nationId: Int = 1) = City(
        id = id,
        name = "c$id",
        nationId = nationId,
        level = 5,
        agriculture = 1000,
        agricultureMax = 20000,
        commerce = 1000,
        commerceMax = 20000,
        supplyState = 1,
        frontState = 0,
        meta = linkedMapOf("trust" to 50),
    )

    private fun nation(id: Int = 1, capital: Int = 99) =
        Nation(id = id, name = "n$id", color = "#000", level = 2, capitalCityId = capital)

    private fun baseState() = TurnWorldState(
        id = 1, currentYear = YEAR, currentMonth = MONTH, tickSeconds = 3600, lastTurnTime = t0,
    )

    private fun worldWith(generals: List<TurnGeneral> = listOf(general())) =
        InMemoryTurnWorld(WorldSnapshot(baseState(), generals, listOf(city()), listOf(nation())))

    private fun handlerFor(
        world: InMemoryTurnWorld,
        aiHook: ((generalId: Int, reserved: ReservedTurn) -> ChosenCommand)? = null,
    ) = ReservedTurnHandler(world, registry, FIXTURE_HIDDEN_SEED, START_YEAR, aiHook = aiHook)

    // ── (1) argJson threads end-to-end ────────────────────────────────────────────────────────────

    @Test
    fun `argJson threads through handle into the resolver draft ctx`() {
        val world = worldWith()
        val handler = handlerFor(world)

        // A targeted reserved command carrying a stored arg payload (the general_turn.arg jsonb).
        val outcome = handler.handle(
            42,
            ReservedTurn(actionCode = "che_농지개간", argJson = """{"destCityID":5,"amount":100}"""),
            YEAR, MONTH, "12:34",
        )

        // The parsed args reached the resolver (surfaced on HandledTurn for the parity oracle).
        assertEquals(5, (outcome.args["destCityID"] as Number).toInt(), "destCityID arg parsed + threaded")
        assertEquals(100, (outcome.args["amount"] as Number).toInt(), "amount arg parsed + threaded")
    }

    @Test
    fun `a no-arg reserved command threads an empty arg map`() {
        val world = worldWith()
        val handler = handlerFor(world)

        val outcome = handler.handle(42, ReservedTurn("che_농지개간", ""), YEAR, MONTH, "12:34")

        assertTrue(outcome.args.isEmpty(), "an empty argJson yields an empty arg map (no-arg command)")
        assertFalse(outcome.fellBack, "the no-arg domestic command still resolves")
    }

    // ── (2)/(3) the AI hook replaces only for AI generals, sets autorunMode only on change ──────────

    @Test
    fun `AI hook replaces the reserved command for an AI-controlled general and sets autorunMode`() {
        val world = worldWith(generals = listOf(general(npcState = 2)))
        // The AI chooses a DIFFERENT command than the reserved one.
        val handler = handlerFor(world) { _, _ -> ChosenCommand("che_농지개간", emptyMap(), reason = "do일반내정") }

        val outcome = handler.handle(42, ReservedTurn("휴식", ""), YEAR, MONTH, "12:34")

        assertEquals("che_농지개간", outcome.definition.key, "the AI-chosen command replaced the reserved 휴식")
        assertTrue(outcome.autorunMode, "autorunMode set because cmd changed (R-SEAM §2 :333-336)")
        assertFalse(outcome.fellBack, "the AI-chosen command resolved (not a fallback)")
    }

    @Test
    fun `AI hook keeps autorunMode false when the chosen command equals the reserved one`() {
        val world = worldWith(generals = listOf(general(npcState = 2)))
        // The AI honors the reserved command verbatim (chooseGeneralTurn returns the reserved one).
        val handler = handlerFor(world) { _, reserved -> ChosenCommand(reserved.actionCode, emptyMap(), reason = "do예약턴") }

        val outcome = handler.handle(42, ReservedTurn("che_농지개간", ""), YEAR, MONTH, "12:34")

        assertEquals("che_농지개간", outcome.definition.key)
        assertFalse(outcome.autorunMode, "autorunMode stays false when the AI returns the reserved command unchanged")
    }

    @Test
    fun `non-AI general is untouched by the hook the reserved command resolves as-is`() {
        val world = worldWith(generals = listOf(general(npcState = 0)))
        var hookCalled = false
        val handler = handlerFor(world) { _, _ ->
            hookCalled = true
            ChosenCommand("che_상업투자", emptyMap())
        }

        val outcome = handler.handle(42, ReservedTurn("che_농지개간", ""), YEAR, MONTH, "12:34")

        assertFalse(hookCalled, "the AI hook never fires for a non-AI (npc==0) general")
        assertEquals("che_농지개간", outcome.definition.key, "the human's reserved command resolves unchanged")
        assertFalse(outcome.autorunMode, "a human turn is never autorunMode")
    }

    // ── (4) seed parity unaffected + no inline entity write ─────────────────────────────────────────

    @Test
    fun `seed keys on definition key not the arg payload parity unaffected`() {
        // Two runs with DIFFERENT arg payloads on the SAME action+general must yield the IDENTICAL
        // per-action RNG stream (the seed's 6th component is definition.key, never the arg) → identical
        // resolved post-state. che_농지개간 ignores args, so the post-state is the cleanest oracle.
        val worldA = worldWith()
        handlerFor(worldA).handle(42, ReservedTurn("che_농지개간", """{"amount":1}"""), YEAR, MONTH, "12:34")

        val worldB = worldWith()
        handlerFor(worldB).handle(42, ReservedTurn("che_농지개간", """{"amount":999999}"""), YEAR, MONTH, "12:34")

        val a = worldA.getGeneralById(42)!!
        val b = worldB.getGeneralById(42)!!
        assertEquals(a.gold, b.gold, "arg payload does NOT perturb the per-action RNG seed (keyed on definition.key)")
        assertEquals(a.experience, b.experience)
        assertEquals(worldA.getCityById(7)!!.agriculture, worldB.getCityById(7)!!.agriculture)
    }

    @Test
    fun `AI hook writes no game-entity row inline only meta-KV deltas route through the recorder`() {
        val world = worldWith(generals = listOf(general(npcState = 2)))
        // The AI returns the reserved 휴식 verbatim — 휴식 mutates NOTHING — so any general/city change
        // would have to come from an illicit inline write inside the hook/adapter seam.
        val handler = handlerFor(world) { _, reserved -> ChosenCommand(reserved.actionCode, emptyMap()) }

        handler.handle(42, ReservedTurn("휴식", ""), YEAR, MONTH, "12:34")

        // The world's own dirty set stays empty (the AI never calls world.updateGeneral/updateCity).
        val worldDirty = world.consumeDirtyState()
        assertTrue(worldDirty.generals.isEmpty(), "the AI selection writes no general row inline")
        assertTrue(worldDirty.cities.isEmpty(), "the AI selection writes no city row inline")
        // The resolve of 휴식 records nothing dirty either.
        assertFalse(handler.recorder.isDirty, "휴식 resolve records no dirty row")
        assertNull(world.getGeneralById(42)!!.meta["killturn"], "no spurious meta write for the plain hook path")
    }
}
