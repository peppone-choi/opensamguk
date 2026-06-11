package opensamguk.gameapi.web

import com.fasterxml.jackson.annotation.JsonProperty
import opensamguk.common.constants.CityConst
import opensamguk.common.constants.GameUnitConst
import opensamguk.common.constants.getCityLevelList
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.GeneralListText
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.NationReadRepository
import opensamguk.gameapi.read.WorldStateReadRepository
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
    private val world: WorldStateReadRepository,
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

        // ── b_currentCity.php 패러티 확장 (이상 = 기존 헤더/게이지 표면, 이하 = 상세 정보 표) ──────────
        /** PHP `$showDetailedInfo`(b_currentCity.php:169) — 첩보·소유·주둔·경로상 아국도시면 true. false면
         *  장수 상세표/장수명을 노출하지 않음(FE 셀에 "알 수 없음"). visible(내정 가시)과 구분된 별개 게이트다. */
        val showDetailedInfo: Boolean,
        /** PHP `$lastExecute = substr($gameStor->turntime, 5, 14)`(b_currentCity.php:19) — "MM-DD HH:MM:SS".
         *  world_state config["turntime"] 미배선 시 null(§5 날조 금지). 헤더 우측 갱신시각. */
        val lastExecute: String?,
        /** PHP 도시명행(b_currentCity.php:484) — 표 중복 노출(name 동일값, 패러티 보존). */
        val cityName: String,
        /** 태수/군사/종사 관직자명(b_currentCity.php:200-208). officer[4]/[3]/[2]. 부재 시 "-"(PHP 기본값). */
        val officerGovernor: OfficerCell,    // 태수 officer_level 4
        val officerStrategist: OfficerCell,  // 군사 officer_level 3
        val officerSecretary: OfficerCell,   // 종사 officer_level 2
        /** 군사 집계행(b_currentCity.php:374-439, number_format 표시는 FE). 비가시·무소속이면 0. */
        val military: MilitaryStat,
        /** 장수 상세표 rows(b_currentCity.php:339-368 `$generalsFormat`). showDetailedInfo=false면 빈 목록. */
        val generals: List<CityGeneralRow>,
        /** PHP `$generalsName = array_map(formatName,...)`(b_currentCity.php:370). 장수명 CSV 표시용 원천
         *  (FE가 NPC색·`, ` join). showDetailedInfo=false면 빈 목록("알 수 없음" 렌더). */
        val generalNames: List<GeneralNameCell>,
        /** 도시선택 셀렉터 옵션(b_currentCity.php:73-159). 재야=현재도시만 / 관직자=아국+아국장수주둔타국+첩보도시. */
        val citySelector: List<CitySelectorOption>,
    )

    /** 태수/군사/종사 셀. PHP officer[lv] = ['name'=>'-','npc'=>0] 기본값(b_currentCity.php:201-203). */
    data class OfficerCell(val name: String, val npc: Int)

    /**
     * 군사 집계행(b_currentCity.php:374-439). 적군은 "병력/무장장수수(장수수)", 아군 병장/90/60/수비는
     * "병력/장수수". 모두 number_format은 FE에서(서버는 raw int). 비가시·무소속(myNation 0)이면 전부 0.
     */
    data class MilitaryStat(
        val enemyCrew: Int,     // 적군 병력
        val enemyArmedCnt: Int, // 적군 무장(crew>0) 장수 수
        val enemyCnt: Int,      // 적군 장수 수
        val crewTotal: Int,     // 병장(총) 아군 병력
        val armedGenTotal: Int, // 무장 아군 장수 수
        val genTotal: Int,      // 아군 장수 수
        val crew90: Int, val gen90: Int, // min(train,atmos)>=90
        val crew60: Int, val gen60: Int, // min(train,atmos)>=60
        val crewDef: Int, val genDef: Int, // min(train,atmos)>=defenceTrain(수비○)
    )

    /**
     * 장수 상세표 row(b_currentCity.php cityGeneral.php). ourGeneral(아국·userGrade7)에서만 병종/훈련/사기/
     * 명령이 노출, 타국은 "?"·국가명, 재야는 "재 야". defenceTrainText/defenceTrain은 원천 미배선 → BLOCKED.
     */
    data class CityGeneralRow(
        val no: Int,
        val ourGeneral: Boolean,
        val iconPath: String,       // GetImageURL(imgsvr)+'/'+picture — FE가 CDN base 결합(여기선 picture 경로만)
        val npc: Int,
        // Jackson Kotlin 모듈이 Boolean `is`-접두사를 떼면 `npc`로 직렬화돼 위 npc(Int)와 충돌한다 →
        // FE 계약 키 `isNPC`(types/game.ts)로 고정(F4Dto §isChief 동일).
        @get:JsonProperty("isNPC")
        val isNPC: Boolean,         // npc > 1
        val wounded: Int,           // injury (부상 % — FE가 통무지에 formatWounded 적용)
        val name: String,
        val leadership: Int,
        val strength: Int,
        val intel: Int,
        val officerLevel: Int,
        val officerLevelText: String,
        val leadershipBonus: Int,   // calcLeadershipBonus(officerLevel, 해당 장수 국가 level)
        // ourGeneral 전용(타국/재야면 0/None — FE는 "?" 렌더). defenceTrain은 원천 미배선 → 항상 0(BLOCKED).
        val crewType: Int,
        val crewTypeName: String,   // GameUnitConst.byId(crewType).name, 비아국이면 ""
        val crew: Int,              // 비가시 타국이면 -1(PHP $crew=-1 → FE "?")
        val train: Int,             // 비아국이면 -1
        val atmos: Int,             // 비아국이면 -1
        val nation: Int,
        val nationName: String,
    )

    /** 장수명 셀(NPC색 적용용). formatName은 FE(getNPCColor)에서 — 서버는 name+npc raw 제공. */
    data class GeneralNameCell(val name: String, val npc: Int)

    /**
     * 도시선택 셀렉터 옵션(b_currentCity.php:84-152). label = "【{4자패딩 도시명}】{공백지|본국|타국}".
     * relation: 0=공백지, 1=본국(아국), 2=타국 — FE는 PHP 동일 한글로 표시(서버가 분류만).
     */
    data class CitySelectorOption(
        val cityId: Int,
        val cityName: String,
        val relation: Int, // 0 공백지 / 1 본국 / 2 타국
        val selected: Boolean,
    )

    @GetMapping("/city/{id}")
    fun city(
        @AuthenticationPrincipal userId: Long?,
        @RequestParam(required = false) generalId: Int?,
        @PathVariable id: Int,
    ): ResponseEntity<CityDetailResponse> {
        // identity → myNation (principal 우선, ?generalId= fallback, 익명이면 null).
        val general = (userId?.let { resolver.resolve(it)?.general })
            ?: generalId?.let { generals.findById(it).orElse(null) }

        // P0-12 — id<=0이면 현재 장수 소재 도시로 해석(legacy b_currentCity.php 패러티).
        val effectiveId = if (id <= 0) general?.cityId?.takeIf { it > 0 } ?: id else id
        val c = cities.findById(effectiveId).orElse(null) ?: return ResponseEntity.notFound().build()
        val myNation: Int? = general?.nationId?.takeIf { it != 0 }

        // 가시성: 내 소재 도시(재야 포함) OR 아국 소유 OR spy intel OR 아국 장수 주둔 (func_map.php fog 패러티).
        // func_map은 myCity(general.city)를 myNation과 독립적으로 노출한다 → 재야(nation 0) 장수도 자기 도시는 본다.
        val myCity: Int? = general?.cityId?.takeIf { it != 0 }
        val visible: Boolean = when {
            myCity == c.id -> true
            myNation == null -> false
            c.nationId == myNation -> true
            else -> {
                val spy = nations.findById(myNation).orElse(null)?.let { decodeSpy(it.meta["spy"]) } ?: emptyMap()
                spy.containsKey(c.id) || generals.findDistinctCityIdByNationId(myNation).contains(c.id)
            }
        }

        // PHP `$showDetailedInfo`(b_currentCity.php:169) — 장수 상세표/장수명 노출 게이트. 셀렉터에 든 도시
        // (=$valid) / 경로상 아국도시 / userGrade>=6 어드민이면 true. 여기선 visible(소유/첩보/주둔/myCity)을
        // 그대로 쓴다(동일 술어 집합). userGrade>=6 어드민 강제 노출은 게임-api에 userGrade 비배선 → 미모델
        // (관리자 전수노출은 패러티 대비 누락, 일반 플레이어 경로는 동형). BLOCKED 주석은 REPORT 참조.
        val showDetailedInfo = visible

        // ── 도시 국가/레벨 정적정보 + 전 국가 level 캐시(leadershipBonus 입력) ─────────────────────────
        val nationLevelById: Map<Int, Int> = nations.findAll().associate { it.id to it.level }
        fun nationLevelOf(nid: Int): Int = nationLevelById[nid] ?: 0
        fun nationNameOf(nid: Int): String = nations.findById(nid).orElse(null)?.name ?: ""

        // ── 태수/군사/종사 관직자(b_currentCity.php:200-208) — officer_city=cityId, level in (4,3,2) ────
        var govName = "-"; var govNpc = 0
        var strName = "-"; var strNpc = 0
        var secName = "-"; var secNpc = 0
        for (o in generals.findByOfficerCityAndOfficerLevelInOrderByIdAsc(c.id, listOf(4, 3, 2))) {
            when (o.officerLevel) {
                4 -> { govName = o.name; govNpc = o.npcState }
                3 -> { strName = o.name; strNpc = o.npcState }
                2 -> { secName = o.name; secNpc = o.npcState }
            }
        }

        // ── 장수 상세표(b_currentCity.php:215-368) — showDetailedInfo면 도시 소재 장수 turntime 순 ────────
        val cityGenerals = if (showDetailedInfo) generals.findByCityIdOrderByTurnTimeAsc(c.id) else emptyList()
        val generalRows = cityGenerals.map { g ->
            // ourGeneral = 도시 장수 국가가 0이 아니고 내 국가와 같을 때(userGrade7 강제는 미모델 — 위 주석 참조).
            val ourGeneral = g.nationId != 0 && myNation != null && g.nationId == myNation
            val isNPC = g.npcState > 1
            // 비아국이면 병종/훈련/사기 마스킹(PHP: train=-1, atmos=-1, crewType=0). !valid면 crew=-1.
            val crewType = if (ourGeneral) g.crewTypeId else 0
            // PHP: ourGeneral이면 `GameUnitConst::byID($crewtype)->name` — 실 장수의 crewtype은 항상 유효 id(>=1000,
            // ResetHelper가 DEFAULT_CREWTYPE 1100으로 초기화). byID는 id<1000에 예외를 던지므로(파리티), 미초기화
            // crewtype(<1000)은 호출하지 않고 ""(FrontInfoController crewTypeName 동식). 비아국이면 PHP ''.
            val crewTypeName = if (ourGeneral && g.crewTypeId >= 1000) (GameUnitConst.byId(g.crewTypeId)?.name ?: "") else ""
            val crew = if (ourGeneral) g.crew else if (!visible) -1 else g.crew
            val train = if (ourGeneral) g.train else -1
            val atmos = if (ourGeneral) g.atmos else -1
            CityGeneralRow(
                no = g.id,
                ourGeneral = ourGeneral,
                iconPath = g.picture ?: "",
                npc = g.npcState,
                isNPC = isNPC,
                wounded = g.injury,
                name = g.name,
                leadership = g.leadership,
                strength = g.strength,
                intel = g.intel,
                officerLevel = g.officerLevel,
                officerLevelText = GeneralListText.officerLevelText(g.officerLevel, nationLevelOf(g.nationId)),
                leadershipBonus = calcLeadershipBonus(g.officerLevel, nationLevelOf(g.nationId)),
                crewType = crewType,
                crewTypeName = crewTypeName,
                crew = crew,
                train = train,
                atmos = atmos,
                nation = g.nationId,
                nationName = nationNameOf(g.nationId),
            )
        }
        val generalNames = if (showDetailedInfo) cityGenerals.map { GeneralNameCell(it.name, it.npcState) } else emptyList()

        // ── 군사 집계행(b_currentCity.php:374-439) — 아군/적군 분리 집계. myNation 0이면 전부 0(PHP continue). ─
        var enemyCrew = 0; var enemyCnt = 0; var enemyArmedCnt = 0
        var crewTotal = 0; var armedGenTotal = 0; var genTotal = 0
        var crew90 = 0; var gen90 = 0; var crew60 = 0; var gen60 = 0; var crewDef = 0; var genDef = 0
        for (g in generalRows) {
            // PHP: `if (!$general['nation'] || !$myNation['nation']) continue;`
            if (g.nation == 0 || myNation == null || myNation == 0) continue
            if (g.nation != myNation) {
                enemyCnt += 1
                if (g.crew >= 0) enemyCrew += g.crew
                if (g.crew > 0) enemyArmedCnt += 1
                continue
            }
            crewTotal += g.crew
            genTotal += 1
            if (g.crew == 0) continue
            armedGenTotal += 1
            val minTrain = minOf(g.train, g.atmos)
            if (minTrain >= 90) { crew90 += g.crew; gen90 += 1 }
            if (minTrain >= 60) { crew60 += g.crew; gen60 += 1 }
            // defenceTrain 원천 미배선(GeneralReadEntity에 defence_train 컬럼 미존재) → 0으로 처리.
            // PHP `$minTrain >= $defenceTrain` 비교의 defenceTrain이 0이면 항상 참 → 수비○가 과집계될 수 있어
            // BLOCKED(REPORT 기재). 현재는 0 기준(미배선 표기). 원천 배선 시 1줄 교체로 정합.
            val defenceTrain = 0
            if (minTrain >= defenceTrain) { crewDef += g.crew; genDef += 1 }
        }

        // ── 도시선택 셀렉터(b_currentCity.php:73-159) — 재야=현재도시만 / 관직자=아국+아국장수주둔타국+첩보 ────
        val citySelector = buildCitySelector(general, myNation, c.id)

        // ── 갱신시각(b_currentCity.php:19) — substr(turntime, 5, 14) = "MM-DD HH:MM:SS". KV 미배선 시 null. ──
        val lastExecute = lastExecuteOf()

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
            showDetailedInfo = showDetailedInfo,
            lastExecute = lastExecute,
            cityName = c.name,
            officerGovernor = OfficerCell(govName, govNpc),
            officerStrategist = OfficerCell(strName, strNpc),
            officerSecretary = OfficerCell(secName, secNpc),
            military = MilitaryStat(
                enemyCrew = enemyCrew, enemyArmedCnt = enemyArmedCnt, enemyCnt = enemyCnt,
                crewTotal = crewTotal, armedGenTotal = armedGenTotal, genTotal = genTotal,
                crew90 = crew90, gen90 = gen90, crew60 = crew60, gen60 = gen60,
                crewDef = crewDef, genDef = genDef,
            ),
            generals = generalRows,
            generalNames = generalNames,
            citySelector = citySelector,
        )
        return ResponseEntity.ok(resp)
    }

    /**
     * PHP `calcLeadershipBonus($officerLevel, $nationLevel)`(func_process.php:52-61) — GeneralList의 lbonus와
     * 동일 식(raw officer_level 사용). 12=레벨*2, 5이상=레벨, 그 외 0.
     */
    private fun calcLeadershipBonus(officerLevel: Int, nationLevel: Int): Int = when {
        officerLevel == 12 -> nationLevel * 2
        officerLevel >= 5 -> nationLevel
        else -> 0
    }

    /**
     * PHP `$lastExecute = substr($gameStor->turntime, 5, 14)`(b_currentCity.php:19). turntime은
     * "YYYY-MM-DD HH:MM:SS[.ffffff]" 문자열 → 인덱스 5..18(14자) = "MM-DD HH:MM:SS". world_state
     * config["turntime"] 미배선 시 null(§5 — 값 날조 금지, FE가 "-" 또는 빈칸 렌더).
     */
    private fun lastExecuteOf(): String? {
        val turntime = world.findAll().firstOrNull()?.config?.get("turntime")?.toString() ?: return null
        if (turntime.length < 5) return null
        return turntime.substring(5, minOf(19, turntime.length))
    }

    /**
     * 도시선택 셀렉터(b_currentCity.php:73-159). 재야(officer_level 0)면 현재 소재 도시만. 관직자면
     * 아국 도시 전부 + 아국 장수가 주둔한 타국 도시 + (국가레벨>0 시) 첩보 도시. relation: 0 공백지 / 1 본국 /
     * 2 타국(아국 비교, 셀렉터엔 사실상 본국·타국만). selected = 현재 조회 cityId.
     */
    private fun buildCitySelector(
        general: GeneralReadEntity?,
        myNation: Int?,
        currentCityId: Int,
    ): List<CitySelectorOption> {
        val out = LinkedHashMap<Int, CitySelectorOption>() // 도시 id 중복 제거(삽입순 보존 §6)
        fun add(cid: Int) {
            if (out.containsKey(cid)) return
            val city = cities.findById(cid).orElse(null) ?: return
            val relation = when {
                city.nationId == 0 -> 0
                myNation != null && city.nationId == myNation -> 1
                else -> 2
            }
            out[cid] = CitySelectorOption(
                cityId = cid,
                cityName = city.name,
                relation = relation,
                selected = cid == currentCityId,
            )
        }

        val officerLevel = general?.officerLevel ?: 0
        if (general == null || officerLevel == 0) {
            // 재야/익명: 현재 소재 도시(없으면 조회 대상 도시)만.
            add(general?.cityId?.takeIf { it != 0 } ?: currentCityId)
        } else if (myNation != null) {
            // 아국 도시 전부.
            for (city in cities.findAll().filter { it.nationId == myNation }.sortedBy { it.id }) add(city.id)
            // 아국 장수가 주둔한 타국 도시.
            for (cid in generals.findDistinctCityIdByNationId(myNation)) {
                val city = cities.findById(cid).orElse(null) ?: continue
                if (city.nationId != myNation) add(cid)
            }
            // 첩보 도시(국가 레벨>0 시).
            val nation = nations.findById(myNation).orElse(null)
            if (nation != null && nation.level > 0) {
                for (cid in decodeSpy(nation.meta["spy"]).keys) add(cid)
            }
        } else {
            add(general.cityId.takeIf { it != 0 } ?: currentCityId)
        }
        return out.values.toList()
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
