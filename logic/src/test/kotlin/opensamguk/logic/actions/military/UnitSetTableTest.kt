package opensamguk.logic.actions.military

import opensamguk.common.constants.GameUnitConst
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Port-faithful test for the declarative unit-set stat table + getTechCost curve.
 * Values transcribed from GameUnitConstBase.php getBuildData() (id/armType/cost/rice slots) and
 * the curves func_converter.php:676-705 + GameUnitDetail.php:120-128.
 */
class UnitSetTableTest {

    @Test
    fun `default crewtype is 보병 1100 footman with cost 9 rice 9`() {
        val u = UnitSetTable.byId(GameUnitConst.DEFAULT_CREWTYPE)!!
        assertEquals(1100, u.id)
        assertEquals(GameUnitConst.T_FOOTMAN, u.armType)
        assertEquals("보병", u.name)
        assertEquals(9, u.cost)
        assertEquals(9, u.rice)
    }

    @Test
    fun `castle 1000 is a castle armType with cost 99 rice 9`() {
        val u = UnitSetTable.byId(GameUnitConst.CREWTYPE_CASTLE)!!
        assertEquals(GameUnitConst.T_CASTLE, u.armType)
        assertEquals(99, u.cost)
        assertEquals(9, u.rice)
    }

    @Test
    fun `several unit-set cost rice armType values match GameUnitConstBase`() {
        // 맹수병 (1306): cavalry, the priciest unit cost 16 rice 16
        UnitSetTable.byId(1306)!!.let {
            assertEquals(GameUnitConst.T_CAVALRY, it.armType); assertEquals(16, it.cost); assertEquals(16, it.rice)
        }
        // 충차 (1501): siege cost 18 rice 5
        UnitSetTable.byId(1501)!!.let {
            assertEquals(GameUnitConst.T_SIEGE, it.armType); assertEquals(18, it.cost); assertEquals(5, it.rice)
        }
        // 궁병 (1200): archer cost 10 rice 10
        UnitSetTable.byId(1200)!!.let {
            assertEquals(GameUnitConst.T_ARCHER, it.armType); assertEquals(10, it.cost); assertEquals(10, it.rice)
        }
        // 남귀병 (1405): wizard, the cheapest cost 8 rice 8
        UnitSetTable.byId(1405)!!.let {
            assertEquals(GameUnitConst.T_WIZARD, it.armType); assertEquals(8, it.cost); assertEquals(8, it.rice)
        }
    }

    @Test
    fun `table has 34 units and an absent id returns null`() {
        assertEquals(34, UnitSetTable.all().size)
        assertNull(UnitSetTable.byId(9999))
    }

    @Test
    fun `unsupported unit set never aliases the che table`() {
        assertNotNull(UnitSetTable.byId(unitSet = "che", id = 1100))
        assertNull(UnitSetTable.byId(unitSet = "not-ported", id = 1100))
    }

    @Test
    fun `a present blank unit set is unsupported while a missing unit set defaults to che`() {
        assertEquals(UnitSetTable.CHE_UNIT_SET, UnitSetTable.activeUnitSet(emptyMap(), emptyMap()))

        val blank = UnitSetTable.activeUnitSet(config = linkedMapOf("unitSet" to "   "), meta = emptyMap())
        assertEquals("   ", blank)
        assertNull(UnitSetTable.byId(blank, 1100))
        assertTrue(UnitSetTable.all(blank).isEmpty())
    }

    @Test
    fun `che unit rows follow the canonical GameUnitConst catalog`() {
        val expected = GameUnitConst.all().values.map { unit ->
            listOf(unit.id, unit.armType, unit.name, unit.cost, unit.rice)
        }
        val actual = UnitSetTable.all().map { unit ->
            listOf(unit.id, unit.armType, unit.name, unit.cost, unit.rice)
        }

        assertEquals(expected, actual)
    }

    @Test
    fun `flattened active unit set overrides a stale nested fallback`() {
        val active = UnitSetTable.activeUnitSet(
            config = linkedMapOf(
                "map" to linkedMapOf("unitSet" to "che"),
                "unitSet" to "not-ported",
            ),
            meta = emptyMap(),
        )

        assertEquals("not-ported", active)
        assertNull(UnitSetTable.byId(active, 1100))
    }

    @Test
    fun `byId below 1000 throws (PHP InvalidArgumentException)`() {
        assertFailsWith<IllegalArgumentException> { UnitSetTable.byId(999) }
    }

    @Test
    fun `getTechLevel floors tech div 1000 clamped to maxTechLevel`() {
        assertEquals(0, UnitSetTable.getTechLevel(0))
        assertEquals(0, UnitSetTable.getTechLevel(999))
        assertEquals(1, UnitSetTable.getTechLevel(1000))
        assertEquals(3, UnitSetTable.getTechLevel(3500))
        assertEquals(12, UnitSetTable.getTechLevel(12_000))
        assertEquals(12, UnitSetTable.getTechLevel(50_000))   // clamped at maxTechLevel 12
    }

    @Test
    fun `getTechCost is 1 plus techLevel times 0_15`() {
        assertEquals(1.0, UnitSetTable.getTechCost(0))
        assertEquals(1.15, UnitSetTable.getTechCost(1000), 1e-9)
        assertEquals(1.45, UnitSetTable.getTechCost(3000), 1e-9)
    }

    @Test
    fun `costWithTech and riceWithTech apply the tech curve and crew scaling`() {
        val footman = UnitSetTable.byId(1100)!!   // cost 9 rice 9
        // tech 0, crew 100 → base
        assertEquals(9.0, footman.costWithTech(0, 100), 1e-9)
        assertEquals(9.0, footman.riceWithTech(0, 100), 1e-9)
        // tech 3000 (level 3 → 1.45), crew 500 → 9 * 1.45 * 500/100 = 65.25
        assertEquals(9 * 1.45 * 5, footman.costWithTech(3000, 500), 1e-9)
        assertEquals(9 * 1.45 * 5, footman.riceWithTech(3000, 500), 1e-9)
    }
}
