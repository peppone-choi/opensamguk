package opensamguk.common.constants

import kotlin.test.Test
import kotlin.test.assertEquals

class EffectiveGameConstTest {

    @Test fun overrideReplacesScalar() {
        val eff = EffectiveGameConst.of(mapOf("maxTurn" to 99))
        assertEquals(99, eff.getInt("maxTurn"))
    }

    @Test fun unspecifiedFallsThroughToBase() {
        val eff = EffectiveGameConst.of(mapOf("maxTurn" to 99))
        // not overridden -> base GameConst value
        assertEquals(GameConst.develrate, eff.getInt("develrate"))
        assertEquals(GameConst.upgradeLimit, eff.getInt("upgradeLimit"))
        assertEquals(GameConst.maxLevel, eff.getInt("maxLevel"))
        assertEquals(GameConst.sabotageDefaultProb, eff.getDouble("sabotageDefaultProb"), 0.0)
    }

    @Test fun derivedKillturnFlooredForNpcmode() {
        // turnterm 120 -> base 40; npcmode 1 -> intdiv(40,3) = 13
        assertEquals(40, EffectiveGameConst.killturn(turnterm = 120, npcmode = 0))
        assertEquals(13, EffectiveGameConst.killturn(turnterm = 120, npcmode = 1))
    }

    @Test fun derivedDevelcostFormula() {
        // (year - startYear + 10) * 2
        assertEquals(20, EffectiveGameConst.develcost(year = 180, startYear = 180))
        assertEquals(40, EffectiveGameConst.develcost(year = 190, startYear = 180))
    }
}
