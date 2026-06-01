package opensamguk.logic.inheritance

import opensamguk.common.constants.GameConst
import opensamguk.common.constants.GameUnitConst
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InheritancePointMathTest {
    private fun proxy(
        owner: Int = 1,
        npc: Int = 0,
        vars: Map<String, Int> = emptyMap(),
        rankVars: Map<String, Int> = emptyMap(),
        auxVars: Map<String, Any?> = emptyMap(),
    ) = object : GeneralProxy {
        override val id = 1
        override val owner = owner
        override val npc = npc
        override fun getVar(key: String): Int = vars[key] ?: 0
        override fun getRankVar(key: String): Int = rankVars[key] ?: 0
        override fun getAuxVar(key: String): Any? = auxVars[key]
    }

    @Test
    fun `owner zero returns zero`() {
        assertEquals(0.0, InheritancePointMath.compute(proxy(owner = 0), InheritanceKey.dex, false, true))
    }

    @Test
    fun `npc greater or equal 2 returns zero`() {
        assertEquals(0.0, InheritancePointMath.compute(proxy(npc = 2), InheritanceKey.dex, false, true))
        assertEquals(0.0, InheritancePointMath.compute(proxy(npc = 3), InheritanceKey.dex, false, true))
    }

    @Test
    fun `dex extractor sums all arm types and applies overflow slash 3`() {
        val dexVars = GameUnitConst.allType().keys.associate { "dex$it" to 500_000 }.toMutableMap()
        dexVars["dex${GameUnitConst.T_FOOTMAN}"] = 2_000_000
        val p = proxy(vars = dexVars)
        val result = InheritancePointMath.compute(p, InheritanceKey.dex, false, true)!!
        // footman: (2_000_000 - 1_000_000) / 3 + 1_000_000 = 333_333 + 1_000_000 = 1_333_333
        // others: 4 * 500_000 = 2_000_000
        // total = 3_333_333 * 0.001 = 3333.333
        assertEquals(3333.333, result, 0.001)
    }

    @Test
    fun `dex extractor with all below limit sums directly`() {
        val dexVars = GameUnitConst.allType().keys.associate { "dex$it" to 100_000 }
        val p = proxy(vars = dexVars)
        val result = InheritancePointMath.compute(p, InheritanceKey.dex, false, true)!!
        assertEquals(500.0, result, 0.001)
    }

    @Test
    fun `betting extractor uses betWin times coeff times winRate squared`() {
        val p = proxy(rankVars = mapOf("betwin" to 5, "betwingold" to 2000, "betgold" to 1000))
        val result = InheritancePointMath.compute(p, InheritanceKey.betting, false, true)!!
        // betWinRate = 2000 / max(1000, 1000) = 2.0
        // 5 * 10.0 * (2.0 * 2.0) = 200.0
        assertEquals(200.0, result, 0.001)
    }

    @Test
    fun `max_belong extractor uses max of belong and auxMaxBelong`() {
        val p = proxy(vars = mapOf("belong" to 3), auxVars = mapOf("max_belong" to 5))
        val result = InheritancePointMath.compute(p, InheritanceKey.max_belong, false, true)!!
        assertEquals(50.0, result, 0.001)
    }

    @Test
    fun `isunited gate returns zero for non previous keys`() {
        val p = proxy(rankVars = mapOf("warnum" to 10))
        val result = InheritancePointMath.compute(p, InheritanceKey.combat, isUnited = true, forceCalc = true)!!
        assertEquals(0.0, result, 0.001)
    }

    @Test
    fun `isunited gate returns null for store type true keys when not forceCalc`() {
        val p = proxy()
        assertNull(InheritancePointMath.compute(p, InheritanceKey.previous, isUnited = true, forceCalc = false))
    }

    @Test
    fun `rank array storeType multiplies rankVar by coeff`() {
        val p = proxy(rankVars = mapOf("warnum" to 7))
        val result = InheritancePointMath.compute(p, InheritanceKey.combat, false, true)!!
        assertEquals(35.0, result, 0.001)
    }
}
