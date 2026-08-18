package opensamguk.logic.actions.military

import opensamguk.common.constants.GameUnitConst
import opensamguk.common.constants.UnitCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** han 세트가 실제로 물렸는지 — 이전엔 `isSupported` 가 che 하나만 참이었다. */
class UnitSetTableHanTest {

    @Test
    fun `han 세트를 지원한다`() {
        assertTrue(UnitSetTable.isSupported("han"))
        assertTrue(UnitSetTable.isSupported(null), "null 은 여전히 che 다")
        assertTrue(UnitSetTable.isSupported("che"))
        assertTrue(!UnitSetTable.isSupported("없는세트"))
    }

    @Test
    fun `세트별 목록이 갈린다`() {
        assertEquals(GameUnitConst.all().size, UnitSetTable.all("che").size)
        assertEquals(UnitCatalog.all("han").size, UnitSetTable.all("han").size)
        assertTrue(UnitSetTable.all("han").none { it.id < 2000 })
    }

    @Test
    fun `기본 병종은 세트마다 다르다`() {
        assertEquals(GameUnitConst.DEFAULT_CREWTYPE, UnitSetTable.defaultCrewTypeId("che"))
        assertEquals(GameUnitConst.CREWTYPE_CASTLE, UnitSetTable.castleCrewTypeId("che"))
        val hanDefault = assertNotNull(UnitSetTable.defaultCrewTypeId("han"))
        assertTrue(hanDefault >= 2000)
        // 예전 AutorunNationPolicy 는 상수 1100 을 그대로 썼다 — han 에서 NPE 였다.
        assertNotNull(UnitSetTable.byId(hanDefault))
    }

    @Test
    fun `비용 곡선은 세트와 무관하게 같은 공식이다`() {
        val che = assertNotNull(UnitSetTable.byId(GameUnitConst.DEFAULT_CREWTYPE))
        val han = assertNotNull(UnitSetTable.byId(UnitSetTable.defaultCrewTypeId("han")!!))
        for (tech in listOf(0, 1500, 9000)) {
            assertEquals(che.cost * UnitSetTable.getTechCost(tech), che.costWithTech(tech))
            assertEquals(han.rice * UnitSetTable.getTechCost(tech), han.riceWithTech(tech))
        }
    }
}
