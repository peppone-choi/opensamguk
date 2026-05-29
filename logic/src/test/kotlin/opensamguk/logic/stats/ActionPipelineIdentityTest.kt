package opensamguk.logic.stats

import opensamguk.logic.domain.General
import kotlin.test.Test
import kotlin.test.assertEquals

class ActionPipelineIdentityTest {

    private val general = General(
        id = 1,
        nationId = 1,
        cityId = 1,
        leadership = 70,
        strength = 60,
        intel = 80,
        injury = 0,
        experience = 0.0,
        dedication = 0.0,
        officerLevel = 0,
        gold = 1000,
        rice = 1000,
    )

    @Test
    fun `empty pipeline is identity on onCalcStat`() {
        val pipeline = GeneralActionPipeline()
        assertEquals(80.0, pipeline.onCalcStat(general, "intelligence", 80.0))
    }

    @Test
    fun `empty pipeline is identity on onCalcDomestic`() {
        val pipeline = GeneralActionPipeline()
        assertEquals(16.0, pipeline.onCalcDomestic(general, "상업", "cost", 16.0))
    }

    @Test
    fun `one-module pipeline folds onCalcDomestic correctly`() {
        val plusFiveCost = object : GeneralActionModule {
            override fun onCalcDomestic(general: General, actionKey: String, varType: String, value: Double): Double =
                if (varType == "cost") value + 5.0 else value
        }
        val pipeline = GeneralActionPipeline(listOf(plusFiveCost))
        assertEquals(21.0, pipeline.onCalcDomestic(general, "상업", "cost", 16.0))
        // non-'cost' varType is left untouched by the stub module
        assertEquals(16.0, pipeline.onCalcDomestic(general, "상업", "score", 16.0))
        // onCalcStat unaffected (module overrides only onCalcDomestic)
        assertEquals(80.0, pipeline.onCalcStat(general, "intelligence", 80.0))
    }
}
