package opensamguk.gameapi.dto

/**
 * W9 (9a) — public 도시일람 응답(`GET /api/cities`). PHP `j_get_city_list.php:32-38`와 동형.
 *
 * PHP shape: `{nations: NationStaticItem[], cityArgsList: ['city','nation','name','level'], cities: ValuesOf[][]}`.
 *  - `nations` = `getAllNationStaticInfo()`(`func.php:38-82`) — 중립(0) 재야 entry 포함.
 *  - `cityArgsList` = 컬럼 키 순서(`['city','nation','name','level']`).
 *  - `cities` = `select city,nation,name,level from city`의 행들(컬럼 순서 = cityArgsList).
 *
 * cities 튜플은 `[city:Int, nation:Int, name:String, level:Int]` 혼합형이라 `List<List<Any>>`로 직렬화.
 */
data class CityListResponse(
    val nations: List<NationStaticItem>,
    val cityArgsList: List<String>,
    /** `[city, nation, name, level]` 4-튜플 배열(cityArgsList 순서). */
    val cities: List<List<Any>>,
)

/**
 * PHP `NationStaticItem`(`func.php:38-82`) — `getNationStaticInfo`/`getAllNationStaticInfo`의 국가 정적 정보.
 * 중립(0) = `{nation:0, name:"재야", color:"#000000", type:<중립성향>, level:0, capital:0, gennum:1, power:1}`.
 *
 *  - `type` = `nation.type`(raw type_code, 예: `che_위나라`) — opensamguk `nation.type_code`를 그대로 노출.
 *  - `gennum` = 국가 소속 장수 수(opensamguk엔 컬럼 없음 → countByNationId 산출; 중립은 1).
 *  - `power` = 국력(opensamguk엔 컬럼 없음 → SUM(crew) proxy, F3 KingdomRank와 동일 정책; 중립은 1).
 */
data class NationStaticItem(
    val nation: Int,
    val name: String,
    val color: String,
    val type: String,
    val level: Int,
    val capital: Int,
    val gennum: Int,
    val power: Long,
)
