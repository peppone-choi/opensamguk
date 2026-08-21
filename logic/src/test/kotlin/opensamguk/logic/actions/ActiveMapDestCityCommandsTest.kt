package opensamguk.logic.actions

import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.ConstraintMode
import opensamguk.logic.constraints.ConstraintResult
import opensamguk.logic.constraints.RequirementKey
import opensamguk.logic.constraints.StateView
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.stats.GeneralActionPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ActiveMapDestCityCommandsTest {
    private val registry = CommandRegistry(GeneralActionPipeline())

    private val commands = linkedMapOf(
        "che_수몰" to mapOf<String, Any?>("destCityID" to 421),
        "che_백성동원" to mapOf<String, Any?>("destCityID" to 421),
        "che_선동" to mapOf<String, Any?>("destCityID" to 421),
        "che_탈취" to mapOf<String, Any?>("destCityID" to 421),
        "che_첩보" to mapOf<String, Any?>("destCityID" to 421),
        "che_화계" to mapOf<String, Any?>("destCityID" to 421),
        "che_파괴" to mapOf<String, Any?>("destCityID" to 421),
        "che_초토화" to mapOf<String, Any?>("destCityID" to 421),
        "cr_인구이동" to mapOf<String, Any?>("destCityID" to 421, "amount" to 100),
        "che_허보" to mapOf<String, Any?>("destCityID" to 421),
    )

    @Test
    fun `destination commands parse Han-only ids and validate them against the active map`() {
        for ((code, rawArgs) in commands) {
            val definition = registry.resolve(code)
            val parsed = definition.parseArgs(rawArgs)
            assertEquals(421, parsed["destCityID"], "$code must structurally parse a Han-only city")

            val han = context(parsed, "han", 421)
            val constraint = assertNotNull(
                definition.buildConstraints(han).singleOrNull { it.name == "ActiveMapDestCity" },
                "$code must validate its destination through the active map",
            )
            assertEquals(ConstraintResult.Allow, constraint.test(han, cityView(421)), "$code Han city")

            val che = context(parsed, "che", 421)
            assertEquals("ActiveMapDestCity", definition.buildConstraints(che).first().name, "$code destination priority")
            assertEquals(
                ConstraintResult.Deny("Invalid destination city.", "ActiveMapDestCity"),
                constraint.test(che, cityView(421)),
                "$code must reject a Han-only city on Che",
            )
        }
    }

    @Test
    fun `destination commands retain Che city parsing and validation`() {
        for ((code, rawArgs) in commands) {
            val cheArgs = rawArgs + ("destCityID" to 1)
            val definition = registry.resolve(code)
            val parsed = definition.parseArgs(cheArgs)
            assertEquals(1, parsed["destCityID"], "$code must retain Che parsing")
            val ctx = context(parsed, "che", 1)
            val constraint = assertNotNull(definition.buildConstraints(ctx).singleOrNull { it.name == "ActiveMapDestCity" })
            assertEquals(ConstraintResult.Allow, constraint.test(ctx, cityView(1)), "$code Che city")
        }
    }

    @Test
    fun `population movement uses the active Han edge instead of the Che graph`() {
        val definition = registry.resolve("cr_인구이동")
        val args = definition.parseArgs(mapOf("destCityID" to 421, "amount" to 100))
        val han = context(args, "han", 421)
        val che = context(args, "che", 421)
        val hanNearCity = assertNotNull(definition.buildConstraints(han).singleOrNull { it.name == "NearCity" })
        val cheNearCity = assertNotNull(definition.buildConstraints(che).singleOrNull { it.name == "NearCity" })
        val view = edgeView()

        assertEquals(ConstraintResult.Allow, hanNearCity.test(han, view))
        assertEquals(ConstraintResult.Deny("인접도시가 아닙니다."), cheNearCity.test(che, view))
    }

    private fun context(args: Map<String, Any?>, mapName: String, destCityId: Int) = ConstraintContext(
        actorId = 1,
        cityId = 3,
        nationId = 1,
        destCityId = destCityId,
        args = args,
        env = mapOf("mapName" to mapName, "develCost" to 100),
        mode = ConstraintMode.FULL,
    )

    private fun cityView(cityId: Int) = object : StateView {
        override fun has(req: RequirementKey): Boolean = req == RequirementKey.DestCity(cityId)
        override fun get(req: RequirementKey): Any? = null
    }

    private fun edgeView() = object : StateView {
        private val general = General(
            id = 1, nationId = 1, cityId = 3, leadership = 70, strength = 70, intel = 70,
            injury = 0, experience = 0.0, dedication = 0.0, officerLevel = 12, gold = 1000, rice = 1000,
        )
        private val destination = City(
            id = 421, nationId = 1, level = 1, commerce = 0, commerceMax = 1,
            agriculture = 0, agricultureMax = 1, supplyState = 1, frontState = 0, trust = 100.0,
        )

        override fun has(req: RequirementKey): Boolean = get(req) != null
        override fun get(req: RequirementKey): Any? = when (req) {
            RequirementKey.General(1) -> general
            RequirementKey.DestCity(421) -> destination
            else -> null
        }
    }
}
