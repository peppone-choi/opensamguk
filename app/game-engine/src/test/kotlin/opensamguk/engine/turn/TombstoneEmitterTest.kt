package opensamguk.engine.turn

import opensamguk.logic.domain.General as LogicGeneral
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F3 Task FF1 — the delete/tombstone emitter on the SINGLE dirty source.
 *
 * Faithful to `General.php:515-600` (kill: storeOldGeneral → DELETE general/general_turn/rank_data
 * + gennum-1) and the nation cascade. The [ChangeRecorder] gains `markGeneralDeleted` /
 * `markNationDeleted` so the recorder — NOT a bare `world.removeX` — is the lone emitter: a
 * tombstoned row leaves the update-set (kill() clears updatedVar at `General.php:595`, so a trailing
 * applyDB must NOT re-INSERT) and lands ONCE in `DirtyState.deletedGenerals` / `deletedNations`.
 */
class TombstoneEmitterTest {
    private val t0 = Instant.parse("0200-01-01T00:00:00Z")

    private fun engineGeneral(id: Int, nationId: Int = 1, gold: Int = 100) = TurnGeneral(
        id = id,
        name = "g$id",
        nationId = nationId,
        cityId = 5,
        troopId = 0,
        stats = GeneralStats(80, 70, 60),
        experience = 0,
        dedication = 0,
        officerLevel = 0,
        gold = gold,
        turnTime = t0,
    )

    private fun engineNation(id: Int) = Nation(id = id, name = "n$id", color = "#000")

    private fun engineCity(id: Int, nationId: Int) = City(
        id = id, name = "c$id", nationId = nationId, level = 5, frontState = 1,
        meta = mapOf("conflict" to mapOf("2" to 1)),
    )

    private fun logicGeneral(id: Int, nationId: Int = 1, gold: Int = 100) = LogicGeneral(
        id = id, nationId = nationId, cityId = 5,
        leadership = 80, strength = 70, intel = 60, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = 0, gold = gold, rice = 0,
    )

    private fun baseState() = TurnWorldState(
        id = 1, currentYear = 200, currentMonth = 1, tickSeconds = 3600, lastTurnTime = t0,
    )

    @Test
    fun `markGeneralDeleted feeds deletedGenerals and excludes it from the update-set`() {
        val world = InMemoryTurnWorld(WorldSnapshot(state = baseState(), generals = listOf(engineGeneral(1))))
        val recorder = ChangeRecorder()

        recorder.markGeneralDeleted(world, 1)

        // the recorder no longer treats it as an UPDATE (no double-apply)
        assertFalse(recorder.dirtyGeneralIds().contains(1), "tombstoned general not in update-set")
        // the world dropped it and emits the delete exactly once
        val drained = world.consumeDirtyState()
        assertEquals(listOf(1), drained.deletedGenerals)
        assertTrue(drained.generals.none { it.id == 1 }, "tombstoned general not in dirty update rows")
    }

    @Test
    fun `a marked-then-mutated general emits ONLY the delete`() {
        val world = InMemoryTurnWorld(WorldSnapshot(state = baseState(), generals = listOf(engineGeneral(1, gold = 100))))
        val recorder = ChangeRecorder()

        // a mutation recorded BEFORE the kill must be discarded once the row is tombstoned.
        recorder.diffGeneral(logicGeneral(1, gold = 100), logicGeneral(1, gold = 250))
        assertTrue(recorder.dirtyGeneralIds().contains(1), "mutation recorded pre-kill")

        recorder.markGeneralDeleted(world, 1)

        assertFalse(recorder.dirtyGeneralIds().contains(1), "kill clears the pending update (General.php:595)")
        // a mutation recorded AFTER the kill is ignored too (tombstoned row never re-enters the update-set).
        recorder.diffGeneral(logicGeneral(1, gold = 250), logicGeneral(1, gold = 999))
        assertFalse(recorder.dirtyGeneralIds().contains(1), "post-kill mutation discarded")

        assertEquals(listOf(1), world.consumeDirtyState().deletedGenerals)
    }

    @Test
    fun `the snapshot list captures the old-general before delete`() {
        val world = InMemoryTurnWorld(WorldSnapshot(state = baseState(), generals = listOf(engineGeneral(7, gold = 4242))))
        val recorder = ChangeRecorder()

        recorder.markGeneralDeleted(world, 7)

        val snapshots = recorder.oldGeneralSnapshots()
        assertEquals(1, snapshots.size)
        assertEquals(7, snapshots.single().id)
        assertEquals(4242, snapshots.single().gold, "snapshot captured the pre-delete row")
    }

    @Test
    fun `markNationDeleted marks the nation, reverts captured cities, and snapshots its generals`() {
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state = baseState(),
                generals = listOf(engineGeneral(11, nationId = 2), engineGeneral(12, nationId = 2)),
                cities = listOf(engineCity(5, nationId = 2), engineCity(6, nationId = 2)),
                nations = listOf(engineNation(2)),
            ),
        )
        val recorder = ChangeRecorder()

        recorder.markNationDeleted(world, 2)

        // nation cascade: the nation lands in deletedNations exactly once.
        val drained = world.consumeDirtyState()
        assertEquals(listOf(2), drained.deletedNations)
        assertTrue(drained.nations.none { it.id == 2 }, "deleted nation not in dirty update rows")

        // captured cities reverted to neutral (nation=0, front_state=0, conflict removed) as city patches.
        val cityPatches = recorder.cityPatches().associateBy { it.id }
        assertEquals(0, cityPatches.getValue(5).columns["nationId"])
        assertEquals(0, cityPatches.getValue(5).columns["frontState"])
        assertEquals(0, cityPatches.getValue(6).columns["nationId"])

        // the nation snapshot captures the nation + its general ids (the ng_old_nations archive write).
        val snap = recorder.nationSnapshots().single()
        assertEquals(2, snap.nation.id)
        assertEquals(listOf(11, 12), snap.generalIds)
    }
}
