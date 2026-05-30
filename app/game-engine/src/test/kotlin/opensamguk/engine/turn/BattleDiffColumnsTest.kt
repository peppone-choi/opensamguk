package opensamguk.engine.turn

import opensamguk.logic.domain.City as LogicCity
import opensamguk.logic.domain.General as LogicGeneral
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F7 FU3 — the P4 battle/conquest diff-column surface on [ChangeRecorder].
 *
 * `diffCity` gains `term`/`officer_set`/`conflict` (PHP city.front == opensamguk front_state, ALREADY
 * diffed). `diffGeneral` gains `officer_city` (ConquerCity demotes the governor to 재야 — generals
 * SURVIVE, so markGeneralDeleted is NOT used by ConquerCity). Every mutation lands as a flush DELTA;
 * no inline DB write. RankColumn already carries the battle counters (confirmed below).
 */
class BattleDiffColumnsTest {

    private fun general(id: Int = 1, officerCity: Int = 0, officerLevel: Int = 5) = LogicGeneral(
        id = id,
        nationId = 1,
        cityId = 5,
        leadership = 80,
        strength = 70,
        intel = 60,
        injury = 0,
        experience = 0.0,
        dedication = 0.0,
        officerLevel = officerLevel,
        gold = 100,
        rice = 50,
        officerCity = officerCity,
    )

    private fun city(
        id: Int = 5,
        term: Int = 0,
        officerSet: Int = 0,
        conflict: String = "{}",
    ) = LogicCity(
        id = id,
        nationId = 1,
        level = 5,
        commerce = 200,
        commerceMax = 2000,
        agriculture = 100,
        agricultureMax = 1000,
        supplyState = 1,
        frontState = 0,
        trust = 80.0,
        term = term,
        officerSet = officerSet,
        conflict = conflict,
    )

    @Test
    fun `city term mutation emits a delta`() {
        val recorder = ChangeRecorder()
        val pre = city(term = 3)
        val patch = recorder.diffCity(pre, pre.copy(term = 0)) // ConquerCity resets term → 0
        assertEquals(0, patch!!.columns["term"])
        assertTrue(5 in recorder.dirtyCityIds())
    }

    @Test
    fun `city officer_set mutation emits a delta`() {
        val recorder = ChangeRecorder()
        val pre = city(officerSet = 2)
        val patch = recorder.diffCity(pre, pre.copy(officerSet = 0))
        assertEquals(0, patch!!.columns["officerSet"])
    }

    @Test
    fun `city conflict jsonb mutation emits a delta byte-faithfully`() {
        val recorder = ChangeRecorder()
        val pre = city(conflict = "{\"3\":1.05}")
        val patch = recorder.diffCity(pre, pre.copy(conflict = "{}")) // ConquerCity resets conflict → '{}'
        assertEquals("{}", patch!!.columns["conflict"])
    }

    @Test
    fun `unchanged battle columns yield no city delta`() {
        val recorder = ChangeRecorder()
        val pre = city(term = 1, officerSet = 1, conflict = "{\"2\":3.15}")
        assertNull(recorder.diffCity(pre, pre.copy()), "identical → null patch")
    }

    @Test
    fun `general officer_city mutation emits a delta (governor demoted to 재야)`() {
        val recorder = ChangeRecorder()
        val pre = general(officerCity = 5, officerLevel = 4)
        // process_war.php:705-708 — officer_city=0, officer_level=1; general SURVIVES (no tombstone).
        val patch = recorder.diffGeneral(pre, pre.copy(officerCity = 0, officerLevel = 1))
        assertEquals(0, patch!!.columns["officerCity"])
        assertEquals(1, patch.columns["officerLevel"])
        assertTrue(1 in recorder.dirtyGeneralIds())
        assertTrue(1 !in recorder.deletedGeneralIds(), "ConquerCity does NOT tombstone survivors")
    }

    @Test
    fun `RankColumn carries every battle counter`() {
        val columns = RankColumn.entries.map { it.column }.toSet()
        for (c in listOf(
            "warnum", "killnum", "deathnum", "killcrew", "deathcrew",
            "killcrew_person", "deathcrew_person", "occupied",
        )) {
            assertTrue(c in columns, "RankColumn missing battle counter '$c'")
        }
    }
}
