package opensamguk.gameapi.dto

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * W3 — `GetConst` 정적 상수 번들 응답 (`GET /api/const`).
 *
 * PHP grand truth: `legacy/devsam-core/hwe/sammo/API/Global/GetConst.php::genConstData()` —
 * `NO_SESSION` 정적 API로, **DB read가 전혀 없다**(파일 캐시만 사용). 우리도 DB를 만지지 않고
 * `:common`의 [opensamguk.common.constants.GameConst] / [opensamguk.common.constants.GameUnitConst] /
 * [opensamguk.common.constants.CityConst]를 그대로 직렬화한다.
 *
 * 본 번들에 담는 것(no-DB 정적 부분, W3 스펙):
 *  - `gameUnitConst` = `GameUnitConst.all()`의 유닛 상세(병종 일람) — PHP `GameUnitConst::all()`.
 *  - `cityConst`     = `CityConst.all()`의 도시 초기치 — PHP `CityConst::all()`.
 *  - `cityConstMap`  = region/level 양방향 라벨↔int 맵 — PHP `CityConst::$regionMap`/`$levelMap`.
 *  - `iAction`       = nationType/specialDomestic/specialWar/personality/item/crewtype 키맵 — PHP
 *    `genConstData()`의 `iActionInfo`에 대응(여기서는 키→표시문자열 번들로 단순화; getName/getInfo의
 *    동적 클래스 인스턴스화는 :logic 의존이라 BLOCKED §아래 참조).
 *  - `gameConst`     = `GameConst`의 스칼라/리스트 설정값 일부(프론트가 쓰는 표시·게이팅 상수).
 *
 * **BLOCKED (의도적 누락 — W3_PLAN §2 / fabricate 금지):**
 *  - PHP `iActionInfo`의 각 항목 `getName()/getInfo()`는 `buildXxxClass()`로 iAction 인스턴스를
 *    런타임 생성해 한글 표시명을 얻는다. 이는 `:logic` CommandRegistry/iAction 포팅에 결합돼 있어
 *    game-api 정적 번들에서 안전하게 노출할 수 없다 → 키 목록(value)만 노출하고 name/info는 비운다.
 *    (날조 금지: 없는 한글명을 지어내지 않는다. FE는 별도 i18n 테이블로 표시명을 채운다.)
 *  - `version`(VersionGit) / 캐시키 / lastModified는 게임 상수가 아니라 빌드 메타라 제외.
 */
data class GetConstResponse(
    // ── 구 /api/const(GameConstResponse) FE-소비 필드 흡수(엔드포인트 단일화). web/game lib/api.ts가
    //    mapName/mapWidth/mapHeight/maxTurn/officerLevelText를 읽으므로 superset으로 보존. ──
    val result: Boolean = true,
    val mapName: String,
    val mapWidth: Int,
    val mapHeight: Int,
    val maxTurn: Int,
    /**
     * 직책 라벨 기본열(officerLevel → 한글명) — 정본 `F4StateText`(PHP func_converter.php:522-565
     * `getOfficerLevelText`) 직렬화. 와이어 모양 = `hwe/ts/utilGame/formatOfficerLevelText.ts`
     * `OfficerLevelMapDefault`(nlevel=8 기본열 + 공통 0..4).
     */
    val officerLevelText: Map<Int, String>,
    /**
     * 국가레벨(7..0)별 수뇌 직책 맵(nationLevel → officerLevel → 한글명) — 동일 정본 테이블의
     * `OfficerLevelMapByNationLevel` 와이어. PHP 미정의 코드는 키 생략(PHP '-').
     */
    val officerLevelTextByNationLevel: Map<Int, Map<Int, String>>,
    /** 게임 스칼라/리스트 상수 번들(프론트 표시·게이팅용). 키 = 상수명, 값 = 직렬화된 상수값. */
    val gameConst: Map<String, Any?>,
    /** 병종 일람 — id → 유닛 상세 와이어. PHP `GameUnitConst::all()`(id-삽입순). */
    val gameUnitConst: List<GameUnitConstItem>,
    /** 도시 초기치 일람 — PHP `CityConst::all()`(id-삽입순). */
    val cityConst: List<CityConstItem>,
    /** region/level 양방향 라벨↔int 맵. PHP `cityConstMap`. */
    val cityConstMap: CityConstMap,
    /**
     * iAction 키맵(nationType/specialDomestic/…/crewtype) — value-only(표시명 BLOCKED).
     * Jackson BeanIntrospector가 `getIAction` 게터를 디캐피탈라이즈할 때 앞 2글자(IA)가 모두
     * 대문자라 `IAction`으로 남겨버리므로(JavaBeans 규칙), 와이어 키를 `iAction`으로 고정한다.
     */
    @get:JsonProperty("iAction")
    val iAction: Map<String, List<IActionItem>>,
)

/** 병종 한 종의 와이어(`GameUnitDetail` 직렬화 서브셋 — 프론트가 쓰는 표시·전투 필드). */
data class GameUnitConstItem(
    val id: Int,
    val armType: Int,
    val name: String,
    val attack: Int,
    val defence: Int,
    val speed: Int,
    val avoid: Int,
    val magicCoef: Double,
    val cost: Int,
    val rice: Int,
    /** _generate()로 각 제약(getInfo)이 append된 최종 설명 리스트. */
    val info: List<String>,
)

/** 도시 초기치 한 행의 와이어(`CityInitialDetail` 직렬화 서브셋). */
data class CityConstItem(
    val id: Int,
    val name: String,
    val level: Int,
    val population: Int,
    val agriculture: Int,
    val commerce: Int,
    val security: Int,
    val defence: Int,
    val wall: Int,
    val region: Int,
    val posX: Int,
    val posY: Int,
    /** 인접 도시 id → 이름(삽입순). */
    val path: Map<Int, String>,
)

/** region/level 양방향 라벨↔int 맵(PHP `CityConst::$regionMap`/`$levelMap`을 라벨→int 한 방향으로 노출). */
data class CityConstMap(
    /** 지역 라벨 → int (예: "하북"→1). */
    val region: Map<String, Int>,
    /** 도시 레벨 라벨 → int (예: "수"→1). lv4='이' lv5='소'(프로젝트 메모리). */
    val level: Map<String, Int>,
)

/**
 * iAction 한 항목 — PHP `extractObjClassInfo`의 `{value, name, info}` 형태.
 *
 * `name`/`info`는 BLOCKED(§GetConstResponse): :logic iAction 인스턴스화 없이 한글 표시명을
 * 안전하게 채울 수 없어 null로 둔다(날조 금지). `value`(상수 키)만 신뢰 가능한 정적 원천이다.
 */
data class IActionItem(
    val value: String,
    /** BLOCKED — :logic iAction getName() 결합. null 고정(fabricate 금지). */
    val name: String? = null,
    /** BLOCKED — :logic iAction getInfo() 결합. null 고정(fabricate 금지). */
    val info: List<String>? = null,
)
