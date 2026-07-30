package opensamguk.engine.turn

import opensamguk.engine.flush.DatabaseHooks
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.tick.ServerClock
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * B4 Task LC1 — the post-command lifecycle tail (killturn decrement/reset, block branch, updateTurnTime).
 *
 * Port target: `TurnExecutionHelper.php:153-165` (processCommand killturn), `:47-70` (processBlocked),
 * `:170-230` (updateTurnTime: +1 lived_month inheritance, then `turntime = addTurn(...)`).
 *
 * The engine [TurnGeneral] is the P0-B/P1 slice: `npcState` is the `npc` column, `age` is a column,
 * and the lifecycle scalars (`killturn`/`deadyear`/`block`/`owner`/`owner_name`/`npc_org`/`lived_month`)
 * ride the `meta` bag (same convention `UpdateNationLevel` reads `meta["killturn"]`). LC1 mutates the
 * world's stored row in place while [ReservedTurnHandler.recorder] remains the single dirty seam.
 */
class LifecycleTailTest {

    private val t0 = Instant.parse("0200-06-15T14:00:00Z")
    private val turnTerm = 120

    private fun env(
        killturn: Int = 12,
        year: Int = 200,
        month: Int = 6,
        isunited: Int = 0,
        autorunMode: Boolean = false,
    ) = LifecycleEnv(
        baselineKillturn = killturn,
        year = year,
        month = month,
        turnTerm = turnTerm,
        isunited = isunited,
        autorunMode = autorunMode,
    )

    private fun gen(
        id: Int = 1,
        npc: Int = 0,
        age: Int = 30,
        killturn: Int = 12,
        block: Int = 0,
        deadyear: Int = 999,
        extraMeta: Map<String, Any?> = emptyMap(),
    ) = TurnGeneral(
        id = id,
        name = "g$id",
        nationId = 1,
        cityId = 5,
        troopId = 0,
        stats = GeneralStats(80, 70, 60),
        experience = 0,
        dedication = 0,
        officerLevel = 1,
        age = age,
        npcState = npc,
        turnTime = t0,
        meta = linkedMapOf<String, Any?>(
            "killturn" to killturn,
            "block" to block,
            "deadyear" to deadyear,
            "lived_month" to 100,
        ) + extraMeta,
    )

    private fun world(g: TurnGeneral, accessLog: GeneralAccessLog? = null) =
        InMemoryTurnWorld(
            WorldSnapshot(
                state = TurnWorldState(1, 200, 6, 3600, t0),
                generals = listOf(g),
                nations = listOf(Nation(1, "n1", "#000")),
                accessLogs = listOfNotNull(accessLog),
                worldId = opensamguk.common.world.WorldId((TurnWorldState(1, 200, 6, 3600, t0)).id),
            ),
        )

    private fun handler(world: InMemoryTurnWorld) =
        ReservedTurnHandler(
            world,
            registry = CommandRegistry(GeneralActionPipeline()),
            hiddenSeed = "0".repeat(32),
            startYear = 184,
        )

    private fun killturnOf(world: InMemoryTurnWorld, id: Int): Int =
        (world.getGeneralById(id)!!.meta["killturn"] as Number).toInt()

    // ── killturn decrement (processCommand:153-165) ──────────────────────────────────────────────

    @Test
    fun `NPC type 2 decrements killturn by 1`() {
        val w = world(gen(npc = 2, killturn = 5))
        handler(w).applyKillturnDecrement(1, commandClassName = "상업투자", env = env(killturn = 12))
        assertEquals(4, killturnOf(w, 1))
    }

    @Test
    fun `killturn over baseline decrements by 1`() {
        val w = world(gen(npc = 0, killturn = 20))
        handler(w).applyKillturnDecrement(1, commandClassName = "상업투자", env = env(killturn = 12))
        assertEquals(19, killturnOf(w, 1))
    }

    @Test
    fun `휴식 command decrements killturn by 1`() {
        val w = world(gen(npc = 0, killturn = 5))
        val h = handler(w)

        h.applyKillturnDecrement(1, commandClassName = "휴식", env = env(killturn = 12))

        assertEquals(4, killturnOf(w, 1))
        assertEquals(setOf(1), h.recorder.dirtyGeneralIds(), "휴식 후처리도 general UPDATE를 flush해야 한다")
    }

    @Test
    fun `autorun mode decrements killturn by 1`() {
        val w = world(gen(npc = 0, killturn = 5))
        handler(w).applyKillturnDecrement(1, commandClassName = "상업투자", env = env(killturn = 12, autorunMode = true))
        assertEquals(4, killturnOf(w, 1))
    }

    @Test
    fun `a human at-or-under baseline running a real command resets killturn to baseline`() {
        val w = world(gen(npc = 0, killturn = 5))
        handler(w).applyKillturnDecrement(1, commandClassName = "상업투자", env = env(killturn = 12))
        assertEquals(12, killturnOf(w, 1))
    }

    @Test
    fun `killturn decrement is NOT floored - it can cross below zero (the kill gate reads it)`() {
        // increaseVarWithLimit('killturn', -1) has NO floor in processCommand (LazyVarUpdater.php:80).
        val w = world(gen(npc = 2, killturn = 0))
        handler(w).applyKillturnDecrement(1, commandClassName = "휴식", env = env(killturn = 12))
        assertEquals(-1, killturnOf(w, 1))
    }

    // ── block branch (processBlocked:47-70) ──────────────────────────────────────────────────────

    @Test
    fun `block under 2 does nothing and does not skip the command`() {
        val w = world(gen(block = 1, killturn = 5))
        val blocked = handler(w).processBlocked(1, env = env())
        assertFalse(blocked)
        assertEquals(5, killturnOf(w, 1))
    }

    @Test
    fun `block 2 decrements killturn floored at 0, pushes the multi-block log, and skips the command`() {
        val w = world(gen(block = 2, killturn = 5))
        val h = handler(w)

        val blocked = h.processBlocked(1, env = env())

        assertTrue(blocked, "block>=2 returns true to skip the command")
        assertEquals(4, killturnOf(w, 1))
        assertEquals(setOf(1), h.recorder.dirtyGeneralIds(), "블럭 후처리도 general UPDATE를 flush해야 한다")
        val logs = w.consumeDirtyState().logs
        assertTrue(logs.any { it.text.contains("멀티, 또는 비매너로 인한") && it.text.contains("<R>블럭</>") })
    }

    @Test
    fun `block 3 decrements killturn and pushes the malicious-user block log`() {
        val w = world(gen(block = 3, killturn = 5))
        val blocked = handler(w).processBlocked(1, env = env())
        assertTrue(blocked)
        assertEquals(4, killturnOf(w, 1))
        assertTrue(w.consumeDirtyState().logs.any { it.text.contains("악성유저로 분류") })
    }

    @Test
    fun `block killturn decrement is floored at 0 (increaseVarWithLimit min 0)`() {
        val w = world(gen(block = 2, killturn = 0))
        handler(w).processBlocked(1, env = env())
        assertEquals(0, killturnOf(w, 1))
    }

    // ── updateTurnTime (:170-230) ────────────────────────────────────────────────────────────────

    @Test
    fun `updateTurnTime increments lived_month and advances turntime by addTurn`() {
        val w = world(
            gen(npc = 0, age = 30, killturn = 5),
            GeneralAccessLog(1, 7, t0, refreshScore = 17, refreshScoreTotal = 90),
        )
        val h = handler(w)

        h.updateTurnTime(1, env = env())

        val g = w.getGeneralById(1)!!
        assertEquals(9, (g.meta["myset"] as Number).toInt(), "myset +3 capped at 9")
        assertEquals(101, (g.meta["lived_month"] as Number).toInt(), "lived_month +1")
        assertEquals(ServerClock.addTurn(t0, turnTerm, 1), g.turnTime, "turntime advanced by addTurn")
        assertEquals(setOf(1), h.recorder.dirtyGeneralIds(), "turntime 후처리도 general UPDATE를 flush해야 한다")
        val payload = DatabaseHooks.toFlushPayload(w, h.recorder, w.consumeDirtyState())
        assertEquals(ServerClock.addTurn(t0, turnTerm, 1), payload.updatedGenerals.single().turnTime)
        assertEquals(30, payload.updatedGenerals.single().age)
        assertEquals(0, payload.generalAccessLogUpserts.single().refreshScore)
        assertEquals(90, payload.generalAccessLogUpserts.single().refreshScoreTotal)
    }

    @Test
    fun `updateTurnTime is a no-op kill path when killturn positive and age under retirement`() {
        val w = world(gen(npc = 0, age = 30, killturn = 5))
        val outcome = handler(w).updateTurnTime(1, env = env())
        assertEquals(LifecycleOutcome.SURVIVED, outcome)
        assertFalse(w.consumeDirtyState().deletedGenerals.contains(1), "no tombstone for a surviving general")
    }
}
