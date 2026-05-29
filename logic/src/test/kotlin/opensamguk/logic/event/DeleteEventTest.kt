package opensamguk.logic.event

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * B5 / Task LT2 — DeleteEvent (PHP `Event/Action/DeleteEvent.php:10-20`).
 *
 * `DELETE FROM event WHERE id = currentEventID` — a tombstone-delete on the [EventStore] using the
 * dispatcher-injected `currentEventID`. PHP throws RuntimeException when currentEventID is unset
 * ("currentEventID가 지정되지 않았습니다."). It must NOT double-apply within a single dispatch — the F2
 * dispatcher freezes the row set BEFORE running, so a self-delete removes the row from the STORE but the
 * in-flight set is unaffected (the deleted row still ran this dispatch but is gone next). The
 * founding-limit notices self-delete on schedule (Scenario.php:560-597 DeleteEvent injection).
 *
 * DeleteEvent binds to the [EventStore] via the [DeleteEventContext] seam the dispatcher/tick threads
 * under `env[DeleteEventContext.ENV_KEY]` (B5-owned; no widening of the F2 dispatcher). The
 * already-frozen-set semantics are proven in EventDispatcherTest; here we pin the leaf itself.
 */
class DeleteEventTest {

    private fun ctxWith(store: EventStore, currentEventID: Int): EventActionContext {
        val e = mutableMapOf<String, Any?>(
            "currentEventID" to currentEventID,
            DeleteEventContext.ENV_KEY to store,
        )
        return object : EventActionContext { override val env = e }
    }

    @Test
    fun `DeleteEvent removes the current event row exactly once`() {
        val store = EventStore()
        val id = store.insert("month", 2000, EventCondition.ConstBool(true), listOf(RawAction("DeleteEvent", emptyList())))
        store.insert("month", 2000, EventCondition.ConstBool(true), listOf(RawAction("X", emptyList())))
        assertEquals(2, store.allRows().size)

        DeleteEventAction().run(ctxWith(store, id))

        assertTrue(store.allRows().none { it.id == id }, "the currentEventID row is deleted")
        assertEquals(1, store.allRows().size, "exactly one row removed")
    }

    @Test
    fun `a second DeleteEvent on an already-deleted id is idempotent (no double-apply)`() {
        val store = EventStore()
        val id = store.insert("month", 2000, EventCondition.ConstBool(true), listOf(RawAction("DeleteEvent", emptyList())))

        DeleteEventAction().run(ctxWith(store, id))
        // Re-running on the same id (e.g. a stray re-dispatch) must not throw or corrupt the store.
        DeleteEventAction().run(ctxWith(store, id))

        assertEquals(0, store.allRows().size)
    }

    @Test
    fun `DeleteEvent throws when currentEventID is unset (zero)`() {
        // PHP DeleteEvent.php:13-15 throws when Util::array_get(currentEventID) is falsy.
        val store = EventStore()
        assertFailsWith<IllegalStateException> {
            DeleteEventAction().run(ctxWith(store, 0))
        }
    }

    @Test
    fun `DeleteEvent is registered into the factory by name`() {
        val factory = EventActionFactory().also { DeleteEventAction.register(it) }
        assertTrue(factory.has("DeleteEvent"))
        val action = factory.create(RawAction("DeleteEvent", emptyList()))
        assertTrue(action is DeleteEventAction)
    }

    @Test
    fun `within a dispatch the deleted row already ran but does NOT re-fire next dispatch`() {
        // End-to-end through the F2 dispatcher: a row that self-deletes via the real DeleteEventAction.
        val log = mutableListOf<String>()
        val store = EventStore()
        store.insert("month", 2000, EventCondition.ConstBool(true), listOf(RawAction("DeleteEvent", emptyList()))) // id1
        store.insert("month", 2000, EventCondition.ConstBool(true), listOf(RawAction("Mark", emptyList())))       // id2
        val factory = EventActionFactory().also { DeleteEventAction.register(it) }
        factory.register("Mark") { _ ->
            object : EventAction { override fun run(ctx: EventActionContext) { log.add("mark#${ctx.currentEventID}") } }
        }
        val dispatcher = EventDispatcher(store, factory)

        // First dispatch: both rows are in the frozen set; id1 deletes itself, id2 marks.
        dispatcher.run(EventTarget.MONTH) { mutableMapOf(DeleteEventContext.ENV_KEY to store) }
        assertEquals(listOf("mark#2"), log)
        assertTrue(store.allRows().none { it.id == 1 }, "id1 tombstoned in the store")

        // Second dispatch: id1 is gone → only id2 fires again.
        dispatcher.run(EventTarget.MONTH) { mutableMapOf(DeleteEventContext.ENV_KEY to store) }
        assertEquals(listOf("mark#2", "mark#2"), log, "the deleted row does not re-fire")
    }
}
