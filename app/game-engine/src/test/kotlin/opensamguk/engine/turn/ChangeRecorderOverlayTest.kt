package opensamguk.engine.turn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChangeRecorderOverlayTest {
    @Test
    fun `vote reservation rejects a duplicate until recorder clear`() {
        val recorder = ChangeRecorder()

        assertTrue(recorder.tryRecordVoteInsert(7, 11))
        assertFalse(recorder.tryRecordVoteInsert(7, 11))
        assertTrue(recorder.tryRecordVoteInsert(7, 12))

        recorder.clear()

        assertTrue(recorder.tryRecordVoteInsert(7, 11))
    }

    @Test
    fun `effective inheritance read observes latest pending value before base`() {
        val recorder = ChangeRecorder(
            initialInheritancePoints = mapOf(3 to mapOf("active_action" to listOf(2.0, "old"))),
        )

        assertEquals(2.0 to "old", recorder.effectiveInheritancePoint(3, "active_action"))
        assertNull(recorder.pendingInheritanceKv(3, "active_action"))

        recorder.recordInheritancePointSet(3, "active_action", 9.0, "new")

        assertEquals(9.0 to "new", recorder.effectiveInheritancePoint(3, "active_action"))
        assertEquals(listOf(9.0, "new"), recorder.pendingInheritanceKv(3, "active_action")?.value)
    }
}
