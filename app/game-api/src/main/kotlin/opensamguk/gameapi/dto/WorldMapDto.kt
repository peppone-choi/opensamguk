package opensamguk.gameapi.dto

/**
 * W9 (9a) — 인게임 world-map 응답(`GET /api/map`). PHP `getWorldMap`(`func_map.php:52-170`)의 출력 구조 +
 * TS 와이어 타입 `MapResult`(`hwe/ts/defs/index.ts:243-258`)와 동형.
 *
 * **lobby `MapPreview`([MapPreviewResponse])와 별개.** Preview는 게이트웨이 로비 전용 named-object 형태이고,
 * 이 응답은 게임 클라이언트가 fog(정찰/아국 장수 소재 도시)와 함께 소비하는 PHP-패러티 compact-tuple 형태다.
 *
 * compact tuple(PHP가 `array_map('Util::toInt', ...)`로 만드는 정수 배열)을 그대로 JSON 배열로 직렬화하기 위해
 * cityList/nationList를 `List<List<Any>>`로 둔다(named object 아님):
 *  - cityList tuple = `[city, level, state, nation, region, supply]` (6 Int) — `func_map.php:144-148`.
 *  - nationList tuple = `[nation, name, color, capital]` — `func_map.php:123-131`.
 *
 * fog 게이트(`func_map.php:78-155`):
 *  - `myCity` = `showMe`일 때만 내 장수 소재 도시 id, 아니면 null.
 *  - `myNation` = `neutralView`가 아닐 때만 내 국가 id, 아니면 null.
 *  - `spyList` = `{cityNo: remainMonth}`(내 국가 정찰 잔여개월). myNation 없으면 빈 맵. 어드민(userGrade≥5)은 전 도시 1.
 *  - `shownByGeneralList` = 아국 장수가 있는 도시 id 목록(myNation 없으면 빈 목록).
 */
data class WorldMapResponse(
    val result: Boolean,
    /** 캐시 버저닝(PHP `CURRENT_MAP_VERSION` = 0). */
    val version: Int,
    val mapName: String,
    val startYear: Int,
    val year: Int,
    val month: Int,
    val turnPhase: Int,
    val turnPhaseText: String,
    /** `[city, level, state, nation, region, supply]` 정수 6-튜플 배열. */
    val cityList: List<List<Int>>,
    /** `[nation, name, color, capital]` 혼합 4-튜플 배열(name/color는 String). */
    val nationList: List<List<Any>>,
    /** `{cityNo: remainMonth}` — insertion-order 보존(LinkedHashMap). myNation 없으면 빈 맵. */
    val spyList: Map<Int, Int>,
    /** 아국 장수 소재 도시 id 목록. */
    val shownByGeneralList: List<Int>,
    /** showMe일 때 내 장수 소재 도시 id, 아니면 null. */
    val myCity: Int?,
    /** neutralView가 아닐 때 내 국가 id, 아니면 null. */
    val myNation: Int?,
)
