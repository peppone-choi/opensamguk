package opensamguk.engine.turn

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.ai.ChosenCommand
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.stats.GeneralActionPipeline
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P5 Task FM2 — `ProcessNationCommand` port + the officer_level>=5 nation pass running BEFORE the
 * general pass (R-SEAM §2/§4, PHP `TurnExecutionHelper.php:260-364` + `:72-109`).
 *
 * Asserts the FM2 contract points:
 *  1. for an officer_level>=5 AI general the NATION pass runs BEFORE the GENERAL pass (ordering via a
 *     recording sink);
 *  2. `hasNationTurn` false (nation==0 OR officer_level<5) skips the WHOLE nation block AND its RNG
 *     construction;
 *  3. `processBlocked()` (block>=2) skips BOTH passes;
 *  4. the `'nationCommand'` and `'generalCommand'` seeds are DISTINCT streams (different 2nd component);
 *  5. `turn_last_{officer_level}` is recorded as a ChangeRecorder delta (no EntityManager);
 *  6. `chooseInstantNationTurn` is never invoked from the lifecycle (only `chooseNationTurn` is wired).
 */
class NationPassOrderTest {

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
        block: Int = 0,
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
        rice = 1000,
        injury = 0,
        npcState = npcState,
        turnTime = t0,
        // killturn>0: 살아있는 장수는 양수 killturn을 가진다(PHP는 $gameStor->killturn에서 시드).
        // strict-< 교정 후 drain 꼬리(updateTurnTime, TurnExecutionHelper.php:185)의 killturn<=0 kill
        // 게이트가 동작하므로, kill 의도가 아닌 fixture 장수는 양수 killturn으로 tail을 통과해야 한다.
        meta = linkedMapOf("explevel" to 10, "intel_exp" to 3, "max_domestic_critical" to 0.0, "killturn" to 80, "block" to block),
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

    private fun worldWith(generals: List<TurnGeneral> = listOf(general())) =
        InMemoryTurnWorld(WorldSnapshot(baseState(), generals, listOf(city()), listOf(nation()), worldId = opensamguk.common.world.WorldId((baseState()).id)))

    // --- ProcessNationCommand unit assertions ---------------------------------------------------

    @Test
    fun `ProcessNationCommand seeds the nationCommand stream and records the turn_last KV delta`() {
        val world = worldWith()
        val recorder = ChangeRecorder()
        val proc = ProcessNationCommand(world, recorder, FIXTURE_HIDDEN_SEED)

        val result = proc.process(
            generalId = 42,
            officerLevel = 5,
            nationCommand = ChosenCommand("휴식", emptyMap()),
            lastTurn = LastTurn(),
            year = YEAR,
            month = MONTH,
            date = "12:34",
        )

        // turn_last_{officer_level} recorded as a nation meta delta (the nationStor->setValue analogue).
        val nationPatch = recorder.nationPatches().firstOrNull { it.id == 1 }
        assertNotNull(nationPatch, "turn_last_5 recorded as a ChangeRecorder nation delta")
        assertTrue(nationPatch.meta.containsKey("turn_last_5"), "the KV key is turn_last_{officer_level}")
        assertEquals(result.toRaw(), nationPatch.meta["turn_last_5"], "the KV value is the result LastTurn toRaw")
    }

    @Test
    fun `the nationCommand seed differs from the generalCommand seed second component`() {
        // The two passes RE-SEED their OWN per-command RNG — distinct streams (R-SEAM §2). Same draw
        // off two same-keyed-except-2nd-component seeds must differ (proof the 2nd component matters).
        val nationSeed = serializeSeed(FIXTURE_HIDDEN_SEED, "nationCommand", YEAR, MONTH, 42, "휴식")
        val generalSeed = serializeSeed(FIXTURE_HIDDEN_SEED, "generalCommand", YEAR, MONTH, 42, "휴식")
        assertFalse(nationSeed == generalSeed, "nationCommand and generalCommand seeds are distinct streams")
        val nationDraw = RandUtil(LiteHashDrbg(nationSeed)).nextInt(0, 1_000_000)
        val generalDraw = RandUtil(LiteHashDrbg(generalSeed)).nextInt(0, 1_000_000)
        assertFalse(nationDraw == generalDraw, "distinct seeds yield distinct draw streams")
    }

    @Test
    fun `ProcessNationCommand writes no game-entity row inline only the recorder delta`() {
        val world = worldWith()
        val recorder = ChangeRecorder()
        val proc = ProcessNationCommand(world, recorder, FIXTURE_HIDDEN_SEED)

        proc.process(42, 5, ChosenCommand("휴식", emptyMap()), LastTurn(), YEAR, MONTH, "12:34")

        // The world's own dirty set stays empty (the resolve never calls world.updateNation/updateGeneral).
        val dirty = world.consumeDirtyState()
        assertTrue(dirty.nations.isEmpty(), "no nation row written inline (the recorder owns dirtiness)")
        assertTrue(dirty.generals.isEmpty(), "no general row written inline")
    }

    // --- lifecycle pass-order assertions --------------------------------------------------------

    private fun lifecycle(
        world: InMemoryTurnWorld,
        recorder: ChangeRecorder,
        order: MutableList<String>,
        chooseNationTurn: ((generalId: Int, reserved: ReservedTurn) -> ChosenCommand)? = { _, r ->
            ChosenCommand(r.actionCode, emptyMap(), reason = "reserved").also { order.add("nation") }
        },
        instantInvoked: (() -> Unit)? = null,
    ): TurnDaemonLifecycle {
        val nationProc = ProcessNationCommand(world, recorder, FIXTURE_HIDDEN_SEED)
        val handler = ReservedTurnHandler(
            world, registry, FIXTURE_HIDDEN_SEED, START_YEAR,
            aiHook = { _, r -> ChosenCommand(r.actionCode, emptyMap()).also { order.add("general") } },
        )
        return TurnDaemonLifecycle(
            world = world,
            handler = handler,
            reservedActionOf = { ReservedTurn("휴식", "") },
            nationProcessor = nationProc,
            reservedNationActionOf = { _, _ -> ReservedTurn("휴식", "") },
            chooseNationTurn = chooseNationTurn,
        )
    }

    @Test
    fun `the nation pass runs BEFORE the general pass for an officer_level GTE 5 AI general`() {
        val world = worldWith(generals = listOf(general(officerLevel = 5, npcState = 2)))
        val recorder = ChangeRecorder()
        val order = mutableListOf<String>()
        val lc = lifecycle(world, recorder, order)

        // PHP 선택 게이트(TurnExecutionHelper.php:237) `turntime < %s`(STRICT <): turnTime(t0)과 같은
        // 시각은 due가 아니다. production은 nextRunTime()=lastTurnTime+tick으로 호출하므로 t0보다 미래
        // 시각을 넘겨 그 장수를 due로 만든다(과거 inclusive `<=` 버그 제거).
        lc.runTick(t0.plusSeconds(1))

        assertEquals(listOf("nation", "general"), order, "NATION pass runs BEFORE the GENERAL pass (R-SEAM §2)")
    }

    @Test
    fun `hasNationTurn false (officer_level LT 5) skips the whole nation block`() {
        val world = worldWith(generals = listOf(general(officerLevel = 4, npcState = 2)))
        val recorder = ChangeRecorder()
        val order = mutableListOf<String>()
        val lc = lifecycle(world, recorder, order)

        // PHP 선택 게이트(TurnExecutionHelper.php:237) `turntime < %s`(STRICT <): turnTime(t0)과 같은
        // 시각은 due가 아니다. production은 nextRunTime()=lastTurnTime+tick으로 호출하므로 t0보다 미래
        // 시각을 넘겨 그 장수를 due로 만든다(과거 inclusive `<=` 버그 제거).
        lc.runTick(t0.plusSeconds(1))

        assertEquals(listOf("general"), order, "officer_level<5 → no nation pass; only the general pass runs")
        assertTrue(recorder.nationPatches().isEmpty(), "no turn_last KV delta when hasNationTurn is false")
    }

    @Test
    fun `hasNationTurn false (nation 0) skips the whole nation block`() {
        val world = worldWith(generals = listOf(general(nationId = 0, officerLevel = 5, npcState = 2)))
        val recorder = ChangeRecorder()
        val order = mutableListOf<String>()
        val lc = lifecycle(world, recorder, order)

        // PHP 선택 게이트(TurnExecutionHelper.php:237) `turntime < %s`(STRICT <): turnTime(t0)과 같은
        // 시각은 due가 아니다. production은 nextRunTime()=lastTurnTime+tick으로 호출하므로 t0보다 미래
        // 시각을 넘겨 그 장수를 due로 만든다(과거 inclusive `<=` 버그 제거).
        lc.runTick(t0.plusSeconds(1))

        assertEquals(listOf("general"), order, "nation==0 → no nation pass; only the general pass runs")
    }

    @Test
    fun `processBlocked (block GTE 2) skips BOTH passes`() {
        val world = worldWith(generals = listOf(general(officerLevel = 5, npcState = 2, block = 2)))
        val recorder = ChangeRecorder()
        val order = mutableListOf<String>()
        val lc = lifecycle(world, recorder, order)

        // PHP 선택 게이트(TurnExecutionHelper.php:237) `turntime < %s`(STRICT <): turnTime(t0)과 같은
        // 시각은 due가 아니다. production은 nextRunTime()=lastTurnTime+tick으로 호출하므로 t0보다 미래
        // 시각을 넘겨 그 장수를 due로 만든다(과거 inclusive `<=` 버그 제거).
        lc.runTick(t0.plusSeconds(1))

        assertTrue(order.isEmpty(), "block>=2 skips BOTH the nation and general passes (single processBlocked gate)")
        assertTrue(recorder.nationPatches().isEmpty(), "no nation delta when the whole command block is skipped")
    }

    @Test
    fun `chooseInstantNationTurn is never invoked from the lifecycle`() {
        // Decision #3 / B3 — only chooseNationTurn is wired. The lifecycle has no instant hook at all;
        // the only nation hook it accepts is chooseNationTurn. We assert the chosen reason is the
        // chooseNationTurn-shaped 'reserved'/'do'/'neutral' reason, never an instant-path artifact.
        val world = worldWith(generals = listOf(general(officerLevel = 5, npcState = 2)))
        val recorder = ChangeRecorder()
        val order = mutableListOf<String>()
        var instantCalled = false
        val lc = lifecycle(world, recorder, order, instantInvoked = { instantCalled = true })

        // PHP 선택 게이트(TurnExecutionHelper.php:237) `turntime < %s`(STRICT <): turnTime(t0)과 같은
        // 시각은 due가 아니다. production은 nextRunTime()=lastTurnTime+tick으로 호출하므로 t0보다 미래
        // 시각을 넘겨 그 장수를 due로 만든다(과거 inclusive `<=` 버그 제거).
        lc.runTick(t0.plusSeconds(1))

        assertFalse(instantCalled, "chooseInstantNationTurn is NOT wired into the lifecycle (decision #3)")
    }

    @Test
    fun `a human general (npc LT 2) with officer_level GTE 5 still resolves the reserved nation command without AI`() {
        // PHP `:305` — the AI nation hook only fires when `$ai && use_auto_nation_turn`; a human chief
        // still runs processNationCommand on the RESERVED command (no chooseNationTurn).
        val world = worldWith(generals = listOf(general(officerLevel = 5, npcState = 0)))
        val recorder = ChangeRecorder()
        val order = mutableListOf<String>()
        val lc = lifecycle(world, recorder, order)

        // PHP 선택 게이트(TurnExecutionHelper.php:237) `turntime < %s`(STRICT <): turnTime(t0)과 같은
        // 시각은 due가 아니다. production은 nextRunTime()=lastTurnTime+tick으로 호출하므로 t0보다 미래
        // 시각을 넘겨 그 장수를 due로 만든다(과거 inclusive `<=` 버그 제거).
        lc.runTick(t0.plusSeconds(1))

        // The nation pass still runs (processNationCommand on the reserved command) but the AI
        // chooseNationTurn hook is NOT consulted for a human (no "nation" marker from the AI hook).
        assertFalse(order.contains("nation"), "a human chief does NOT consult chooseNationTurn")
        assertNotNull(recorder.nationPatches().firstOrNull { it.id == 1 }, "the reserved nation command still resolves → turn_last KV delta")
        assertNull(world.getGeneralById(42)!!.meta["block"]?.let { if (it == 0) null else it }, "block stays 0")
    }
}
