package opensamguk.logic.stats

import opensamguk.logic.domain.General
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Port-faithful tests for the pair-typed `onCalcStatRange` hook (plan AREA F4 task FW1, decision #1).
 *
 * PHP grand truth: `criticalDamageRange` is a `[min,max]` PAIR threaded through `onCalcStat`
 * (`WarUnit.php:443`, `che_필살` rewrites it). The Kotlin port does NOT widen the scalar
 * `onCalcStat`/`onCalcOpposeStat` (1235 GREEN tests keep compiling); instead a parallel
 * pair hook `onCalcStatRange(general, statName, value: Pair<Double,Double>, aux): Pair<Double,Double>`
 * (identity default) folds over the same module list.
 */
class OnCalcStatRangeFoldTest {

    private val general = General(
        id = 1, nationId = 1, cityId = 1,
        leadership = 70, strength = 60, intel = 80, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = 0,
        gold = 1000, rice = 1000,
    )

    @Test
    fun `empty pipeline is identity on onCalcStatRange`() {
        val pipeline = GeneralActionPipeline()
        assertEquals(
            1.3 to 2.0,
            pipeline.onCalcStatRange(general, "criticalDamageRange", 1.3 to 2.0),
        )
    }

    @Test
    fun `module default onCalcStatRange is identity`() {
        // a module overriding only the scalar hook leaves the pair untouched
        val scalarOnly = object : GeneralActionModule {
            override fun onCalcStat(general: General, statName: String, value: Double, aux: Map<String, Any?>): Double = value + 1.0
        }
        val pipeline = GeneralActionPipeline(listOf(scalarOnly))
        assertEquals(1.3 to 2.0, pipeline.onCalcStatRange(general, "criticalDamageRange", 1.3 to 2.0))
    }

    @Test
    fun `che_필살 style rewrite folds the pair`() {
        // che_필살: range -> [(min+max)/2, max]  => [1.3,2.0] -> [1.65, 2.0]
        val pilsal = object : GeneralActionModule {
            override fun onCalcStatRange(
                general: General,
                statName: String,
                value: Pair<Double, Double>,
                aux: Map<String, Any?>,
            ): Pair<Double, Double> =
                if (statName == "criticalDamageRange") ((value.first + value.second) / 2.0) to value.second else value
        }
        val pipeline = GeneralActionPipeline(listOf(pilsal))
        assertEquals(1.65 to 2.0, pipeline.onCalcStatRange(general, "criticalDamageRange", 1.3 to 2.0))
        // a non-matching statName is left untouched
        assertEquals(0.5 to 1.0, pipeline.onCalcStatRange(general, "other", 0.5 to 1.0))
    }

    @Test
    fun `range fold is left-to-right across modules`() {
        // module A: widen max by +0.5 ; module B: floor min at 1.5 — order matters (left fold)
        val widenMax = object : GeneralActionModule {
            override fun onCalcStatRange(general: General, statName: String, value: Pair<Double, Double>, aux: Map<String, Any?>): Pair<Double, Double> =
                value.first to (value.second + 0.5)
        }
        val floorMin = object : GeneralActionModule {
            override fun onCalcStatRange(general: General, statName: String, value: Pair<Double, Double>, aux: Map<String, Any?>): Pair<Double, Double> =
                maxOf(value.first, 1.5) to value.second
        }
        val pipeline = GeneralActionPipeline(listOf(widenMax, floorMin))
        assertEquals(1.5 to 2.5, pipeline.onCalcStatRange(general, "criticalDamageRange", 1.3 to 2.0))
    }

    @Test
    fun `existing scalar onCalcStat fold is unchanged`() {
        // round-trip a P2 assertion: scalar hook still folds, pair hook does not interfere
        val plusFive = object : GeneralActionModule {
            override fun onCalcStat(general: General, statName: String, value: Double, aux: Map<String, Any?>): Double = value + 5.0
        }
        val pipeline = GeneralActionPipeline(listOf(plusFive))
        assertEquals(85.0, pipeline.onCalcStat(general, "warCriticalRatio", 80.0))
        // and the scalar oppose fold likewise untouched
        assertEquals(80.0, pipeline.onCalcOpposeStat(general, "warCriticalRatio", 80.0))
    }
}
