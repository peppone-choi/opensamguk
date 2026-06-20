package opensamguk.gameapi.controller

import opensamguk.common.constants.CityConst
import opensamguk.common.constants.GameConst
import opensamguk.common.constants.GameUnitConst
import opensamguk.gameapi.dto.CityConstItem
import opensamguk.gameapi.dto.CityConstMap
import opensamguk.gameapi.dto.GameUnitConstItem
import opensamguk.gameapi.dto.GetConstResponse
import opensamguk.gameapi.dto.IActionItem
import opensamguk.gameapi.read.F4StateText
import opensamguk.infra.seed.MapJson
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * W3 — `GetConst` 정적 상수 서비스(`GET /api/const`).
 *
 * PHP grand truth: `API/Global/GetConst.php`(NO_SESSION, **DB read 없음** — 파일 캐시 정적 API).
 * 이 컨트롤러도 DB/레포를 주입하지 않고 `:common`의 컴파일타임 상수 + 커밋된 정적 리소스
 * (`map/<code>.json` dims, `F4StateText` 직책 테이블)만 직렬화한다. one-daemon-write
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

    /** 맵 표시 dims — 커밋된 `map/<GameConst.mapName>.json` 리소스에서 읽는다(MapPreviewController와
     *  공유하는 [MapJson.loadFromClasspath] 단일 로더 — 컨트롤러 리터럴 금지). 정적 리소스라 1회 로드. */
    private val mapData: MapJson.MapData by lazy { MapJson.loadFromClasspath(GameConst.mapName) }

    @GetMapping
    fun getConst(): ResponseEntity<GetConstResponse> = ResponseEntity.ok(build())

    private fun build(): GetConstResponse = GetConstResponse(
        // 구 /api/const 필드(FE 소비) — mapName/dims/maxTurn/officerLevelText.
        mapName = GameConst.mapName,
        mapWidth = mapData.width,
        mapHeight = mapData.height,
        maxTurn = GameConst.maxTurn,
        // 직책 라벨 — 정본 F4StateText 단일 테이블(PHP func_converter.php:522-565 getOfficerLevelText)
        // 직렬화. 와이어 모양은 hwe/ts/utilGame/formatOfficerLevelText.ts(기본열 + 국가레벨별 테이블).
        officerLevelText = F4StateText.officerLevelTextDefault(),
        officerLevelTextByNationLevel = F4StateText.officerLevelTextByNationLevel(),
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
        "initialAllowedTechLevel" to GameConst.initialAllowedTechLevel,
        "techLevelIncYear" to GameConst.techLevelIncYear,
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
        // 장수 생성/빙의 스탯 캡(d_setting) — PageJoin 폼·진입(server-basic-info) defaultStatTotal 노출.
        "defaultStatTotal" to GameConst.defaultStatTotal,
        "defaultStatMin" to GameConst.defaultStatMin,
        "defaultStatMax" to GameConst.defaultStatMax,
        "chiefStatMin" to GameConst.chiefStatMin,
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
