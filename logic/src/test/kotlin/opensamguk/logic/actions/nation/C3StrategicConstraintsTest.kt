package opensamguk.logic.actions.nation

import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.ConstraintMode
import opensamguk.logic.constraints.ConstraintResult
import opensamguk.logic.constraints.RequirementKey
import opensamguk.logic.constraints.StateView
import opensamguk.logic.domain.Nation
import kotlin.test.Test
import kotlin.test.assertEquals

class C3StrategicConstraintsTest {

    @Test
    fun `strategic command is available only when PHP limit is at or below zero`() {
        val constraint = availableStrategicCommand()
        val context = ConstraintContext(actorId = 10, nationId = 1, mode = ConstraintMode.FULL)

        assertEquals(
            ConstraintResult.Allow,
            constraint.test(context, View(RequirementKey.Nation(1) to nation(limit = 0))),
        )
        assertEquals(
            ConstraintResult.Deny("전략기한이 남았습니다."),
            constraint.test(context, View(RequirementKey.Nation(1) to nation(limit = 1))),
        )
        assertEquals(
            ConstraintResult.Deny("전략기한이 남았습니다."),
            constraint.test(context, View()),
        )
    }

    @Test
    fun `diplomacy status constraint denies when staging data is absent`() {
        val constraint = disallowDiplomacyStatus(mapOf(0 to "평시에만 가능합니다."))

        assertEquals(
            ConstraintResult.Deny("평시에만 가능합니다."),
            constraint.test(ConstraintContext(actorId = 10, mode = ConstraintMode.FULL), View()),
        )
        assertEquals(
            ConstraintResult.Allow,
            constraint.test(
                ConstraintContext(
                    actorId = 10,
                    env = mapOf("__disallowDiplomacyHit" to false),
                    mode = ConstraintMode.FULL,
                ),
                View(),
            ),
        )
    }

    private fun nation(limit: Int): Nation = Nation(
        id = 1,
        level = 1,
        capitalCityId = 1,
        name = "촉",
        color = "#0f0",
        meta = mapOf("strategic_cmd_limit" to limit),
    )

    private class View(vararg entries: Pair<RequirementKey, Any?>) : StateView {
        private val values = entries.toMap()

        override fun has(req: RequirementKey): Boolean = values.containsKey(req)

        override fun get(req: RequirementKey): Any? = values[req]
    }
}
