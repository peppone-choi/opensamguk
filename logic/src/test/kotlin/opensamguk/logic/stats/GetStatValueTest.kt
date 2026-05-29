package opensamguk.logic.stats

import opensamguk.logic.domain.General
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Port-faithful tests for General.php::getStatValue (lines 359-403).
 * getIntel(true,true,true,false) = with injury, with action obj, with stat-adjust, NO floor (float).
 * P1 pipeline is empty (identity), so onCalcStat is a no-op.
 */
class GetStatValueTest {

    private fun general(intel: Int, strength: Int, injury: Int, leadership: Int = 50) = General(
        id = 1,
        nationId = 1,
        cityId = 1,
        leadership = leadership,
        strength = strength,
        intel = intel,
        injury = injury,
        experience = 0.0,
        dedication = 0.0,
        officerLevel = 0,
        gold = 1000,
        rice = 1000,
    )

    private val pipeline = GeneralActionPipeline()

    @Test
    fun `intel 90 strength 40 no injury cross-stat adds round(strength over 4)`() {
        // 90 + round(40/4) = 90 + 10 = 100
        val g = general(intel = 90, strength = 40, injury = 0)
        val v = getStatValue(g, "intelligence", pipeline, withInjury = true, withIActionObj = true, withStatAdjust = true, useFloor = false)
        assertEquals(100.0, v)
    }

    @Test
    fun `intel 90 strength 40 with injury 20 applies injury to both base and cross`() {
        // (90*0.8) + round((40*0.8)/4) = 72 + round(8) = 72 + 8 = 80
        val g = general(intel = 90, strength = 40, injury = 20)
        val v = getStatValue(g, "intelligence", pipeline, withInjury = true, withIActionObj = true, withStatAdjust = true, useFloor = false)
        assertEquals(80.0, v)
    }

    @Test
    fun `clamp at maxLevel when base plus cross exceeds maxLevel`() {
        // intel=250, strength=80: 250 + round(80/4)=250+20=270 -> clamp 255
        val g = general(intel = 250, strength = 80, injury = 0)
        val v = getStatValue(g, "intelligence", pipeline, withInjury = true, withIActionObj = true, withStatAdjust = true, useFloor = false)
        assertEquals(255.0, v)
    }

    @Test
    fun `floored variant truncates toward zero`() {
        // strength=45, intel=90, injury=10: base = 45*0.9 = 40.5; cross = round((90*0.9)/4)=round(20.25)=20
        // 40.5 + 20 = 60.5 -> float; floored = truncate(60.5) = 60
        val g = general(intel = 90, strength = 45, injury = 10)
        val floatVal = getStatValue(g, "strength", pipeline, useFloor = false)
        assertEquals(60.5, floatVal)
        val floored = getStatValue(g, "strength", pipeline, useFloor = true)
        assertEquals(60.0, floored)
    }

    @Test
    fun `defaults are with-injury with-action with-stat-adjust floored`() {
        // intel=90, strength=40, injury=0: 90 + 10 = 100 -> truncate 100
        val g = general(intel = 90, strength = 40, injury = 0)
        assertEquals(100.0, getStatValue(g, "intelligence", pipeline))
    }
}
