package opensamguk.logic.war.trigger

import opensamguk.common.rng.RandUtil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Port target = PHP `sammo/ObjectTrigger.php:5-36`. Pins the priority band constants
 * (lower number = fires FIRST) and the base `getUniqueID` format `{priority}_{FQN}_{objID}`.
 */
class ObjectTriggerTest {

    // A stub trigger that does nothing (this layer carries no draw behavior).
    private class StubTriggerA(
        override val priority: Int,
        override val objectId: String,
    ) : ObjectTrigger() {
        override fun action(rng: RandUtil, env: TriggerEnv, arg: Any?): TriggerEnv = env
    }

    private class StubTriggerB(
        override val priority: Int,
        override val objectId: String,
    ) : ObjectTrigger() {
        override fun action(rng: RandUtil, env: TriggerEnv, arg: Any?): TriggerEnv = env
    }

    @Test
    fun `priority band constants match PHP ObjectTrigger exactly`() {
        assertEquals(0, ObjectTrigger.PRIORITY_MIN)
        assertEquals(10000, ObjectTrigger.PRIORITY_BEGIN)
        assertEquals(20000, ObjectTrigger.PRIORITY_PRE)
        assertEquals(30000, ObjectTrigger.PRIORITY_BODY)
        assertEquals(40000, ObjectTrigger.PRIORITY_POST)
        assertEquals(50000, ObjectTrigger.PRIORITY_FINAL)
    }

    @Test
    fun `getUniqueID format is priority underscore FQN underscore objID`() {
        val trigger = StubTriggerA(priority = 20120, objectId = "7")
        // FQN token is the fully-qualified class name (stable across runs, unlike spl_object_id).
        assertEquals("20120_${StubTriggerA::class.qualifiedName}_7", trigger.getUniqueID())
    }

    @Test
    fun `empty objectId renders as trailing empty segment like PHP null object`() {
        val trigger = StubTriggerA(priority = 0, objectId = "")
        assertEquals("0_${StubTriggerA::class.qualifiedName}_", trigger.getUniqueID())
    }

    @Test
    fun `distinct FQNs at the same priority produce distinct ids`() {
        val a = StubTriggerA(priority = 40300, objectId = "1")
        val b = StubTriggerB(priority = 40300, objectId = "1")
        assertNotEquals(a.getUniqueID(), b.getUniqueID())
    }

    @Test
    fun `distinct objIds for the same class produce distinct ids`() {
        val a = StubTriggerA(priority = 40300, objectId = "1")
        val b = StubTriggerA(priority = 40300, objectId = "2")
        assertNotEquals(a.getUniqueID(), b.getUniqueID())
    }
}
