package opensamguk.gameapi.controller

import opensamguk.gameapi.dto.WorldMapResponse
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.NationReadRepository
import opensamguk.gameapi.read.WorldStateReadRepository
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * W9 (9a) — 인게임 world-map 읽기(`GET /api/map`). PHP `sammo/API/Global/GetMap.php` + `getWorldMap`
 * (`func_map.php:52-170`)의 충실 이식. fog(정찰 spyList / 아국 장수 소재 shownByGeneralList)를 포함하므로
 * 게이트웨이 로비 전용 [MapPreviewController]`/api/map/preview`와는 **별 엔드포인트**다(서로 co-widen 안 함).
 *
 * **인자**(`GetMap.php:18-19`, in [0,1]):
 *  - `neutralView` (기본 0): 1이면 내 국가 정보(myNation)를 숨긴 중립 시점.
 *  - `showMe` (기본 0): 1이면 내 장수 소재 도시(myCity)를 노출.
 *
 * **identity**: [FrontInfoController]와 동일하게 JWT principal(`@AuthenticationPrincipal userId`)→소유 general,
 * 없으면 `?generalId=` transition fallback, 둘 다 없으면 익명(myCity/myNation/spyList/shownByGeneralList 비움).
 * PHP는 `REQ_LOGIN`이지만 게임-API는 read 공개 정책(SecurityConfig `permitAll`)이라 익명도 200으로 중립 맵을 받는다.
 *
 * **fog 게이트 순서**(`func_map.php:78-155` byte-faithful):
 *  1. general 있고 (showMe || !neutralView) → myCity=general.city(showMe시), myNation=general.nation(!neutralView시).
 *  2. myNation 있으면 spyList = nation.meta["spy"] decode `{cityNo:remainMonth}`, 없으면 빈 맵.
 *  3. myNation 있으면 shownByGeneralList = distinct 아국 장수 소재 도시, 없으면 빈 목록.
 *  4. (PHP) userGrade≥5(어드민)는 전 도시 spyList=1 — game-api JWT principal엔 grade claim이 없어 이 분기는
 *     도달 불가(OQ-4): 보수적으로 일반 fog만 적용한다(어드민 전-도시 정찰 해제는 미구현, 의도적 divergence).
 *
 * cityList/nationList 튜플은 PHP `Util::toInt`/raw 그대로의 compact 배열(`WorldMapResponse` 참조).
 * 빈 world ⇒ 200 result=true + 빈 cityList(절대 500 아님). READ-ONLY(§7) — write-path 무관.
 */
@RestController
@RequestMapping("/api/map")
class WorldMapController(
    private val resolver: GeneralResolver,
    private val world: WorldStateReadRepository,
    private val generals: GeneralReadRepository,
    private val nations: NationReadRepository,
    private val cities: CityReadRepository,
) {

    @GetMapping
    fun map(
        @AuthenticationPrincipal userId: Long?,
        @RequestParam(required = false, defaultValue = "0") neutralView: Int,
        @RequestParam(required = false, defaultValue = "0") showMe: Int,
        @RequestParam(required = false) generalId: Int?,
    ): ResponseEntity<WorldMapResponse> {
        val neutral = neutralView == 1
        val show = showMe == 1

        // 시계 — 싱글톤 world_state. 미시드 ⇒ 빈 맵(year/month 0), 절대 500 아님.
        val w = world.findAll().firstOrNull()
        // startYear 케이싱 분열 대응(PR #31): ScenarioImporter는 config에 소문자 `startyear`(PHP 정본),
        // meta에 camelCase `startYear`를 쓴다. 정본(소문자) 우선, 그다음 camelCase·meta 폴백.
        val startYear = listOf(
            w?.config?.get("startyear"),
            w?.config?.get("startYear"),
            w?.meta?.get("startyear"),
            w?.meta?.get("startYear"),
        ).firstNotNullOfOrNull { (it as? Number)?.toInt() } ?: 0

        // identity: principal 우선, 그다음 ?generalId= transition fallback.
        val resolved = userId?.let { resolver.resolve(it) }
        val general: GeneralReadEntity? = resolved?.general
            ?: generalId?.let { generals.findById(it).orElse(null) }

        // fog 게이트 1 — myCity/myNation 결정(func_map.php:78-96).
        var myCity: Int? = null
        var myNation: Int? = null
        if (general != null && (show || !neutral)) {
            myCity = if (show) general.cityId.takeIf { it != 0 } else null
            myNation = if (neutral) null else general.nationId.takeIf { it != 0 }
        }

        // fog 게이트 2 — spyList(func_map.php:98-121). myNation의 meta["spy"]를 {cityNo:remainMonth}로 디코드.
        val spyList: Map<Int, Int> = myNation?.let { nid ->
            nations.findById(nid).orElse(null)?.let { decodeSpy(it.meta["spy"]) }
        } ?: emptyMap()

        // fog 게이트 3 — shownByGeneralList(func_map.php:133-142). 아국 장수 소재 도시.
        val shownByGeneralList: List<Int> =
            myNation?.let { generals.findDistinctCityIdByNationId(it) } ?: emptyList()

        // nationList = nation 전 행 [nation, name, color, capital] (func_map.php:123-131).
        val nationList: List<List<Any>> = nations.findAll()
            .sortedBy { it.id }
            .map { listOf(it.id, it.name, it.color, it.capitalCityId ?: 0) }

        // cityList = city 전 행 [city, level, state, nation, region, supply] (func_map.php:144-148).
        val cityList: List<List<Int>> = cities.findAll()
            .sortedBy { it.id }
            .map { listOf(it.id, it.level, it.state, it.nationId, it.region, it.supplyState) }

        return ResponseEntity.ok(
            WorldMapResponse(
                result = true,
                version = 0,
                startYear = startYear,
                year = w?.currentYear ?: 0,
                month = w?.currentMonth ?: 0,
                turnPhase = w?.currentPhase?.takeIf { it in 1..3 } ?: 1,
                turnPhaseText = turnPhaseText(w?.currentPhase ?: 1),
                cityList = cityList,
                nationList = nationList,
                spyList = spyList,
                shownByGeneralList = shownByGeneralList,
                myCity = myCity,
                myNation = myNation,
            ),
        )
    }

    private fun turnPhaseText(phase: Int): String = when (phase) {
        1 -> "상순"
        2 -> "중순"
        3 -> "하순"
        else -> "상순"
    }

    /**
     * `nation.spy` 디코드(`func_map.php:104-117`). opensamguk은 `nation.meta["spy"]`에 `{cityNo:remainMonth}`를
     * 라이드한다([PreUpdateMonthly]가 동일 형태로 decode/decay). 키/값을 Int로 정규화하고 insertion-order 보존.
     * 레거시 `"cityNo*10+remainMonth"` pipe 문자열 포맷은 opensamguk seed/엔진에 없으므로(0.8 이전 데이터)
     * map 디코드만 지원한다. 알 수 없는 형태/null ⇒ 빈 맵.
     */
    private fun decodeSpy(raw: Any?): Map<Int, Int> {
        if (raw !is Map<*, *>) return emptyMap()
        val out = LinkedHashMap<Int, Int>()
        for ((k, v) in raw) {
            val cityNo = (k as? Number)?.toInt() ?: (k as? String)?.toIntOrNull() ?: continue
            val remain = (v as? Number)?.toInt() ?: (v as? String)?.toIntOrNull() ?: continue
            out[cityNo] = remain
        }
        return out
    }
}
