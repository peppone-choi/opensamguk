package opensamguk.logic.war.trigger

import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.LiteHashDrbg
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Port target = PHP `sammo/TriggerCaller.php` (constructor :13-51, merge :80-119, fire :121-129 —
 * NOT append :53-78). The `fire()` iteration order IS the battle RNG draw order, so this test PINS:
 *   - priority ASCENDING across buckets, INSERTION order within a bucket;
 *   - dedup collapses identical uniqueIDs (later overwrites earlier);
 *   - merge interleaves by priority with LHS-then-RHS per shared bucket;
 *   - stopNextAction short-circuits remaining triggers (iterated but draw NOTHING).
 */
class TriggerCallerOrderTest {

    // A concrete caller with a permissive gate (FT3's WarUnitTriggerCaller adds the instanceof gate).
    private class TestCaller(vararg triggers: Trigger) : TriggerCaller(*triggers) {
        override fun checkValidTrigger(trigger: Trigger): Boolean = true
    }

    // A recording trigger: appends its label to env["order"] when fired (so we read the draw order out).
    private class RecordingTrigger(
        override val priority: Int,
        override val objectId: String,
        private val label: String,
        private val stopAfter: Boolean = false,
    ) : ObjectTrigger() {
        override fun action(rng: RandUtil, env: TriggerEnv, arg: Any?): TriggerEnv {
            // stopNextAction is honored by the trigger itself: if set, no-op (matches BaseWarUnitTrigger).
            if (env["stopNextAction"] == true) return env
            @Suppress("UNCHECKED_CAST")
            val order = env.getOrPut("order") { mutableListOf<String>() } as MutableList<String>
            order.add(label)
            if (stopAfter) env["stopNextAction"] = true
            return env
        }
    }

    private fun rng() = RandUtil(LiteHashDrbg("00"))
    private fun env() = TriggerEnv()

    @Suppress("UNCHECKED_CAST")
    private fun order(env: TriggerEnv): List<String> =
        (env["order"] as? List<String>) ?: emptyList()

    @Test
    fun `fire iterates priority-ascending across buckets`() {
        // Insert out of priority order; fire must emit ascending.
        val caller = TestCaller(
            RecordingTrigger(ObjectTrigger.PRIORITY_POST, "1", "post"),
            RecordingTrigger(ObjectTrigger.PRIORITY_PRE, "1", "pre"),
            RecordingTrigger(ObjectTrigger.PRIORITY_BODY, "1", "body"),
        )
        val out = caller.fire(rng(), env(), null)
        assertEquals(listOf("pre", "body", "post"), order(out))
    }

    @Test
    fun `same-priority triggers fire in constructor ARG order (계략발동 before 계략실패 at 40300)`() {
        val caller = TestCaller(
            RecordingTrigger(40300, "u1", "계략발동"),
            RecordingTrigger(40300, "u2", "계략실패"),
        )
        val out = caller.fire(rng(), env(), null)
        assertEquals(listOf("계략발동", "계략실패"), order(out))
    }

    @Test
    fun `dedup collapses identical priority-class-objId uniqueIDs (later overwrites)`() {
        // Two RecordingTriggers with the SAME (priority, class, objId) collapse to one bucket entry.
        val first = RecordingTrigger(20300, "same", "first")
        val second = RecordingTrigger(20300, "same", "second")
        assertEquals(first.getUniqueID(), second.getUniqueID())
        val caller = TestCaller(first, second)
        val out = caller.fire(rng(), env(), null)
        // Only the later one survives (PHP `[$uniqueID=>$trigger]` overwrite).
        assertEquals(listOf("second"), order(out))
    }

    @Test
    fun `merge interleaves by priority with LHS-then-RHS per shared bucket`() {
        val lhs = TestCaller(
            RecordingTrigger(20200, "L1", "L-pre200"),   // shared bucket 20200
            RecordingTrigger(40300, "L2", "L-post300"),  // LHS-only bucket
        )
        val rhs = TestCaller(
            RecordingTrigger(20200, "R1", "R-pre200"),   // shared bucket 20200
            RecordingTrigger(30400, "R2", "R-body400"),  // RHS-only bucket between the two LHS buckets
        )
        lhs.merge(rhs)
        val out = lhs.fire(rng(), env(), null)
        // 20200 bucket: LHS first then RHS; then RHS-only 30400; then LHS-only 40300.
        assertEquals(listOf("L-pre200", "R-pre200", "R-body400", "L-post300"), order(out))
    }

    @Test
    fun `merge of null is a no-op returning this`() {
        val lhs = TestCaller(RecordingTrigger(10000, "L", "begin"))
        val same = lhs.merge(null)
        assertTrue(same === lhs)
        val out = lhs.fire(rng(), env(), null)
        assertEquals(listOf("begin"), order(out))
    }

    @Test
    fun `stopNextAction short-circuits remaining triggers - they iterate but draw nothing`() {
        val caller = TestCaller(
            RecordingTrigger(20100, "a", "a", stopAfter = true),
            RecordingTrigger(20200, "b", "b-should-not-record"),
            RecordingTrigger(40300, "c", "c-should-not-record"),
        )
        val out = caller.fire(rng(), env(), null)
        // Only "a" recorded; b and c iterated but no-op'd on the stopNextAction flag.
        assertEquals(listOf("a"), order(out))
        assertEquals(true, out["stopNextAction"])
    }

    @Test
    fun `isEmpty true for no-arg caller`() {
        assertTrue(TestCaller().isEmpty())
        assertTrue(!TestCaller(RecordingTrigger(0, "x", "x")).isEmpty())
    }

    // A rejecting caller (gate always false) — used to prove the constructor's checkValidTrigger gate.
    private class RejectingCaller(vararg triggers: Trigger) : TriggerCaller(*triggers) {
        override fun checkValidTrigger(trigger: Trigger): Boolean = false
    }

    @Test
    fun `checkValidTrigger gate throws on invalid trigger in constructor`() {
        // PHP `TriggerCaller::__construct` throws InvalidArgumentException on a bad trigger (:33-35).
        assertFailsWith<IllegalArgumentException> {
            RejectingCaller(RecordingTrigger(0, "x", "x"))
        }
    }
}
