package opensamguk.engine.turn

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import opensamguk.common.world.WorldId
import opensamguk.logic.event.EventStore
import opensamguk.logic.record.AudienceTarget
import opensamguk.logic.record.EventKey
import opensamguk.logic.record.EventKind

class TurnUnitExecutorTest {
    private val turnTime = Instant.parse("0200-01-01T00:00:00Z")

    private fun general(id: Int, meta: Map<String, Any?> = emptyMap()) = TurnGeneral(
        id = id, name = "g$id", nationId = 1, cityId = 1, troopId = 0,
        stats = GeneralStats(70, 70, 70), experience = 0, dedication = 0,
        officerLevel = 0, turnTime = turnTime, meta = meta,
    )

    private fun world(vararg generals: TurnGeneral) = InMemoryTurnWorld(WorldSnapshot(
        state = TurnWorldState(1, 200, 1, 3600, turnTime),
        worldId = WorldId(1), generals = generals.toList(),
    ))

    @Test
    fun `one failed general leaves no partial write and the next general still commits`() {
        val nested = mutableListOf("before")
        val world = world(general(1, mapOf("nested" to nested)), general(2))
        val recorder = ChangeRecorder(kvWriteObserver = world::applyKvDirtyFree)
        val executor = TurnUnitExecutor(world, recorder)

        val failed = executor.run {
            world.updateGeneral(checkNotNull(world.getGeneralById(1)).copy(gold = 900))
            nested.add("failed")
            recorder.recordKv("game_env", "game_env", "failed", 1)
            world.pushLog(LogEntryDraft("general", "turn", "failed", generalId = 1))
            throw IllegalStateException("injected general failure")
        }
        assertIs<TurnUnitExecutor.Outcome.Failed>(failed)
        assertEquals(0, world.getGeneralById(1)?.gold)
        assertEquals(listOf("before"), world.getGeneralById(1)?.meta?.get("nested"))
        assertTrue("failed" !in world.getState().meta)
        assertTrue(recorder.kvDirty().isEmpty())

        val succeeded = executor.run {
            world.updateGeneral(checkNotNull(world.getGeneralById(2)).copy(gold = 42))
            recorder.recordKv("game_env", "game_env", "successful", 2)
            world.pushLog(LogEntryDraft("general", "turn", "successful", generalId = 2))
            "saved"
        }
        assertEquals("saved", assertIs<TurnUnitExecutor.Outcome.Succeeded<String>>(succeeded).value)
        val dirty = world.consumeDirtyState()
        assertEquals(listOf(2), dirty.generals.map { it.id })
        assertEquals(listOf("successful"), dirty.logs.map { it.text })
        assertEquals(mapOf(KvKey("game_env", "game_env", "successful") to 2), recorder.kvDirty())
        assertEquals(2, world.getState().meta["successful"])
    }

    @Test
    fun `failed event ordinal and allocated general id stay consumed after restore`() {
        val world = world(general(1))
        val recorder = ChangeRecorder()
        val executor = TurnUnitExecutor(world, recorder)
        val firstId = world.allocateGeneralId()
        assertEquals(2, firstId)

        assertIs<TurnUnitExecutor.Outcome.Failed>(executor.run {
            assertEquals(3, world.allocateGeneralId())
            world.recordEvent(EventKind.TURN_CATCH_UP_FINISHED, AudienceTarget.Public, EventKey.derive("failed"))
            throw IllegalStateException("injected event failure")
        })

        assertEquals(4, world.allocateGeneralId())
        assertEquals(4, world.getState().meta["maxGeneralId"])
        val event = world.recordEvent(
            EventKind.TURN_CATCH_UP_FINISHED, AudienceTarget.Public, EventKey.derive("successful"),
        )
        assertEquals(1, event.occurredAt.ordinal)
        assertEquals(listOf(event), world.consumeDirtyState().gameEvents)
    }

    @Test
    fun `event validation failure also consumes its ordinal`() {
        val world = world(general(1))
        val executor = TurnUnitExecutor(world, ChangeRecorder())
        assertIs<TurnUnitExecutor.Outcome.Failed>(executor.run {
            world.recordEvent(EventKind.INPUT_REJECTED, AudienceTarget.Public, EventKey.derive("invalidAudience"))
        })
        val next = world.recordEvent(
            EventKind.TURN_CATCH_UP_FINISHED, AudienceTarget.Public, EventKey.derive("afterInvalidAudience"),
        )
        assertEquals(1, next.occurredAt.ordinal)
    }

    @Test
    fun `a later failed unit preserves already successful world and recorder deltas`() {
        val world = world(general(1), general(2))
        val recorder = ChangeRecorder(kvWriteObserver = world::applyKvDirtyFree)
        val executor = TurnUnitExecutor(world, recorder)
        assertIs<TurnUnitExecutor.Outcome.Succeeded<*>>(executor.run {
            world.updateGeneral(checkNotNull(world.getGeneralById(1)).copy(gold = 11))
            recorder.recordKv("game_env", "game_env", "first", 11)
        })
        assertIs<TurnUnitExecutor.Outcome.Failed>(executor.run {
            world.updateGeneral(checkNotNull(world.getGeneralById(2)).copy(gold = 22))
            recorder.recordKv("game_env", "game_env", "second", 22)
            throw IllegalStateException("later failure")
        })
        assertEquals(listOf(1), world.consumeDirtyState().generals.map { it.id })
        assertEquals(11, world.getGeneralById(1)?.gold)
        assertEquals(0, world.getGeneralById(2)?.gold)
        assertEquals(listOf(KvKey("game_env", "game_env", "first")), recorder.kvDirty().keys.toList())
        assertEquals(11, world.getState().meta["first"])
        assertTrue("second" !in world.getState().meta)
    }

    @Test
    fun `failed date change does not reuse an event ordinal when that date returns`() {
        val world = world(general(1))
        val executor = TurnUnitExecutor(world, ChangeRecorder())
        assertIs<TurnUnitExecutor.Outcome.Failed>(executor.run {
            world.setCurrentDate(200, 2, 1)
            world.recordEvent(EventKind.TURN_CATCH_UP_FINISHED, AudienceTarget.Public, EventKey.derive("failedInFebruary"))
            throw IllegalStateException("failed month")
        })
        assertEquals(1, world.getState().currentMonth)
        world.setCurrentDate(200, 2, 1)
        val event = world.recordEvent(
            EventKind.TURN_CATCH_UP_FINISHED, AudienceTarget.Public, EventKey.derive("successfulInFebruary"),
        )
        assertEquals(1, event.occurredAt.ordinal)
    }

    @Test
    fun `failed remove and create restore original row order`() {
        val world = world(general(1), general(2))
        val executor = TurnUnitExecutor(world, ChangeRecorder())
        assertIs<TurnUnitExecutor.Outcome.Failed>(executor.run {
            world.removeGeneral(1)
            world.createGeneral(general(3))
            throw IllegalStateException("failed replacement")
        })
        assertEquals(listOf(1, 2), world.listGenerals().map { it.id })
        val dirty = world.consumeDirtyState()
        assertTrue(dirty.createdGenerals.isEmpty())
        assertTrue(dirty.deletedGenerals.isEmpty())
    }

    @Test
    fun `iteration exposes mutable nested metadata without escaping the before image`() {
        val nested = mutableListOf("before")
        val world = world(general(1, mapOf("nested" to nested)))
        val executor = TurnUnitExecutor(world, ChangeRecorder())
        assertIs<TurnUnitExecutor.Outcome.Failed>(executor.run {
            @Suppress("UNCHECKED_CAST")
            val exposed = world.listGenerals().single().meta["nested"] as MutableList<String>
            exposed.add("failed")
            throw IllegalStateException("failed nested change")
        })
        assertEquals(listOf("before"), world.getGeneralById(1)?.meta?.get("nested"))
    }

    @Test
    fun `checkpoint cannot restore another world`() {
        val source = world(general(1))
        val other = world(general(1))
        val checkpoint = source.checkpoint()
        val error = kotlin.runCatching { other.restore(checkpoint) }.exceptionOrNull()
        assertNotNull(error)
        assertIs<IllegalArgumentException>(error)
    }

    @Test
    fun `failed event row mutation restores rows without reusing serial ids`() {
        val world = world(general(1))
        val recorder = ChangeRecorder()
        val store = EventStore.withDefaults()
        store.bindMutationSink(recorder::recordEventMutation)
        val initialRows = store.allRows()
        val executor = TurnUnitExecutor(world, recorder, store)

        assertIs<TurnUnitExecutor.Outcome.Failed>(executor.run {
            store.delete(initialRows.first().id)
            store.insert("month", 1, initialRows.first().condition, initialRows.first().actions)
            throw IllegalStateException("failed event row mutation")
        })

        assertEquals(initialRows, store.allRows())
        assertTrue(!recorder.isDirty)
        val nextId = store.insert("month", 1, initialRows.first().condition, initialRows.first().actions)
        assertEquals(initialRows.maxOf { it.id } + 2, nextId)
    }
}
