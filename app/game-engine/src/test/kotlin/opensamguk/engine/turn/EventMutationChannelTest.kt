package opensamguk.engine.turn

import opensamguk.logic.event.EventCondition
import opensamguk.logic.event.EventStore
import kotlin.test.Test
import kotlin.test.assertEquals

class EventMutationChannelTest {

    @Test
    fun `event store mutations are recorded as ordered flush rows`() {
        val store = EventStore()
        val recorder = ChangeRecorder()
        store.bindMutationSink(recorder::recordEventMutation)

        val id = store.insert("month", 1234, EventCondition.ConstBool(true), emptyList())
        store.delete(id)

        assertEquals(1, recorder.eventInserts().size)
        assertEquals(id, recorder.eventInserts().single().id)
        assertEquals("month", recorder.eventInserts().single().targetCode)
        assertEquals(listOf(id), recorder.eventDeletes())
        assertEquals(true, recorder.isDirty)

        recorder.clear()
        assertEquals(emptyList(), recorder.eventInserts())
        assertEquals(emptyList(), recorder.eventDeletes())
    }
}
