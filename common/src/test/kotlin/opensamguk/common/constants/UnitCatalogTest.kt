package opensamguk.common.constants

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnitCatalogTest {

    @Test
    fun `che 세트는 코틀린을 그대로 준다`() {
        // JSON 을 거치면 파싱 하나로 패러티가 조용히 어긋난다. che 는 GameUnitConst 가 답해야 한다.
        assertEquals(GameUnitConst.all(), UnitCatalog.all(UnitCatalog.CHE))
        for (id in GameUnitConst.all().keys) {
            assertEquals(GameUnitConst.byId(id), UnitCatalog.byId(id), "id=$id")
        }
    }

    @Test
    fun `han 세트가 실린다`() {
        val han = UnitCatalog.all("han")
        assertTrue(han.size > 100, "han 세트가 비었다: ${han.size}")
        val meta = assertNotNull(UnitCatalog.meta("han"))
        assertTrue(meta.defaultCrewTypeId in han.keys, "기본 병종 ${meta.defaultCrewTypeId} 이 세트에 없다")
        assertTrue(meta.castleCrewTypeId in han.keys, "성벽 ${meta.castleCrewTypeId} 이 세트에 없다")
        assertTrue(han.keys.all { it in meta.idRange }, "id 대역 이탈")
    }

    @Test
    fun `세트끼리 id 가 겹치지 않는다`() {
        // 겹치면 byId(id) 한 줄로 못 찾는다 — 전투·표시 경로 전체가 세트를 실어날라야 한다.
        val seen = mutableSetOf<Int>()
        for (set in UnitCatalog.sets().keys) {
            for (id in UnitCatalog.all(set).keys) {
                assertTrue(seen.add(id), "id $id 가 두 세트에 있다")
            }
        }
    }

    @Test
    fun `제약과 info 가 살아서 들어온다`() {
        val cavalry = UnitCatalog.all("han").values.first { u ->
            u.reqConstraints.any { it is UnitConstraint.ForbidRegions }
        }
        val forbid = cavalry.reqConstraints.filterIsInstance<UnitConstraint.ForbidRegions>().single()
        assertTrue("馬韓" in forbid.forbidRegions, "馬韓 금제가 빠졌다")
        // PHP _generate() 와 같은 자리 — 기본 info 뒤에 제약 설명이 붙는다.
        assertTrue(cavalry.info.last() == forbid.getInfo(), "제약 설명이 info 끝에 없다")
        assertNull(UnitCatalog.byId(99999))
    }

    @Test
    fun `세트를 아는 조회는 활성 세트 밖 id 를 거절한다`() {
        // han 병종 id 로 che 세트를 물으면 null — 전역에 있어도 활성 세트 밖은 통과시키지 않는다.
        val hanOnlyId = UnitCatalog.all("han").keys.first { it !in UnitCatalog.all(UnitCatalog.CHE).keys }
        assertNotNull(UnitCatalog.byId("han", hanOnlyId))
        assertNull(UnitCatalog.byId(UnitCatalog.CHE, hanOnlyId))

        val cheOnlyId = UnitCatalog.all(UnitCatalog.CHE).keys.first { it !in UnitCatalog.all("han").keys }
        assertNotNull(UnitCatalog.byId(UnitCatalog.CHE, cheOnlyId))
        assertNull(UnitCatalog.byId("han", cheOnlyId))
    }

    @Test
    fun `성벽은 두 세트 모두 뽑을 수 없다`() {
        for ((set, meta) in UnitCatalog.sets()) {
            val castle = assertNotNull(UnitCatalog.byId(set, meta.castleCrewTypeId), set)
            assertTrue(castle.reqConstraints.any { it is UnitConstraint.Impossible }, "$set 성벽이 뽑힌다")
        }
    }

    /**
     * han defaultCrewTypeId 가 한때 2006(reqTech>=1000)을 가리켜, 갓 건국한 국가가 시작
     * 시점에 뽑을 수 있는 병종이 하나도 없던 결함(scenario_0 han 전환 IT 를 RED 로 만들었다)의
     * 재발을 막는다. 두 조건을 세트마다 확인한다: (1) defaultCrewTypeId 자신이 무제약이어야
     * 하고, (2) 세트 안에 무제약 유닛이 하나라도 있어야 한다 — defaultCrewTypeId 가 우연히
     * 다른 무제약 유닛으로 재조정돼도 이 클래스의 결함을 계속 잡기 위해서다.
     */
    @Test
    fun `기본 병종은 시작 시점부터 무제약으로 뽑을 수 있다`() {
        for ((set, meta) in UnitCatalog.sets()) {
            val units = UnitCatalog.all(set)
            val default = assertNotNull(units[meta.defaultCrewTypeId], "$set 기본 병종 ${meta.defaultCrewTypeId} 이 세트에 없다")
            assertTrue(
                default.reqConstraints.isEmpty(),
                "$set 기본 병종 ${meta.defaultCrewTypeId} 이 reqConstraints=${default.reqConstraints} 로 게이트돼 있다",
            )
            assertTrue(
                units.values.any { it.reqConstraints.isEmpty() },
                "$set 세트에 무제약으로 뽑을 수 있는 병종이 하나도 없다",
            )
        }
    }

    /**
     * B1: `build_unitset.py` 가 한때 `commandery`/`tribe` 게이트 키를 `ReqRegions` 항목
     * 하나로 뭉쳐 `RecruitAlgorithm.any(...)` 가 OR 로 평가하던 결함(郡 전용 병종이 무관한
     * 郡에서도 뽑힘)의 재발을 막는다. 애뢰 노수(2200, requires={commandery:永昌, tribe:夷})는
     * 두 조건을 다 가진 병종이므로, 빌더가 AND 를 지키면 `ReqRegions` 항목이 키마다 하나씩
     * 2개로 나오고, 뭉쳐서 되돌아가면 1개로 줄어든다.
     */
    @Test
    fun `郡과 부족을 동시에 요구하는 병종은 ReqRegions 가 뭉치지 않는다(B1)`() {
        val laoNu = assertNotNull(UnitCatalog.byId("han", 2200), "애뢰 노수(2200)가 사라졌다")
        val reqRegions = laoNu.reqConstraints.filterIsInstance<UnitConstraint.ReqRegions>()
        assertEquals(
            2, reqRegions.size,
            "永昌+夷 게이트가 ReqRegions ${reqRegions.size}개로 뭉쳤다 — commandery/tribe AND 가 깨졌다: $reqRegions",
        )
        assertTrue(reqRegions.any { it.reqRegions == listOf("永昌") }, "永昌 단독 항목이 없다: $reqRegions")
        assertTrue(reqRegions.any { it.reqRegions == listOf("夷") }, "夷 단독 항목이 없다: $reqRegions")
    }
}
