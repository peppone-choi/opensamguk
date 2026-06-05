package opensamguk.gameapi.controller

import opensamguk.gameapi.dto.CityListResponse
import opensamguk.gameapi.dto.NationStaticItem
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.NationReadRepository
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * W9 (9a) — public 도시일람(`GET /api/cities`). PHP `j_get_city_list.php:32-38`의 충실 이식.
 *
 * 응답 = `{nations, cityArgsList:['city','nation','name','level'], cities}` ([CityListResponse]).
 *  - `nations` = `getAllNationStaticInfo()`(`func.php:38-82`) — 중립(0) 재야 entry **포함**.
 *    (인게임 [WorldMapController]의 nationList가 중립을 제외하는 것과 다른 정책 — 두 엔드포인트를 혼동 말 것.)
 *  - `cities` = `select city,nation,name,level from city`의 행들(컬럼 순서 = cityArgsList).
 *
 * `gennum`/`power`는 opensamguk 스키마에 컬럼이 없어 F3 [opensamguk.gameapi.rank.RankReadService] KingdomRank와
 * **동일한 proxy 정책**으로 산출: gennum = COUNT(general WHERE nation), power = SUM(general.crew WHERE nation).
 * `type` = `nation.type_code`(raw key) 그대로.
 *
 * **public read**(no auth — SecurityConfig `permitAll`). PHP 비로그인 10초 쓰로틀(`:18-30`)은 의도적 divergence로
 * 생략(MapPreview/F1 선례상 무인증 공개 read, OQ-5). 빈 world ⇒ 200 + 빈 cities.
 */
@RestController
@RequestMapping("/api")
class CityListController(
    private val cities: CityReadRepository,
    private val nations: NationReadRepository,
    private val generals: GeneralReadRepository,
) {

    @GetMapping("/cities")
    fun cityList(): ResponseEntity<CityListResponse> {
        // nations = 전 국가 static + 중립(0) 재야 entry. (getAllNationStaticInfo 동형)
        val nationItems = buildList {
            // 중립(재야) — func.php:53-66.
            add(
                NationStaticItem(
                    nation = 0,
                    name = NEUTRAL_NAME,
                    color = NEUTRAL_COLOR,
                    type = NEUTRAL_TYPE,
                    level = 0,
                    capital = 0,
                    gennum = 1,
                    power = 1,
                ),
            )
            nations.findAll()
                .filter { it.id != 0 }
                .sortedBy { it.id }
                .forEach { n ->
                    add(
                        NationStaticItem(
                            nation = n.id,
                            name = n.name,
                            color = n.color,
                            type = n.typeCode,
                            level = n.level,
                            capital = n.capitalCityId ?: 0,
                            gennum = generals.countByNationId(n.id).toInt(),
                            power = generals.sumCrewByNationId(n.id),
                        ),
                    )
                }
        }

        // cities = [city, nation, name, level] (cityArgsList 순서, j_get_city_list.php:33-34).
        val cityRows: List<List<Any>> = cities.findAll()
            .sortedBy { it.id }
            .map { listOf(it.id, it.nationId, it.name, it.level) }

        return ResponseEntity.ok(
            CityListResponse(
                nations = nationItems,
                cityArgsList = CITY_ARGS_LIST,
                cities = cityRows,
            ),
        )
    }

    companion object {
        /** PHP `j_get_city_list.php:33` 컬럼 키 순서. */
        private val CITY_ARGS_LIST = listOf("city", "nation", "name", "level")

        // 중립(재야) — func.php:53-66.
        private const val NEUTRAL_NAME = "재야"
        private const val NEUTRAL_COLOR = "#000000"
        /** PHP `GameConst::$neutralNationType` — opensamguk 중립 type_code. */
        private const val NEUTRAL_TYPE = "che_중립"
    }
}
