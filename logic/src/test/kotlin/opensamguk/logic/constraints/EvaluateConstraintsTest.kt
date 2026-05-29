package opensamguk.logic.constraints

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EvaluateConstraintsTest {

    /** Hand-rolled stub: fixed requirements + a fixed test result, with a run counter. */
    private class StubConstraint(
        override val name: String,
        private val requirements: List<RequirementKey> = emptyList(),
        private val result: ConstraintResult = ConstraintResult.Allow,
        private val counter: IntArray? = null,
    ) : Constraint {
        override fun requires(ctx: ConstraintContext): List<RequirementKey> = requirements
        override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
            counter?.let { it[0] = it[0] + 1 }
            return result
        }
    }

    /** StateView whose membership is driven by an explicit present-set. */
    private class SetStateView(private val present: Set<RequirementKey>) : StateView {
        override fun has(req: RequirementKey): Boolean = req in present
        override fun get(req: RequirementKey): Any? = null
    }

    private fun ctx(mode: ConstraintMode) = ConstraintContext(actorId = 1, mode = mode)

    @Test
    fun `all-allow yields Allow`() {
        val constraints = listOf(
            StubConstraint("first", result = ConstraintResult.Allow),
            StubConstraint("second", result = ConstraintResult.Allow),
        )
        val result = evaluateConstraints(constraints, ctx(ConstraintMode.FULL), SetStateView(emptySet()))
        assertEquals(ConstraintResult.Allow, result)
    }

    @Test
    fun `second denies short-circuits with its name and third never runs`() {
        val thirdRuns = intArrayOf(0)
        val constraints = listOf(
            StubConstraint("first", result = ConstraintResult.Allow),
            StubConstraint("second", result = ConstraintResult.Deny(reason = "nope")),
            StubConstraint("third", result = ConstraintResult.Allow, counter = thirdRuns),
        )
        val result = evaluateConstraints(constraints, ctx(ConstraintMode.FULL), SetStateView(emptySet()))
        val deny = result as ConstraintResult.Deny
        assertEquals("nope", deny.reason)
        assertEquals("second", deny.constraintName)
        assertEquals(0, thirdRuns[0])
    }

    @Test
    fun `precheck with missing requirement yields Unknown(missing)`() {
        val missing = RequirementKey.General(42)
        val constraints = listOf(
            StubConstraint("needsGeneral", requirements = listOf(missing), result = ConstraintResult.Allow),
        )
        val result = evaluateConstraints(constraints, ctx(ConstraintMode.PRECHECK), SetStateView(emptySet()))
        val unknown = result as ConstraintResult.Unknown
        assertEquals(listOf(missing), unknown.missing)
    }

    @Test
    fun `full mode with missing requirement still runs test and returns its result`() {
        val testRuns = intArrayOf(0)
        val missing = RequirementKey.General(42)
        val constraints = listOf(
            StubConstraint(
                "needsGeneral",
                requirements = listOf(missing),
                result = ConstraintResult.Deny(reason = "denied-in-full"),
                counter = testRuns,
            ),
        )
        val result = evaluateConstraints(constraints, ctx(ConstraintMode.FULL), SetStateView(emptySet()))
        assertTrue(testRuns[0] == 1, "test() must run in FULL mode even when a requirement is absent")
        val deny = result as ConstraintResult.Deny
        assertEquals("denied-in-full", deny.reason)
        assertEquals("needsGeneral", deny.constraintName)
    }
}
