package opensamguk.engine.turn

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.engine.golden.AiDrawRecorder
import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.ai.AiSeed
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.stats.GeneralActionPipeline
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P5 ONE-RNG (production daemon) — the [TurnDaemonLifecycle] threads ONE per-general `"GeneralAI"` decision
 * rng across the nation pass + the general pass for a due general, matching the gated [AiSelectionGateIT].
 *
 * Port target = PHP `TurnExecutionHelper.php:290-348`: `$ai = new GeneralAI($general)` is built ONCE per
 * general per turn (`:290/294`); `$ai->chooseNationTurn(...)` (`:306`, lord nation pass) and
 * `$ai->chooseGeneralTurn(...)` (`:332`, general pass) BOTH thread that ONE `$ai`'s rng — the nation-pass
 * draws are the stream PREFIX, the general-pass draws CONTINUE the same cursor (single-`GeneralAI`-per-general
 * semantics, [AiSeed] decision #1). The execution rngs (`'nationCommand'` `:310`, `'generalCommand'` `:340`)
 * are re-seeded at resolve and are DISTINCT streams (R-SEAM §2) — NOT exercised here.
 *
 * The production wiring under test:
 *  - [TurnDaemonLifecycle.beginGeneralTurn] = [AiTurnAdapter.beginGeneralTurn] (the per-general window OPEN,
 *    invoked ONCE per due general after `processBlocked`, before the nation pass).
 *  - [TurnDaemonLifecycle] `chooseNationTurn` = [AiTurnAdapter.chooseNationTurn].
 *  - [ReservedTurnHandler] `aiHook` = [AiTurnAdapter.chooseGeneralTurn].
 * All three delegate to the SAME [AiTurnAdapter] instance, whose `(generalId, year, month)` rng cache is the
 * thing that threads the one shared decision rng.
 *
 * **Non-vacuous proof.** The rng FACTORY is invoked EXACTLY ONCE per due general per tick — if the two passes
 * built SEPARATE rngs (the pre-fix divergence) the factory would fire twice and the general pass would restart
 * at the fresh `(stateIdx=0, bufferIdx=0)` origin. We assert (1) one factory call per general, (2) the recorded
 * draw stream spans BOTH passes on ONE recorder with a monotonically non-decreasing cursor across the
 * nation→general boundary (the general pass CONTINUES, never resets), and (3) [DaemonNoEntityManagerTest] stays
 * green (covered structurally by that sibling test — this test adds no entity-manager write path).
 */
class OneRngPerGeneralTurnTest {

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
        officerLevel: Int = 5,
        npcState: Int = 2,
    ) = TurnGeneral(
        id = id,
        name = "g$id",
        nationId = nationId,
        cityId = cityId,
        troopId = 0,
        stats = GeneralStats(leadership = 70, strength = 70, intelligence = 80),
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

    // A self-city with a LOW trust ratio so the general-pass priority loop has a non-empty candidate set
    // (so the general pass actually pulls at least one draw — otherwise the boundary continuity is vacuous).
    private fun city(id: Int = 7, nationId: Int = 1) = City(
        id = id, name = "c$id", nationId = nationId, level = 5,
        agriculture = 1000, agricultureMax = 20_000, commerce = 1000, commerceMax = 20_000,
        supplyState = 1, frontState = 0, meta = linkedMapOf("trust" to 50, "pop" to 100_000, "pop_max" to 100_000),
    )

    private fun nation(id: Int = 1, capital: Int = 7) =
        Nation(id = id, name = "n$id", color = "#000", level = 2, capitalCityId = capital)

    private fun baseState() = TurnWorldState(
        id = 1, currentYear = YEAR, currentMonth = MONTH, tickSeconds = 3600, lastTurnTime = t0,
    )

    private fun worldWith(generals: List<TurnGeneral>) =
        InMemoryTurnWorld(WorldSnapshot(baseState(), generals, listOf(city()), listOf(nation()), worldId = opensamguk.common.world.WorldId((baseState()).id)))

    /**
     * Build the production-style daemon over [adapter], capturing the per-general [AiDrawRecorder] and the
     * per-general factory invocation count. The factory wraps the SAME-seeded `"GeneralAI"` DRBG (byte-neutral)
     * so the live decision rng is observed, exactly as [AiSelectionGateIT] does.
     */
    private data class Wiring(
        val lifecycle: TurnDaemonLifecycle,
        val recorders: Map<Int, AiDrawRecorder>,
        val factoryCalls: Map<Int, Int>,
    )

    private fun wireDaemon(world: InMemoryTurnWorld): Wiring {
        val recorders = LinkedHashMap<Int, AiDrawRecorder>()
        val factoryCalls = LinkedHashMap<Int, Int>()
        val rngFactory: (String, Int, Int, Int) -> RandUtil = { hidden, y, m, gid ->
            factoryCalls[gid] = (factoryCalls[gid] ?: 0) + 1
            val rec = AiDrawRecorder(LiteHashDrbg(AiSeed.seed(hidden, y, m, gid)))
            recorders[gid] = rec
            rec
        }
        val adapter = AiTurnAdapter(
            world = world,
            registry = registry,
            hiddenSeed = FIXTURE_HIDDEN_SEED,
            startYear = START_YEAR,
            turnTerm = 1,
            pipeline = pipeline,
            rngFactory = rngFactory,
        )
        val handler = ReservedTurnHandler(
            world, registry, FIXTURE_HIDDEN_SEED, START_YEAR,
            aiHook = { gid, reserved -> adapter.chooseGeneralTurn(gid, reserved) },
        )
        val nationProc = ProcessNationCommand(world, handler.recorder, FIXTURE_HIDDEN_SEED)
        val lifecycle = TurnDaemonLifecycle(
            world = world,
            handler = handler,
            reservedActionOf = { ReservedTurn("휴식", "") },
            nationProcessor = nationProc,
            reservedNationActionOf = { _, _ -> ReservedTurn("휴식", "") },
            chooseNationTurn = { gid, reserved -> adapter.chooseNationTurn(gid, reserved, LastTurn()) },
            beginGeneralTurn = { gid -> adapter.beginGeneralTurn(gid) },
        )
        return Wiring(lifecycle, recorders, factoryCalls)
    }

    @Test
    fun `the daemon threads ONE shared decision rng across a lord's nation and general AI passes`() {
        val gid = 42
        val world = worldWith(listOf(general(id = gid, officerLevel = 5, npcState = 2)))
        val w = wireDaemon(world)

        // PHP 선택 게이트(TurnExecutionHelper.php:237) `turntime < %s`(STRICT <): turnTime(t0)과 같은
        // 시각은 due가 아니다. t0보다 미래 시각을 넘겨 그 장수를 due로 만든다(과거 inclusive `<=` 버그 제거).
        w.lifecycle.runTick(t0.plusSeconds(1))

        // (1) the rng factory fired EXACTLY ONCE for this general — ONE "GeneralAI" rng built per general per
        // turn (PHP `new GeneralAI` once). Two separate rngs (the pre-fix divergence) would fire it twice.
        assertEquals(1, w.factoryCalls[gid], "ONE GeneralAI rng built for the general (nation + general passes share it)")

        // (2) the SAME recorder observed BOTH passes — the nation-pass prefix AND the general-pass continuation.
        val rec = w.recorders[gid] ?: error("no recorder captured — the live AI built no rng?")
        val stream = rec.drawStream()
        assertTrue(stream.size >= 2, "both passes pulled draws onto ONE shared stream (got ${stream.size})")

        // (3) the cursor is monotonically NON-DECREASING across the WHOLE stream (nation prefix → general
        // continuation). A fresh general-pass rng would reset the cursor to the (0,0) origin mid-stream; one
        // shared rng never resets — every later draw is at-or-after the previous draw's position.
        var prevState = Long.MIN_VALUE
        var prevBuffer = Int.MIN_VALUE
        for ((i, d) in stream.withIndex()) {
            val notBefore = d.stateIdxBefore > prevState ||
                (d.stateIdxBefore == prevState && d.bufferIdxBefore >= prevBuffer)
            assertTrue(
                notBefore,
                "draw $i cursor (${d.stateIdxBefore},${d.bufferIdxBefore}) regressed below " +
                    "($prevState,$prevBuffer) — the general pass restarted a fresh rng instead of continuing",
            )
            prevState = d.stateIdxBefore
            prevBuffer = d.bufferIdxBefore
        }

        // (4) the shared stream actually advanced past the (0,0) origin — proving the general pass continued
        // a non-trivial nation prefix (non-vacuous: a single zero-draw pass would trivially "not regress").
        val last = stream.last()
        assertTrue(
            last.stateIdxBefore > 0L || last.bufferIdxBefore > 0,
            "the shared cursor advanced past the origin (last draw at (${last.stateIdxBefore},${last.bufferIdxBefore}))",
        )
    }

    @Test
    fun `the shared AI instance calculates genType before nation pass draws and does not recalculate it`() {
        val gid = 42
        val world = worldWith(listOf(general(id = gid, officerLevel = 5, npcState = 2)))
        val w = wireDaemon(world)

        w.lifecycle.runTick(t0.plusSeconds(1))

        val stream = w.recorders[gid]?.drawStream() ?: error("no recorder captured for the officer")
        assertEquals(
            "nextBool",
            stream.firstOrNull()?.method,
            "PHP GeneralAI constructor calculates genType before chooseNationTurn can draw",
        )
        assertEquals(
            0.45918367346938777,
            stream.first().args["prob"],
            "the actor's pipeline-adjusted strength / intel / 2 is the constructor-time genType probability",
        )
        assertEquals(
            1,
            stream.count { it.method == "nextBool" && it.args["prob"] == 0.45918367346938777 },
            "the same GeneralAI instance must not recalculate genType in the sibling general pass",
        )
    }

    @Test
    fun `a non-lord AI general runs only the general pass on its one rng`() {
        // officer_level<5 → no nation pass (hasNationTurn false). The general pass still opens its window and
        // pulls draws on its sole "GeneralAI" rng built once — the factory fires exactly once.
        val gid = 7
        val world = worldWith(listOf(general(id = gid, officerLevel = 1, npcState = 2)))
        val w = wireDaemon(world)

        // PHP 선택 게이트(TurnExecutionHelper.php:237) `turntime < %s`(STRICT <): turnTime(t0)과 같은
        // 시각은 due가 아니다. t0보다 미래 시각을 넘겨 그 장수를 due로 만든다(과거 inclusive `<=` 버그 제거).
        w.lifecycle.runTick(t0.plusSeconds(1))

        assertEquals(1, w.factoryCalls[gid], "ONE GeneralAI rng for the non-lord general (general pass only)")
        val rec = w.recorders[gid] ?: error("no recorder captured for the non-lord general")
        assertTrue(rec.drawCount() >= 1, "the general pass pulled at least one draw on its sole rng")
    }
}
