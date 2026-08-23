package opensamguk.logic.actions.military

import opensamguk.common.constants.GameUnitConst
import opensamguk.common.constants.UnitCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    fun `세트를 아는 조회는 활성 세트 밖 id 를 거절한다`() {
        // che 병종 id 를 han 세트로 물으면 null — 전역엔 있어도 활성 세트 밖은 통과시키지 않는다.
        assertNull(UnitSetTable.byId("han", GameUnitConst.DEFAULT_CREWTYPE))
        assertNull(UnitSetTable.byId(UnitSetTable.CHE_UNIT_SET, UnitSetTable.defaultCrewTypeId("han")!!))
        // 자기 세트 안에서는 여전히 찾는다.
        assertNotNull(UnitSetTable.byId(UnitSetTable.CHE_UNIT_SET, GameUnitConst.DEFAULT_CREWTYPE))
        assertNotNull(UnitSetTable.byId("han", UnitSetTable.defaultCrewTypeId("han")!!))
    }

    @Test
    fun `비용 곡선은 세트와 무관하게 같은 공식이다`() {
        val che = assertNotNull(UnitSetTable.byId(GameUnitConst.DEFAULT_CREWTYPE))
        val han = assertNotNull(UnitSetTable.byId(UnitSetTable.defaultCrewTypeId("han")!!))
        for (tech in listOf(0, 1500, 9000)) {
            // costWithTech/riceWithTech 은 `x * crew / 100.0`(crew=100) 경로라 곱셈 순서가 달라
            // ULP 단위로 어긋날 수 있다(예: 6.8999999999999995 vs 6.9) — 공식 자체는 같으므로
            // 델타 허용 비교로 판정한다.
            assertEquals(che.cost * UnitSetTable.getTechCost(tech), che.costWithTech(tech), 1e-9)
            assertEquals(han.rice * UnitSetTable.getTechCost(tech), han.riceWithTech(tech), 1e-9)
        }
    }
}
