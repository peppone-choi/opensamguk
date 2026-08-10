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
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.infra.seed.MapJson
import opensamguk.logic.domain.GetNationColors
import opensamguk.logic.actions.military.UnitSetTable
import opensamguk.logic.traits.NationTypeModule
import opensamguk.logic.traits.NationTypeRegistry
import opensamguk.logic.world.CityConstRegistry
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import kotlin.math.roundToInt

@RestController
@RequestMapping("/api/const")
class GetConstController(
    private val worldStateReadRepository: WorldStateReadRepository,
) {

    @GetMapping
    fun getConst(): ResponseEntity<GetConstResponse> = ResponseEntity.ok(build())

    private fun build(): GetConstResponse {
        val active = activeGameConfig()
        val mapData = MapJson.loadFromClasspath(active.mapName)
        val cityConst = cityConstItems(active.mapName)
        return GetConstResponse(
            mapName = active.mapName,
            mapWidth = mapData.width,
            mapHeight = mapData.height,
            maxTurn = GameConst.maxTurn,
            officerLevelText = F4StateText.officerLevelTextDefault(),
            officerLevelTextByNationLevel = F4StateText.officerLevelTextByNationLevel(),
            gameConst = gameConstBundle(active),
            gameUnitConst = gameUnitConstItems(active.unitSet),
            cityConst = cityConst,
            cityConstMap = CityConstMap(
                region = CityConst.regionMap.filterKeys { it is String }
                    .entries.associate { (k, v) -> (k as String) to (v as Int) },
                level = CityConst.levelMap.filterKeys { it is String }
                    .entries.associate { (k, v) -> (k as String) to (v as Int) },
            ),
            iAction = iActionBundle(active.unitSet),
        )
    }

    private data class ActiveGameConfig(val mapName: String, val unitSet: String)

    private fun activeGameConfig(): ActiveGameConfig {
        val world = runCatching { worldStateReadRepository.findAll().firstOrNull() }.getOrNull()
            ?: return ActiveGameConfig(GameConst.mapName, GameConst.unitSet)
        val mapName = CityConstRegistry.activeMapName(world.config, world.meta)
        val unitSet = UnitSetTable.activeUnitSet(world.config, world.meta)
        return ActiveGameConfig(mapName, unitSet)
    }

    private fun gameUnitConstItems(unitSet: String): List<GameUnitConstItem> =
        if (!UnitSetTable.isSupported(unitSet)) {
            emptyList()
        } else {
            GameUnitConst.all().values.map { u ->
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
            }
        }

    private fun cityConstItems(mapName: String): List<CityConstItem> {
        val details = MapJson.loadCityDetailsFromClasspath(mapName)
        if (details.isEmpty()) return cheCityConstItems()
        val nameById = details.associate { it.id to it.name }
        return details.map { c ->
            val path = LinkedHashMap<Int, String>()
            for (id in c.connections) {
                val name = nameById[id] ?: continue
                path[id] = name
            }
            CityConstItem(
                id = c.id,
                name = c.name,
                level = c.level,
                population = c.populationMax,
                agriculture = c.agricultureMax,
                commerce = c.commerceMax,
                security = c.securityMax,
                defence = c.defenceMax,
                wall = c.wallMax,
                region = c.region,
                posX = c.x?.roundToInt() ?: 0,
                posY = c.y?.roundToInt() ?: 0,
                path = path,
            )
        }
    }

    private fun cheCityConstItems(): List<CityConstItem> = CityConst.all().values.map { c ->
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
    }

    /**
     * 프론트가 쓰는 GameConst 표시·게이팅 상수 번들. PHP `get_class_vars('\sammo\GameConst')`는 전체
     * public 상수를 덤프하나, 여기서는 FE 출력 페이지가 실제 소비하는 안정적 부분집합만 노출한다(불변
     * 스칼라/리스트). 값은 :common 상수 그대로(패러티값) — 변형 없음.
     */
    private fun gameConstBundle(active: ActiveGameConfig): Map<String, Any?> = linkedMapOf(
        "mapName" to active.mapName,
        "unitSet" to active.unitSet,
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
        "phasesPerMonth" to GameConst.phasesPerMonth,
        "turnsPerYear" to GameConst.turnsPerYear,
        "openingLimitTurns" to GameConst.openingLimitTurns,
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
        "nationColors" to GetNationColors(),
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
    private fun iActionBundle(unitSet: String): Map<String, List<IActionItem>> = linkedMapOf(
        "nationType" to (GameConst.availableNationType + GameConst.neutralNationType)
            .map { nationTypeItem(it) },
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
        "crewtype" to UnitSetTable.all(unitSet).map { IActionItem(value = it.id.toString()) },
    )

    private fun nationTypeItem(code: String): IActionItem {
        val module = NationTypeRegistry.resolve(code) as? NationTypeModule
        return IActionItem(
            value = code,
            name = module?.typeName,
            info = module?.info?.takeIf { it.isNotBlank() }?.let { listOf(it) },
        )
    }
}
