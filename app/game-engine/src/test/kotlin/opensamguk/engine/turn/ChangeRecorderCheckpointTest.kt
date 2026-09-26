package opensamguk.engine.turn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import java.lang.reflect.Modifier

class ChangeRecorderCheckpointTest {
    @Test
    fun `checkpoint covers every recorder mutable channel exactly once`() {
        val recorder = ChangeRecorder()
        val targets = recorder.checkpoint().capturedTargets()
        val fields = ChangeRecorder::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
        val channels = fields.mapNotNull { field ->
            field.isAccessible = true
            val value = field.get(recorder)
            if (value is Map<*, *> || value is Collection<*>) field.name to value else null
        }
        val scalarFields = fields.filter {
            !Modifier.isFinal(it.modifiers) && it.name != "generationSession"
        }.map { it.name }.toSet()

        assertEquals(channels.size, targets.count { it is Map<*, *> || it is Collection<*> })
        channels.forEach { (name, value) ->
            assertEquals(1, targets.count { it === value }, "checkpoint channel: $name")
        }
        assertEquals(scalarFields, targets.filterIsInstance<String>().toSet())
        assertEquals(channels.size + scalarFields.size, targets.size)
    }

    @Test
    fun `restore removes failed unit deltas but keeps earlier ordered channels`() {
        val recorder = ChangeRecorder()
        val items = mutableListOf("before")
        val nested = linkedMapOf<String, Any?>("items" to items)
        recorder.recordKv("game_env", "game_env", "sample", nested)
        recorder.recordRankIncrease(7, RankColumn.FIRENUM, 2)
        recorder.recordVotePollUpdate(3, linkedMapOf("end_at" to "before"))
        recorder.recordInheritancePointSet(4, "previous", 5.0, listOf("before"))
        val checkpoint = recorder.checkpoint()

        items.add("failed")
        recorder.recordKv("game_env", "game_env", "failed", 1)
        recorder.recordRankIncrease(7, RankColumn.FIRENUM, 9)
        recorder.recordVotePollUpdate(3, linkedMapOf("closed_at" to "failed"))
        recorder.recordInheritancePointSet(4, "previous", 99.0, listOf("failed"))
        recorder.recordGeneralTurnPull(7)
        recorder.restore(checkpoint)

        assertEquals(listOf(KvKey("game_env", "game_env", "sample")), recorder.kvDirty().keys.toList())
        assertEquals(mapOf("items" to listOf("before")), recorder.kvDirty().values.single())
        assertEquals(RankDelta.Increment(2), recorder.rankDeltas(7)[RankColumn.FIRENUM])
        assertEquals(mapOf("end_at" to "before"), recorder.votePollUpdates()[3])
        assertEquals(5.0 to listOf("before"), recorder.effectiveInheritancePoint(4, "previous"))
        assertEquals(emptyList(), recorder.reservedGeneralTurnPulls())
        recorder.restore(checkpoint)
        assertEquals(mapOf("items" to listOf("before")), recorder.kvDirty().values.single())
    }

    @Test
    fun `empty checkpoint discards all recorder mutations and belongs to one recorder`() {
        val recorder = ChangeRecorder()
        val checkpoint = recorder.checkpoint()
        recorder.recordKv("game_env", "game_env", "failed", 1)
        recorder.recordGeneralTurnPull(9)
        recorder.restore(checkpoint)

        assertFalse(recorder.isDirty)
        assertEquals(emptyMap(), recorder.kvDirty())
        assertFailsWith<IllegalArgumentException> { ChangeRecorder().restore(checkpoint) }
    }

    @Test
    fun `restoring inserts does not reuse allocated ids`() {
        val recorder = ChangeRecorder()
        fun insert() = recorder.recordMessageInsert(1, "NOTICE", 1, 2, "now", "later", "{}")
        assertEquals(1, insert())
        val checkpoint = recorder.checkpoint()
        assertEquals(2, insert())
        recorder.restore(checkpoint)
        assertEquals(3, insert())
        assertEquals(listOf(1, 3), recorder.createdMessages().map { it.id })
    }
}
