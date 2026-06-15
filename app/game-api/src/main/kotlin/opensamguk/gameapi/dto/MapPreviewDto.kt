package opensamguk.gameapi.dto

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * World-map snapshot for the gateway lobby `MapPreview` (P7 read API).
 *
 * The gateway renders city dots (colored by owning nation) over the `che` base-map image. Field names
 * are a CLIENT CONTRACT — the gateway TS client is built against this exact shape; do not rename.
 *
 *  - `mapCode`/`width`/`height` describe the base map (`che` = 700×500 native php px; the per-map
 *    `map/<code>.json` resource carries these native dims, uniformly scaled to the canvas on the client).
 *  - `cities[].nationId` is LIVE ownership (changes as the game runs); `nationId == 0` is neutral and has
 *    NO entry in `nations[]` (the client renders neutral with a default color).
 *  - `cities[].x`/`y` are display coords (Double) read from the committed `map/<code>.json` resource (NOT in
 *    the `city` table); a city whose id is absent from the map is OMITTED from `cities` (no coord = nothing to draw).
 */
data class MapPreviewResponse(
    val serverName: String,
    val year: Int,
    val month: Int,
    val mapCode: String,
    val width: Int,
    val height: Int,
    val cities: List<MapPreviewCity>,
    val nations: List<MapPreviewNation>,
    /**
     * 시나리오 개시 연도 — legacy 맵 페이로드 필드(func_map.php:68,158 `startyear`).
     * 연월 타이틀 초반 3년 색상 게이트(P1-060)의 소비 원천. world config의 `startyear`
     * (소문자 — PHP game_env 키 그대로) 부재 시 null이고 직렬화에서 생략한다.
     */
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val startYear: Int? = null,
)

data class MapPreviewCity(
    val id: Int,
    val name: String,
    val level: Int,
    val nationId: Int,
    val x: Double,
    val y: Double,
    /** 재해/사건 코드(`city.state`) — 상태 아이콘 `event<state>.gif`(event1 풍작 / event3 혹한 …). 0=없음, 표시 안 함.
     *  func_map.php:145-147 tuple state자리 = city.state. front_state 아님. */
    val state: Int,
    /** 보급 상태 — 깃발 `f`(보급)/`d`(미보급) 구분. */
    val supply: Boolean,
    /** 지역(`city.region` V1 int) — 지역별 색/그룹 표시용. CityConst.regionMap의 int 키. */
    val region: Int,
    /** 소속국 수도 여부(nation.capital_city_id == id) — 수도 아이콘 `event51.gif`.
     *  `@get:JsonProperty` 고정 — Kotlin boolean `isX`는 Jackson이 `x`로 직렬화하므로 명시. */
    @get:JsonProperty("isCapital")
    val isCapital: Boolean,
)

data class MapPreviewNation(
    val id: Int,
    val name: String,
    /** Hex color e.g. "#c62828", taken VERBATIM from the live `nation.color` column. */
    val color: String,
)
