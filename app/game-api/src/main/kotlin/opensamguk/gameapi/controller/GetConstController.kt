package opensamguk.gameapi.controller

import opensamguk.common.constants.CityConst
import opensamguk.common.constants.GameConst
import opensamguk.common.constants.GameUnitConst
import opensamguk.gameapi.dto.CityConstItem
import opensamguk.gameapi.dto.CityConstMap
import opensamguk.gameapi.dto.GameUnitConstItem
import opensamguk.gameapi.dto.GetConstResponse
import opensamguk.gameapi.dto.IActionItem
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * W3 — `GetConst` 정적 상수 서비스(`GET /api/const`).
 *
 * PHP grand truth: `API/Global/GetConst.php`(NO_SESSION, **DB read 없음** — 파일 캐시 정적 API).
 * 이 컨트롤러도 DB/레포를 주입하지 않고 `:common`의 컴파일타임 상수만 직렬화한다. one-daemon-write
 * 규칙·정적 read seam 모두 무관(write 경로 밖, 순수 상수 노출).
 *
 * 캐싱: PHP는 파일 캐시 + cacheKey를 쓰지만 이 번들은 빌드타임 상수라 사실상 불변이므로 별도 캐시
 * 없이 매 요청 동일 객체를 조립한다(비용 미미). 필요 시 후속 웨이브에서 MapPreview처럼 volatile
 * holder를 얹을 수 있으나 정적 상수에는 과잉이다.
 *
 * 누락(BLOCKED, fabricate 금지): iAction의 한글 표시명(getName/getInfo)은 :logic iAction
 * 인스턴스화 결합 → value(키)만 노출. 자세한 사유는 `dto/GetConstDto.kt` 참조.
 */
@RestController
@RequestMapping("/api/const")
class GetConstController {

    /** 직책 라벨(구 GlobalMenuController.gameConst에서 이관). devsam getNationLevelText 서브셋. */
    private val OFFICER_LEVEL_TEXT: Map<Int, String> = linkedMapOf(
        0 to "재야", 1 to "일반", 2 to "종사", 3 to "군사", 4 to "태수",
        5 to "참모", 6 to "장군", 7 to "총사령관", 11 to "군주대리", 12 to "군주",
    )

    @GetMapping
    fun getConst(): ResponseEntity<GetConstResponse> = ResponseEntity.ok(build())

    private fun build(): GetConstResponse = GetConstResponse(
        // 구 /api/const 필드(FE 소비) — mapName/dims/maxTurn/officerLevelText.
        mapName = GameConst.mapName,
        mapWidth = 1000,
        mapHeight = 714,
        maxTurn = GameConst.maxTurn,
        officerLevelText = OFFICER_LEVEL_TEXT,
        gameConst = gameConstBundle(),
        gameUnitConst = GameUnitConst.all().values.map { u ->
            GameUnitConstItem(
                id = u.id,
                armType = u.armType,
                name = u.name,
                attack = u.attack,
                defence = u.defence,
                speed = u.speed,
                avoid = u.avoid,
                magicCoef = u.magicCoef,
                cost = u.cost,
                rice = u.rice,
                info = u.info,
            )
        },
        cityConst = CityConst.all().values.map { c ->
            CityConstItem(
                id = c.id,
                name = c.name,
                level = c.level,
                population = c.population,
                agriculture = c.agriculture,
                commerce = c.commerce,
                security = c.security,
                defence = c.defence,
                wall = c.wall,
                region = c.region,
                posX = c.posX,
                posY = c.posY,
                path = c.path,
            )
        },
        cityConstMap = CityConstMap(
            // 양방향 맵에서 라벨(String)→int(Int) 방향만 추출(역방향 int→label은 중복이라 제외).
            region = CityConst.regionMap.filterKeys { it is String }
                .entries.associate { (k, v) -> (k as String) to (v as Int) },
            level = CityConst.levelMap.filterKeys { it is String }
                .entries.associate { (k, v) -> (k as String) to (v as Int) },
        ),
        iAction = iActionBundle(),
    )

    /**
     * 프론트가 쓰는 GameConst 표시·게이팅 상수 번들. PHP `get_class_vars('\sammo\GameConst')`는 전체
     * public 상수를 덤프하나, 여기서는 FE 출력 페이지가 실제 소비하는 안정적 부분집합만 노출한다(불변
     * 스칼라/리스트). 값은 :common 상수 그대로(패러티값) — 변형 없음.
     */
    private fun gameConstBundle(): Map<String, Any?> = linkedMapOf(
        "mapName" to GameConst.mapName,
        "unitSet" to GameConst.unitSet,
        "maxTurn" to GameConst.maxTurn,
        "maxChiefTurn" to GameConst.maxChiefTurn,
        "maxTechLevel" to GameConst.maxTechLevel,
        "maxLevel" to GameConst.maxLevel,
        "maxDedLevel" to GameConst.maxDedLevel,
        "statGradeLevel" to GameConst.statGradeLevel,
        "develrate" to GameConst.develrate,
        "upgradeLimit" to GameConst.upgradeLimit,
        "dexLimit" to GameConst.dexLimit,
        "defaultMaxGeneral" to GameConst.defaultMaxGeneral,
        "defaultMaxNation" to GameConst.defaultMaxNation,
        "defaultStartYear" to GameConst.defaultStartYear,
        "retirementYear" to GameConst.retirementYear,
        "adultAge" to GameConst.adultAge,
        "openingPartYear" to GameConst.openingPartYear,
        "minGoldRequiredWhenBetting" to GameConst.minGoldRequiredWhenBetting,
        "maxResourceActionAmount" to GameConst.maxResourceActionAmount,
        "resourceActionAmountGuide" to GameConst.resourceActionAmountGuide,
        // 국가 레벨 0-9 APPEND 테이블([name, chiefCnt, cityCnt]) — 프론트 레벨 라벨/게이팅.
        "nationLevelByCityCnt" to GameConst.nationLevelByCityCnt09,
        "availableNationType" to GameConst.availableNationType,
        "neutralNationType" to GameConst.neutralNationType,
        "availableSpecialDomestic" to GameConst.availableSpecialDomestic,
        "availableSpecialWar" to GameConst.availableSpecialWar,
        "availablePersonality" to GameConst.availablePersonality,
        // 장수/수뇌 명령 메뉴(카테고리→명령 키 목록) — 프론트 명령창 구성.
        "availableGeneralCommand" to GameConst.availableGeneralCommand,
        "availableChiefCommand" to GameConst.availableChiefCommand,
    )

    /**
     * iAction 키맵 — PHP `iActionInfo`의 카테고리 구조를 value-only로 노출.
     *
     * 각 항목은 `{value, name:null, info:null}`. name/info는 BLOCKED(§컨트롤러 doc): :logic iAction
     * getName/getInfo 인스턴스화 없이는 한글 표시명을 신뢰 가능하게 채울 수 없어 null 고정(fabricate 금지).
     *  - nationType: availableNationType + neutralNationType
     *  - specialDomestic: defaultSpecialDomestic + available + optional
     *  - specialWar: defaultSpecialWar + available + optional
     *  - personality: neutralPersonality + available + optional
     *  - item: allItems의 모든 카테고리 키를 평탄화
     *  - crewtype: GameUnitConst.all()의 id 문자열
     */
    private fun iActionBundle(): Map<String, List<IActionItem>> = linkedMapOf(
        "nationType" to (GameConst.availableNationType + GameConst.neutralNationType)
            .map { IActionItem(value = it) },
        "specialDomestic" to (listOf(GameConst.defaultSpecialDomestic) +
            GameConst.availableSpecialDomestic + GameConst.optionalSpecialDomestic)
            .distinct().map { IActionItem(value = it) },
        "specialWar" to (listOf(GameConst.defaultSpecialWar) +
            GameConst.availableSpecialWar + GameConst.optionalSpecialWar)
            .distinct().map { IActionItem(value = it) },
        "personality" to (listOf(GameConst.neutralPersonality) +
            GameConst.availablePersonality + GameConst.optionalPersonality)
            .distinct().map { IActionItem(value = it) },
        // allItems = {horse:{...}, weapon:{...}, book:{...}, item:{...}} → 모든 키 평탄화(PHP flatLevel=1).
        "item" to GameConst.allItems.values.flatMap { it.keys }.map { IActionItem(value = it) },
        "crewtype" to GameUnitConst.all().keys.map { IActionItem(value = it.toString()) },
    )
}
