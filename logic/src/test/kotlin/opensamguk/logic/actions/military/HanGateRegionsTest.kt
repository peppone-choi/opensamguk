package opensamguk.logic.actions.military

import opensamguk.common.constants.GameUnitConst
import opensamguk.common.constants.HanGateIndex
import opensamguk.common.constants.UnitCatalog
import opensamguk.common.constants.UnitConstraint
import opensamguk.logic.domain.General
import opensamguk.logic.world.CityConstRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * han 병종의 게이트 키 판정 — [HanGateIndex] (생성물) 가 실제로 물리는지 고정한다.
 *
 * 규칙(확정): `ReqRegions` = 국가가 **보유한 城** 중 하나라도 그 키를 가지면 통과(인접은 보지 않는다).
 * `ForbidRegions` = 장수가 **서 있는 城**의 키로 판정한다.
 *
 * 주의(뮤테이션 테스트 함정): 유닛이 `ReqRegions` 를 여러 개(郡+부족처럼 AND) 요구해도, 국가가
 * 城을 **여러 개** 보유하면 그중 서로 다른 城이 각 제약을 나눠 만족해도 통과한다 — 한 城이 모든
 * 키를 동시에 가질 필요가 없다(`RecruitUnitAvailability.isValid` 가 제약별로 `ownCities` 전체를
 * `any{}` 로 독립 검사하기 때문). 그래서 `own` 에 城을 두 개 이상 넣은 테스트에서 GATE_PLACES
 * 별칭 하나를 지워도 RED 가 안 뜰 수 있다 — 프로덕션 로직이 그렇게 동작하는 게 맞고 테스트 결함이
 * 아니다. "같은 城 하나가 전부 가진다"를 검증하려면 [cityWithKeys] 로 찾은 단일 城만 `own` 에 넣어라.
 */
class HanGateRegionsTest {

    private val han = CityConstRegistry.of("han")
    private val che = CityConstRegistry.of("che")

    // 城 id 는 생성기가 州 → 郡 순으로 다시 매기면 통째로 밀린다. 박아두지 말고 게이트 키로 찾는다.
    private fun cityWithKey(key: String) = han.all().keys.first { key in HanGateIndex.keys(it) }

    /** 주어진 키를 **전부** 가진 城. 郡+부족 AND 게이트가 실제로 같은 城에서 만나는지 증명할 때 쓴다. */
    private fun cityWithKeys(vararg keys: String) =
        han.all().keys.firstOrNull { id -> keys.all { it in HanGateIndex.keys(id) } }

    /** 幽州 게이트 키를 가진 城. */
    private val youzhouCity = cityWithKey("幽州")

    /** 烏桓 거점. 幽州 城들과 인접하지만 州 밖(외부)이다. */
    private val wuhuanCity = cityWithKey("烏桓")

    /** 邪馬壹國(왜). 기병 32종의 ForbidRegions 대상이다. */
    private val waCity = cityWithKey("邪馬壹國")

    /** 京兆尹(治 장안) — 司隸. 幽州·烏桓·鮮卑 어느 키도 없다.
     *  '장안'은 會稽郡에도 있어 이름만으로는 못 찍는다 — 州까지 같이 본다. */
    private val changanCity = han.all().values
        .first { it.name == "장안" && it.region == han.regionIdByName("사예") }.id

    private fun general(cityId: Int) = General(
        id = 1, nationId = 1, cityId = cityId,
        leadership = 70, strength = 70, intel = 70, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = 1,
        gold = 0, rice = 0, crew = 0, train = 0.0, atmos = 0.0, crewTypeId = 2000,
    )

    private fun canRecruit(
        unit: opensamguk.common.constants.GameUnitDetail,
        standingAt: Int,
        own: List<Int>,
        cityConst: opensamguk.logic.world.CityConstVariant = han,
    ): Boolean = RecruitUnitAvailability.isValid(
        unit, general(standingAt),
        ownCities = own.associateWith { 5 },
        ownRegions = own.mapNotNull { cityConst.byId(it)?.region }.toSet(),
        relYear = 100, tech = 99999, nationAux = emptyMap(), cityConst = cityConst,
    )

    /** 유주돌기 — ReqTech 2000 + ReqRegions(幽州·烏桓·鮮卑) + 왜/마한 ForbidRegions. */
    private val youzhouTuqi = UnitCatalog.byId("han", 2100)!!

    @Test
    fun `幽州 城을 보유하면 유주돌기를 뽑는다`() {
        assertTrue(canRecruit(youzhouTuqi, standingAt = youzhouCity, own = listOf(youzhouCity)))
    }

    @Test
    fun `幽州 烏桓 鮮卑 어느 것도 없으면 유주돌기를 못 뽑는다`() {
        assertFalse(canRecruit(youzhouTuqi, standingAt = changanCity, own = listOf(changanCity)))
    }

    @Test
    fun `烏桓 거점은 보유해야 통과하고 인접만으로는 안 된다`() {
        val wuhuanOnly = youzhouTuqi.copy(reqConstraints = listOf(UnitConstraint.ReqRegions(listOf("烏桓"))))
        val neighbour = han.byId(wuhuanCity)!!.path.keys.first { "幽州" in HanGateIndex.keys(it) }

        assertTrue(canRecruit(wuhuanOnly, standingAt = wuhuanCity, own = listOf(wuhuanCity)))
        assertFalse(canRecruit(wuhuanOnly, standingAt = neighbour, own = listOf(neighbour)))
    }

    @Test
    fun `왜에 주둔한 장수는 금제 기병을 못 뽑는다`() {
        val forbidden = UnitCatalog.all("han").values.filter { u ->
            u.reqConstraints.any { it is UnitConstraint.ForbidRegions && "邪馬壹國" in it.forbidRegions }
        }
        assertEquals(32, forbidden.size, "금제 기병 32종")

        // 幽州·烏桓 을 다 보유해 ReqRegions 는 모두 통과시키고, 주둔지만 왜로 둔다.
        val own = listOf(youzhouCity, wuhuanCity, waCity)
        assertTrue(canRecruit(youzhouTuqi, standingAt = youzhouCity, own = own), "주둔지가 유주면 통과")
        for (unit in forbidden) {
            assertFalse(canRecruit(unit, standingAt = waCity, own = own), "왜 주둔: ${unit.name}")
        }
    }

    @Test
    fun `애뢰 노수는 越巂 만으로는 못 뽑고 永昌+夷 를 보유해야 뽑는다(B1 회귀 방어)`() {
        // "B1 복원"이 아니다 — `origin/main` 기준 units.json 을 직접 덤프해 확인한 결과, 郡+부족
        // 두 키를 하나의 ReqRegions 로 뭉쳐 AND 가 OR 로 새는 build_unitset.py 버그(B1)는 이
        // 브랜치의 base 에 애초에 없다(2196~2201 전부 이미 별도 ReqRegions 항목으로 분리돼 있다).
        // 그 버그는 `origin/work/opensamguk/han-map-wave-v3` 브랜치가 도중에 스스로 만든 회귀였고
        // 그 브랜치 자체 커밋 09b70418 이 그 브랜치 안에서만 되돌린다 — 여기 이식 대상이 아니다.
        // 이 테스트는 그 이력과 무관하게, #529 GATE_PLACES 수정이 실제로 郡+부족 AND 를 같은
        // 城에서 요구함을 이 브랜치 기준으로 고정하는 회귀 방어다.
        val laoNu = UnitCatalog.byId("han", 2200)!! // 애뢰 노수: ReqRegions(永昌) + ReqRegions(夷)
        val yongchangCity = cityWithKey("永昌")
        val yuexiCity = cityWithKey("越巂")

        assertFalse(
            canRecruit(laoNu, standingAt = yuexiCity, own = listOf(yuexiCity)),
            "越巂 만 보유해서는 永昌+夷 게이트를 못 채운다",
        )
        assertTrue(
            canRecruit(laoNu, standingAt = yongchangCity, own = listOf(yongchangCity)),
            "永昌 城(夷 태그도 같이 가진다)을 보유하면 뽑힌다",
        )
    }

    /**
     * #529 — han 신규 郡+부족 유닛 6종(2196–2201)이 郡 게이트 키 부재로 도달 불가.
     *
     * 6종 전부 commandery(郡)+tribe(부족) 두 ReqRegions 를 AND 로 요구한다. 각 유닛이 요구하는
     * 郡+부족 태그를 **같은 城**에서 가지고 있어야 실제로 도달(모집) 가능함을 고정한다 —
     * "게이트 키를 채웠다"가 아니라 "그 城 하나만 보유해도 실제로 모집된다"를 증명한다.
     */
    @Test
    fun `郡+부족 신규 유닛 6종이 실제로 모집 가능한 城 을 하나 이상 갖는다(#529)`() {
        val cases = listOf(
            2196 to ("武陵" to "蠻"), // 무릉만 노수
            2197 to ("武陵" to "蠻"), // 오계만 도병
            2198 to ("牂牁" to "夷"), // 장가이병
            2199 to ("越巂" to "叟"), // 월수 수병
            2200 to ("永昌" to "夷"), // 애뢰 노수
            2201 to ("鬱林" to "蠻"), // 오호만병
        )
        for ((id, keys) in cases) {
            val (commandery, tribe) = keys
            val unit = UnitCatalog.byId("han", id)!!
            val city = cityWithKeys(commandery, tribe)
            assertTrue(
                city != null,
                "${unit.name}($id): $commandery+$tribe 를 같이 가진 城이 HanGateIndex 에 없다",
            )
            assertTrue(
                canRecruit(unit, standingAt = city!!, own = listOf(city)),
                "${unit.name}($id): $commandery+$tribe 城($city) 하나만 보유해도 실제로 모집돼야 한다",
            )
        }
    }

    /**
     * #511 — han 이민족 병종 GATE_PLACES 태그 커버리지 공백의 전수 회귀.
     *
     * 병종 하나하나를 이름으로 나열하지 않는다 — 앞으로 추가될 han 병종도 자동으로 덮이도록,
     * `ReqRegions` 를 가진 모든 han 병종에 대해 그 요구 태그를 **전부** 가진 城이 최소 1개
     * 있음을 전수로 건다. 태그 하나가 요구 郡/州 밖에만 붙어도 이 테스트가 즉시 잡는다.
     */
    @Test
    fun `모든 han 병종은 ReqRegions 태그를 전부 가진 城이 최소 1개 있다(#511 전수)`() {
        val zeroCoverage = UnitCatalog.all("han").values.mapNotNull { unit ->
            val groups = unit.reqConstraints.filterIsInstance<UnitConstraint.ReqRegions>().map { it.reqRegions }
            if (groups.isEmpty()) return@mapNotNull null
            val covered = han.all().keys.any { id ->
                val gate = HanGateIndex.keys(id)
                groups.all { g -> g.any { it in gate } }
            }
            if (covered) null else "${unit.name}(${unit.id}): $groups"
        }
        assertTrue(zeroCoverage.isEmpty(), "모집 가능 城 이 0인 han 병종: $zeroCoverage")
    }

    @Test
    fun `che 는 게이트 키가 없어 기존 지역 라벨 경로 그대로다`() {
        assertTrue(che.all().keys.all { che.gateKeys(it).isEmpty() })

        val unit = GameUnitConst.byId(1300)!!.copy(reqConstraints = listOf(UnitConstraint.ReqRegions(listOf("하북"))))
        val hebei = che.all().values.first { it.region == che.regionIdByName("하북") }.id
        val other = che.all().values.first { it.region != che.regionIdByName("하북") }.id
        assertTrue(canRecruit(unit, standingAt = hebei, own = listOf(hebei), cityConst = che))
        assertFalse(canRecruit(unit, standingAt = other, own = listOf(other), cityConst = che))
    }
}
