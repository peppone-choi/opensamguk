package opensamguk.logic.inheritance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InheritancePointStoreTest {
    @Test
    fun `set stores value for direct storage keys`() {
        val store = InheritancePointStore()
        store.set(1, "previous", 100.0, null)
        assertEquals(100.0 to null, store.get(1, "previous"))
    }

    @Test
    fun `set rejects non direct storage keys`() {
        val store = InheritancePointStore()
        val ex = kotlin.runCatching { store.set(1, "dex", 10.0, null) }.exceptionOrNull()
        assertEquals(true, ex is IllegalArgumentException)
    }

    @Test
    fun `increaseRaw accumulates with same aux`() {
        val store = InheritancePointStore()
        store.increaseRaw(1, "previous", 10.0, "aux1")
        store.increaseRaw(1, "previous", 20.0, "aux1")
        assertEquals(30.0 to "aux1", store.get(1, "previous"))
    }

    @Test
    fun `increaseRaw resets on aux change`() {
        val store = InheritancePointStore()
        store.increaseRaw(1, "previous", 10.0, "aux1")
        store.increaseRaw(1, "previous", 5.0, "aux2")
        assertEquals(5.0 to "aux2", store.get(1, "previous"))
    }

    @Test
    fun `clear preserves previous`() {
        val store = InheritancePointStore()
        store.putRaw(1, "previous", 100.0, null)
        store.putRaw(1, "lived_month", 50.0, null)
        store.clear(1)
        assertEquals(100.0 to null, store.get(1, "previous"))
        assertNull(store.get(1, "lived_month"))
    }

    @Test
    fun `getAll returns copy of stored data`() {
        val store = InheritancePointStore()
        store.putRaw(1, "previous", 10.0, null)
        val all = store.getAll(1)
        assertEquals(1, all.size)
        assertEquals(10.0 to null, all["previous"])
    }
}
