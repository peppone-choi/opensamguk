package opensamguk.engine.turn

import opensamguk.common.constants.GameConst
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.ai.AutorunGeneralPolicy
import opensamguk.logic.ai.AutorunNationPolicy
import opensamguk.logic.ai.ChosenCommand
import opensamguk.logic.ai.GeneralAiDoBodies
import opensamguk.logic.ai.GeneralAiFactory
import opensamguk.logic.ai.GeneralAiInput
import opensamguk.logic.ai.families.GenDomesticFamily
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.stats.GeneralActionPipeline
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * P5 Wire step (integration) — a smoke of the WIRED GeneralAI spine end-to-end through the
 * [GeneralAiFactory] + the daemon [ReservedTurnHandler] seam.
 *
 * This is NOT the draw-for-draw G-GATE; it asserts the assembly is sound:
 *  1. a representative AI-controlled general, given the full [GeneralAi][opensamguk.logic.ai.GeneralAI]
 *     built via [GeneralAiFactory] with ONE wired leaf body (a faithful do일반내정 composing the real
 *     [GenDomesticFamily.pickDomesticCommand] primitive over the SOLE per-decision rng), dispatches that
 *     NON-NEUTRAL command through the priority loop — not the terminal `che_중립` fallback.
 *  2. that AI choice flows through the live [ReservedTurnHandler] `aiHook` seam and RESOLVES via the
 *     EXISTING P2-P4 delta path (replacing the reserved 휴식; autorunMode set on change).
 *  3. READ-ONLY over GAME ENTITIES is preserved — the AI/factory write NO general/city row inline; the
 *     world's own dirty set carries only the resolved command's delta (the DaemonNoEntityManager invariant).
 */
class GeneralAiWiringTest {

    private val t0 = Instant.parse("0200-01-01T12:34:00Z")
    private val pipeline = GeneralActionPipeline()
    private val registry = CommandRegistry(pipeline)

    private val FIXTURE_HIDDEN_SEED = "00000000000000000000000000000000"
    private val YEAR = 200
    private val MONTH = 1
    private val START_YEAR = 184

    private fun rng() = RandUtil(LiteHashDrbg("GeneralAiWiringTest"))

    private fun general(id: Int = 42, nationId: Int = 1, npcState: Int = 2) = TurnGeneral(
        id = id,
        name = "g$id",
        nationId = nationId,
        cityId = 7,
        troopId = 0,
        stats = GeneralStats(leadership = 70, strength = 70, intelligence = 80),
        experience = 0,
        dedication = 0,
        officerLevel = 1,
        gold = 100_000,
        rice = 100_000,
        injury = 0,
        npcState = npcState,
        turnTime = t0,
        meta = linkedMapOf("explevel" to 10, "intel_exp" to 3, "max_domestic_critical" to 0.0),
    )

    private fun city(id: Int = 7, nationId: Int = 1) = City(
        id = id, name = "c$id", nationId = nationId, level = 5,
        agriculture = 1000, agricultureMax = 20000, commerce = 1000, commerceMax = 20000,
        supplyState = 1, frontState = 0, meta = linkedMapOf("trust" to 50),
    )

    private fun nation(id: Int = 1, capital: Int = 99) =
        Nation(id = id, name = "n$id", color = "#000", level = 2, capitalCityId = capital)

    private fun baseState() = TurnWorldState(
        id = 1, currentYear = YEAR, currentMonth = MONTH, tickSeconds = 3600, lastTurnTime = t0,
        config = linkedMapOf("mapName" to "che"),
    )

    private fun worldWith(generals: List<TurnGeneral>) =
        InMemoryTurnWorld(WorldSnapshot(baseState(), generals, listOf(city()), listOf(nation()), worldId = opensamguk.common.world.WorldId((baseState()).id)))

    private fun genInput(rng: RandUtil) = GeneralAiInput(
        generalId = 42,
        npcType = 2,
        nationId = 1,
        officerLevel = 1,
        injury = 0,
        npcmsg = null,
        capital = true,
        relYearMonth = 0,
        can선양 = false,
        can국가선택 = true,
        cureThreshold = 30,
        npcMessageProb = GameConst.npcMessageFreqByDay * 1.0 / (60 * 24),
        rng = rng,
    )

    /**
     * A faithful representative leaf body: do일반내정 composing the real [GenDomesticFamily] primitive over
     * the SOLE rng (one terminal `choiceUsingWeightPair`), emitting the picked `che_*`. It is wired into the
     * factory by ACTION-NAME "일반내정" — exactly how the full Wave-1 family bodies plug in.
     */
    private fun wiredDomesticBodies(): GeneralAiDoBodies = GeneralAiDoBodies(
        generalDispatch = mapOf(
            "일반내정" to { _: LastTurn? ->
                // a single non-empty candidate (the world-driven build is the full family's job; the wire
                // smoke proves the primitive + the dispatch are connected on the shared rng).
                val picked = GenDomesticFamily.pickDomesticCommand(
                    cmdList = listOf("che_상업투자" to 1.0),
                    rng = rngForBody,
                )
                picked?.let { ChosenCommand(it, emptyMap()) }
            },
        ),
    )

    // a stable rng the wired body draws from (the SOLE per-decision rng in production; here a fixed seed).
    private val rngForBody = rng()

    // ── (1) the wired body dispatches a non-neutral command through the priority loop ──────────────

    @Test fun `a wired leaf body dispatches a non-neutral command through the priority loop`() {
        val ai = GeneralAiFactory.build(
            generalPolicy = AutorunGeneralPolicy(npcType = 2, nationId = 1),
            bodies = wiredDomesticBodies(),
            nationPolicy = AutorunNationPolicy(npcType = 2, tech = 0, develcost = 20),
        )

        val chosen = ai.chooseGeneralTurn(ChosenCommand("휴식", emptyMap()), genInput(rng()))

        assertEquals("che_상업투자", chosen.actionCode, "the wired do일반내정 dispatched its picked command")
        assertEquals("do일반내정", chosen.reason, "fired through the priority loop ('do'+actionName)")
        assertNotEquals("che_중립", chosen.actionCode, "NON-neutral — not the terminal fallback")
    }

    // ── (2) the AI choice flows through the handler aiHook seam + resolves (autorunMode on change) ──

    @Test fun `the wired AI choice flows through the handler aiHook and resolves end-to-end`() {
        val world = worldWith(listOf(general(npcState = 2)))
        val ai = GeneralAiFactory.build(
            generalPolicy = AutorunGeneralPolicy(npcType = 2, nationId = 1),
            bodies = wiredDomesticBodies(),
            nationPolicy = AutorunNationPolicy(npcType = 2, tech = 0, develcost = 20),
        )
        val handler = ReservedTurnHandler(
            world, registry, FIXTURE_HIDDEN_SEED, START_YEAR,
            aiHook = { _, reserved ->
                ai.chooseGeneralTurn(
                    ChosenCommand(reserved.actionCode, ReservedTurnHandler.decodeArgs(reserved.argJson)),
                    genInput(rng()),
                )
            },
        )

        val outcome = handler.handle(42, ReservedTurn("휴식", ""), YEAR, MONTH, "12:34")

        assertEquals("che_상업투자", outcome.definition.key, "the AI-chosen command replaced the reserved 휴식")
        assertTrue(outcome.autorunMode, "autorunMode set because the AI changed the command (R-SEAM §2)")
        assertFalse(outcome.fellBack, "the AI-chosen command resolved (not a fallback)")
    }

    // ── (3) READ-ONLY over GAME ENTITIES preserved (the DaemonNoEntityManager invariant) ───────────

    @Test fun `the wired spine writes no general or city row inline only the resolved delta`() {
        val world = worldWith(listOf(general(npcState = 2)))
        // wire the AI to HONOR the reserved 휴식 verbatim (do예약턴) — 휴식 mutates nothing, so any general/
        // city change would have to come from an illicit inline write inside the wired factory/adapter seam.
        val ai = GeneralAiFactory.build(
            generalPolicy = AutorunGeneralPolicy(npcType = 2, nationId = 1),
            bodies = GeneralAiDoBodies(),
        )
        val handler = ReservedTurnHandler(
            world, registry, FIXTURE_HIDDEN_SEED, START_YEAR,
            aiHook = { _, reserved ->
                ai.chooseGeneralTurn(
                    ChosenCommand(reserved.actionCode, ReservedTurnHandler.decodeArgs(reserved.argJson)),
                    genInput(rng()),
                )
            },
        )

        handler.handle(42, ReservedTurn("휴식", ""), YEAR, MONTH, "12:34")

        val worldDirty = world.consumeDirtyState()
        assertTrue(worldDirty.generals.isEmpty(), "the wired AI selection writes no general row inline")
        assertTrue(worldDirty.cities.isEmpty(), "the wired AI selection writes no city row inline")
        assertFalse(handler.recorder.isDirty, "honored-휴식 resolve records no dirty row")
    }
}
