package opensamguk.logic.event

import opensamguk.logic.domain.General
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StaticEventHandlerTest {

    @Test
    fun `handleEvent with empty registry is no-op`() {
        val general = General(id = 1, nationId = 1, cityId = 1, leadership = 50, strength = 50, intel = 50, injury = 0, experience = 0.0, dedication = 0.0, officerLevel = 0, gold = 0, rice = 0)
        StaticEventHandler.handleEvent(general, null, "testEvent", emptyMap(), emptyMap())
        // no exception = pass
    }

    @Test
    fun `registered handler receives all arguments`() {
        val general = General(id = 1, nationId = 1, cityId = 1, leadership = 50, strength = 50, intel = 50, injury = 0, experience = 0.0, dedication = 0.0, officerLevel = 0, gold = 0, rice = 0)
        val destGeneral = General(id = 2, nationId = 1, cityId = 1, leadership = 60, strength = 60, intel = 60, injury = 0, experience = 0.0, dedication = 0.0, officerLevel = 0, gold = 0, rice = 0)
        val env = linkedMapOf<String, Any?>("year" to 200, "month" to 1)
        val params = linkedMapOf<String, Any?>("amount" to 100)

        var receivedGeneral: General? = null
        var receivedDest: General? = null
        var receivedEnv: Map<String, Any?>? = null
        var receivedParams: Map<String, Any?>? = null

        StaticEventHandler.register("testEvent") { g, d, e, p ->
            receivedGeneral = g
            receivedDest = d
            receivedEnv = e
            receivedParams = p
        }

        StaticEventHandler.handleEvent(general, destGeneral, "testEvent", env, params)

        assertEquals(general, receivedGeneral)
        assertEquals(destGeneral, receivedDest)
        assertEquals(env, receivedEnv)
        assertEquals(params, receivedParams)
    }

    @Test
    fun `multiple handlers for same event are called in registration order`() {
        val general = General(id = 1, nationId = 1, cityId = 1, leadership = 50, strength = 50, intel = 50, injury = 0, experience = 0.0, dedication = 0.0, officerLevel = 0, gold = 0, rice = 0)
        val order = mutableListOf<Int>()

        StaticEventHandler.register("multiEvent") { _, _, _, _ -> order.add(1) }
        StaticEventHandler.register("multiEvent") { _, _, _, _ -> order.add(2) }
        StaticEventHandler.register("multiEvent") { _, _, _, _ -> order.add(3) }

        StaticEventHandler.handleEvent(general, null, "multiEvent", emptyMap(), emptyMap())

        assertEquals(listOf(1, 2, 3), order)
    }

    @Test
    fun `unregistered event type is silently ignored`() {
        val general = General(id = 1, nationId = 1, cityId = 1, leadership = 50, strength = 50, intel = 50, injury = 0, experience = 0.0, dedication = 0.0, officerLevel = 0, gold = 0, rice = 0)
        var called = false
        StaticEventHandler.register("eventA") { _, _, _, _ -> called = true }

        StaticEventHandler.handleEvent(general, null, "eventB", emptyMap(), emptyMap())

        assertTrue(!called, "handler for eventA should not fire for eventB")
    }
}
