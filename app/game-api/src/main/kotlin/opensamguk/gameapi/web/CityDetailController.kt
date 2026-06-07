package opensamguk.gameapi.web

import opensamguk.common.constants.CityConst
import opensamguk.common.constants.getCityLevelList
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.NationReadRepository
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * F2 Wave 6 — `GET /api/city/{id}` (W4 `MapCityDetail` + the W5 main screen). The full city read shape:
 * the scalar surface + the develop/defense pairs (value + max) + trust/trade/region + the stationed
 * officer count.
 *
 * **첩보(fog) 마스킹 (PHP func_map.php 가시성 패러티)**: 자기 국가 도시가 아니고(공백지 포함) 첩보(spy intel)도
 * 없고 아국 장수가 주둔하지도 않은 도시는 내정/방어 수치를 **가린다**(null). 표시 가능 표면(id/name/level/등급/
 * region/지역/nationId/보급·전선 상태 — 맵 타일에 이미 노출)만 내려보낸다. 가시성 =
 * 아국 소유 OR spyList(nation.meta["spy"] {cityNo:remainMonth}) OR 아국 장수 소재(shownByGeneralList).
 *
 * identity: [WorldMapController]/[FrontInfoController]와 동일 — JWT principal→소유 general, 없으면 `?generalId=`
 * fallback, 둘 다 없으면 익명(아무 도시도 안 보임 = 전부 마스킹). READ-ONLY(§7).
 */
@RestController
@RequestMapping("/api")
class CityDetailController(
    private val resolver: GeneralResolver,
    private val cities: CityReadRepository,
    private val generals: GeneralReadRepository,
    private val nations: NationReadRepository,
) {

    /**
     * The full city-detail shape (W4 MapCityDetail + W5). 내정/방어 수치(population..trade)와 officers는
     * 첩보 없는 비-아국/공백지에서 **null**(마스킹). `visible`=false면 FE가 "첩보 없음 — 정보 비공개"로 렌더.
     */
    data class CityDetailResponse(
        val id: Int,
        val name: String,
        val level: Int,
        val levelName: String,  // 치소 등급 한글명 getCityLevelList()[level] (수/진/관/이/소/중/대/특)
        val region: Int,
        val regionName: String, // 지역 한글명 CityConst.regionMap[region] (하북/중원/…/동이)
        val nationId: Int,
        val visible: Boolean,   // 첩보/소유/주둔으로 내정 가시 여부. false면 아래 수치 전부 null.
        val population: Int?,
        val populationMax: Int?,
        val agriculture: Int?,
        val agricultureMax: Int?,
        val commerce: Int?,
        val commerceMax: Int?,
        val security: Int?,
        val securityMax: Int?,
        val defense: Int?,
        val defenseMax: Int?,
        val wall: Int?,
        val wallMax: Int?,
        val trust: Double?,
        val trade: Int?,
        val supplyState: Int,   // 맵 타일 노출값 — 마스킹 제외.
        val frontState: Int,    // 맵 타일 노출값 — 마스킹 제외.
        val officers: Long?,    // 내정 표면 — 비가시 시 null.
    )

    @GetMapping("/city/{id}")
    fun city(
        @AuthenticationPrincipal userId: Long?,
        @RequestParam(required = false) generalId: Int?,
        @PathVariable id: Int,
    ): ResponseEntity<CityDetailResponse> {
        val c = cities.findById(id).orElse(null) ?: return ResponseEntity.notFound().build()

        // identity → myNation (principal 우선, ?generalId= fallback, 익명이면 null).
        val general = (userId?.let { resolver.resolve(it)?.general })
            ?: generalId?.let { generals.findById(it).orElse(null) }
        val myNation: Int? = general?.nationId?.takeIf { it != 0 }

        // 가시성: 아국 소유 OR spy intel OR 아국 장수 주둔 (func_map.php fog 패러티).
        val visible: Boolean = when {
            myNation == null -> false
            c.nationId == myNation -> true
            else -> {
                val spy = nations.findById(myNation).orElse(null)?.let { decodeSpy(it.meta["spy"]) } ?: emptyMap()
                spy.containsKey(c.id) || generals.findDistinctCityIdByNationId(myNation).contains(c.id)
            }
        }

        val resp = CityDetailResponse(
            id = c.id,
            name = c.name,
            level = c.level,
            levelName = getCityLevelList()[c.level] ?: "-",
            region = c.region,
            regionName = CityConst.regionMap[c.region] as? String ?: "-",
            nationId = c.nationId,
            visible = visible,
            // 비가시 도시는 내정/방어 수치 마스킹(null) — 서버에서 아예 안 내려보냄(payload 누출 방지).
            population = if (visible) c.population else null,
            populationMax = if (visible) c.populationMax else null,
            agriculture = if (visible) c.agriculture else null,
            agricultureMax = if (visible) c.agricultureMax else null,
            commerce = if (visible) c.commerce else null,
            commerceMax = if (visible) c.commerceMax else null,
            security = if (visible) c.security else null,
            securityMax = if (visible) c.securityMax else null,
            defense = if (visible) c.defense else null,
            defenseMax = if (visible) c.defenseMax else null,
            wall = if (visible) c.wall else null,
            wallMax = if (visible) c.wallMax else null,
            trust = if (visible) c.trust else null,
            trade = if (visible) c.trade else null,
            supplyState = c.supplyState,
            frontState = c.frontState,
            officers = if (visible) generals.countByCityId(c.id) else null,
        )
        return ResponseEntity.ok(resp)
    }

    /** `nation.meta["spy"]` 디코드 ({cityNo:remainMonth}) — [WorldMapController.decodeSpy] 동일 규약. */
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
