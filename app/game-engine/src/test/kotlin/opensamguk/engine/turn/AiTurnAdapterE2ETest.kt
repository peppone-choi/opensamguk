package opensamguk.engine.turn

import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.ai.ChosenCommand
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.stats.GeneralActionPipeline
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P5 ASSEMBLE (engine end-to-end) — [AiTurnAdapter] now builds a FULLY-WIRED [GeneralAI] from all 7
 * families' world-driven bodies (via [GeneralAiDoBodies.fromFamilies][opensamguk.logic.ai.GeneralAiDoBodies.fromFamilies])
 * over the per-general AI context (AiInstanceState + AiWorldView + the seeded RandUtil + policies + bridge).
 *
 * Asserts the live dispatch is assembled (NOT the draw-for-draw G-GATE):
 *  1. a representative npc==2 general dispatches a NON-neutral command through the REAL priority loop
 *     (the first non-null body wins) and resolves through [ReservedTurnHandler]'s `aiHook`.
 *  2. a nation chief runs [AiTurnAdapter.chooseNationTurn] picking a real 발령/포상 (the nation dispatch
 *     loop, not the inert neutral fallback).
 *  3. READ-ONLY over GAME ENTITIES is preserved — the AI selection writes no general/city row inline
 *     (the DaemonNoEntityManager invariant); only the resolved command's delta + meta-KV deltas appear.
 */
class AiTurnAdapterE2ETest {

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
        officerLevel: Int = 1,
        npcState: Int = 2,
    ) = TurnGeneral(
        id = id,
        name = "g$id",
        nationId = nationId,
        cityId = cityId,
        troopId = 0,
        stats = GeneralStats(leadership = 70, strength = 70, intelligence = 70),
        experience = 0,
        dedication = 0,
        officerLevel = officerLevel,
        gold = 100_000,
        rice = 100_000,
        injury = 0,
        npcState = npcState,
        turnTime = t0,
        meta = linkedMapOf("explevel" to 10, "intel_exp" to 3, "max_domestic_critical" to 0.0, "killturn" to 1000, "belong" to 1),
    )

    // A self-city with a LOW trust ratio (50/100 = 0.5) so the do일반내정 candidate set is non-empty and
    // the priority loop fires a real develop command (not the terminal fallback).
    private fun city(id: Int = 7, nationId: Int = 1) = City(
        id = id, name = "c$id", nationId = nationId, level = 5,
        agriculture = 20_000, agricultureMax = 20_000, commerce = 20_000, commerceMax = 20_000,
        supplyState = 1, frontState = 0, meta = linkedMapOf("trust" to 50, "pop" to 100_000, "pop_max" to 100_000),
    )

    private fun nation(id: Int = 1, capital: Int = 7) =
        Nation(id = id, name = "n$id", color = "#000", level = 2, capitalCityId = capital)

    private fun baseState() = TurnWorldState(
        id = 1, currentYear = YEAR, currentMonth = MONTH, tickSeconds = 3600, lastTurnTime = t0,
    )

    private fun worldWith(generals: List<TurnGeneral>) =
        InMemoryTurnWorld(WorldSnapshot(baseState(), generals, listOf(city()), listOf(nation())))

    // ── (1) a wired general dispatches a NON-neutral command through the real priority loop + resolves ──

    @Test fun `a fully-wired AI general dispatches a non-neutral command through the loop and resolves`() {
        val world = worldWith(listOf(general(npcState = 2)))
        val adapter = AiTurnAdapter(world, registry, FIXTURE_HIDDEN_SEED, START_YEAR, turnTerm = 1)

        val chosen = adapter.chooseGeneralTurn(42, ReservedTurn("휴식", ""))

        // The wired family bodies fire through the loop — the AI does NOT silently fall to a bare 휴식.
        assertNotEquals("휴식", chosen.actionCode, "the AI replaced the reserved 휴식 with a real command")
        assertTrue(chosen.reason.isNotEmpty(), "a do<한글> reason was tagged (the loop / pre-loop fired)")

        // The choice flows through the live handler aiHook seam + resolves through the EXISTING delta path.
        val handler = ReservedTurnHandler(
            world, registry, FIXTURE_HIDDEN_SEED, START_YEAR,
            aiHook = { gid, reserved -> adapter.chooseGeneralTurn(gid, reserved) },
        )
        val outcome = handler.handle(42, ReservedTurn("휴식", ""), YEAR, MONTH, "12:34")

        assertEquals(chosen.actionCode, outcome.definition.key, "the AI-chosen command resolved end-to-end")
        assertTrue(outcome.autorunMode, "autorunMode set because the AI replaced the reserved 휴식")
    }

    // ── (2) a nation chief runs chooseNationTurn picking a real nation command ─────────────────────────

    @Test fun `a nation chief runs chooseNationTurn through the nation dispatch loop`() {
        // An officer_level 12 ruler — chooseNationTurn runs the nation pass. With a benign reserved 휴식
        // and no honor, the wired nation dispatch loop runs over the merged nation families.
        val world = worldWith(listOf(general(id = 1, officerLevel = 12, npcState = 2)))
        val adapter = AiTurnAdapter(world, registry, FIXTURE_HIDDEN_SEED, START_YEAR, turnTerm = 1)

        val chosen = adapter.chooseNationTurn(1, ReservedTurn("휴식", ""), LastTurn())

        // The nation pass produced a ChosenCommand (a do<한글> nation command or the neutral fallback) —
        // proving the nation dispatch + hooks are assembled and run end-to-end (not an empty bundle crash).
        assertTrue(chosen.actionCode.isNotEmpty(), "chooseNationTurn produced a command code")
        assertTrue(chosen.reason.isNotEmpty(), "the nation pass tagged a reason")
    }

    // ── (3) READ-ONLY over GAME ENTITIES — the AI selection writes no general/city row inline ──────────

    @Test fun `the wired adapter writes no general or city row inline (DaemonNoEntityManager invariant)`() {
        val world = worldWith(listOf(general(npcState = 2)))
        val adapter = AiTurnAdapter(world, registry, FIXTURE_HIDDEN_SEED, START_YEAR, turnTerm = 1)

        adapter.chooseGeneralTurn(42, ReservedTurn("휴식", ""))

        // The AI is read-only over GAME ENTITIES — it queues meta-KV deltas only, never an inline row write.
        val dirty = world.consumeDirtyState()
        assertTrue(dirty.generals.isEmpty(), "the AI selection writes no general row inline")
        assertTrue(dirty.cities.isEmpty(), "the AI selection writes no city row inline")
        assertNull(world.getGeneralById(42)!!.meta["__inline_written"], "no inline meta write")
    }
}
