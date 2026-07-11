package opensamguk.gameapi.controller

import opensamguk.gameapi.dto.AdminDiplomacyAllResponse
import opensamguk.gameapi.dto.AdminDiplomacyRow
import opensamguk.gameapi.dto.AdminBlockedWrite
import opensamguk.gameapi.dto.AdminEditableField
import opensamguk.gameapi.dto.AdminFieldOption
import opensamguk.gameapi.dto.AdminGameSettingsResponse
import opensamguk.gameapi.dto.AdminGeneralDetail
import opensamguk.gameapi.dto.AdminGeneralLogResponse
import opensamguk.gameapi.dto.AdminGeneralModerationResponse
import opensamguk.gameapi.dto.AdminGeneralModerationRow
import opensamguk.gameapi.dto.AdminGeneralSelectOption
import opensamguk.gameapi.dto.AdminGeneralSortOption
import opensamguk.gameapi.dto.AdminNationStatsResponse
import opensamguk.gameapi.dto.AdminNationStatsRow
import opensamguk.gameapi.dto.AdminNationStatsSortOption
import opensamguk.gameapi.read.GameKvReadRepository
import opensamguk.gameapi.read.AdminGeneralLogReadRepository
import opensamguk.gameapi.read.CityReadEntity
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.DiplomacyReadEntity
import opensamguk.gameapi.read.DiplomacyReadRepository
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.GeneralTurnReadRepository
import opensamguk.gameapi.read.NationReadEntity
import opensamguk.gameapi.read.NationReadRepository
import opensamguk.gameapi.read.RankDataReadRepository
import opensamguk.gameapi.read.ScenarioTitleResolver
import opensamguk.gameapi.read.TurnTimeFormatter
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.gameapi.security.GameApiJwtVerifier
import opensamguk.common.constants.GameConst
import opensamguk.logic.util.jsonDecodeAny
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * B3a/B4a/B4b — 어드민 read API(`_admin5`/`_admin7`/`_admin8`). 전부 READ-only(game-api JPA read repo만
 * 소비, mutation 0). 강제 뮤테이션(국가변경/블럭 등)은 별개로 game-engine intake(B6)에서만 처리한다.
 *
 * **ADMIN 게이트(0.9.0)**: legacy는 `Session::requireGameLogin(); if userGrade < 6 die(...)`로 grade
 * 다단계 게이트를 건다. opensamguk 0.9.0은 단일 ADMIN 롤이므로(grade 4/5/6 다단계 = 의도적 divergence,
 * MEMORY: project_versioning_0_9_parity) gateway access token의 `role` 클레임이 `"ADMIN"`인지만 검사한다.
 * 토큰 부재/무효 → 401, ADMIN 아님 → 403. 게이트는 [requireAdmin]로 각 핸들러 진입부에서 강제한다.
 *
 * (라우트 레벨 hasRole 게이트 대신 컨트롤러 내부 게이트를 쓰는 이유: game-api JwtVerifyFilter가 모든
 * 인증 요청에 ROLE_USER만 부여하고 role 클레임을 권한으로 승격하지 않기에 — 기존 SecurityConfig/Filter를
 * 건드리지 않고 자기완결적으로 게이트하려고 verifier의 role 클레임을 직접 읽는다. NpcPolicyController의
 * 컨트롤러-내부 permission 게이트와 동형.)
 */
@RestController
@RequestMapping("/api/admin")
class AdminReadController(
    private val verifier: GameApiJwtVerifier,
    private val nations: NationReadRepository,
    private val generals: GeneralReadRepository,
    private val cities: CityReadRepository,
    private val ranks: RankDataReadRepository,
    private val diplomacy: DiplomacyReadRepository,
    private val generalLogs: AdminGeneralLogReadRepository,
    private val world: WorldStateReadRepository,
    private val gameKv: GameKvReadRepository,
    private val generalTurns: GeneralTurnReadRepository,
    private val scenarioTitle: ScenarioTitleResolver,
) {

    // ──────────────────────────────────────────────────────────────────────
    // ADMIN 게이트
    // ──────────────────────────────────────────────────────────────────────

    /** ADMIN 게이트 결과 — null이면 통과, 아니면 즉시 반환할 에러 응답(401/403). */
    private fun requireAdmin(authorization: String?): ResponseEntity<Any>? {
        val token = bearer(authorization) ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        if (!verifier.isValid(token)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        if (verifier.getRole(token) != ADMIN_ROLE) return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        return null
    }

    private fun bearer(authorization: String?): String? {
        if (authorization == null || !authorization.startsWith("Bearer ")) return null
        return authorization.substring(7).ifBlank { null }
    }

    private fun mapCodeOf(config: Map<String, Any?>, meta: Map<String, Any?>): String =
        mapNameOf(config["map"]) ?: mapNameOf(meta["map"]) ?: "che"

    private fun mapNameOf(raw: Any?): String? = when (raw) {
        is String -> raw.takeIf { it.isNotBlank() }
        is Map<*, *> -> (raw["mapName"] ?: raw["name"] ?: raw["code"])?.toString()?.takeIf { it.isNotBlank() }
        else -> null
    }

    private fun turnPhaseText(phase: Int): String = when (phase) {
        1 -> "상순"
        2 -> "중순"
        3 -> "하순"
        else -> "상순"
    }

    @GetMapping("/game-settings")
    fun gameSettings(
        @RequestHeader(value = "Authorization", required = false) authorization: String?,
    ): ResponseEntity<Any> {
        requireAdmin(authorization)?.let { return it }

        val w = world.findById(1).orElse(null)
        val config = w?.config.orEmpty()
        val msg = firstGameEnvText("msg") ?: stringConfig(config["msg"]) ?: ""
        val turnterm = intConfig(config["turnterm"]) ?: w?.let { it.tickSeconds / 60 }

        return ResponseEntity.ok(
            AdminGameSettingsResponse(
                msg = msg,
                logWritable = false,
                scenarioCode = w?.scenarioCode,
                scenarioText = w?.let { scenarioTitle.titleOf(it.scenarioCode) ?: it.scenarioCode },
                mapCode = w?.let { mapCodeOf(it.config, it.meta) },
                year = w?.currentYear,
                month = w?.currentMonth,
                turnPhase = w?.currentPhase?.takeIf { it in 1..3 },
                turnPhaseText = w?.currentPhase?.let { turnPhaseText(it) },
                status = w?.status,
                starttime = stringConfig(config["starttime"]) ?: stringConfig(w?.meta?.get("startTime")),
                startyear = intConfig(config["startyear"]) ?: intConfig(w?.meta?.get("startYear")) ?: GameConst.defaultStartYear,
                maxgeneral = intConfig(config["maxgeneral"]) ?: GameConst.defaultMaxGeneral,
                maxnation = intConfig(config["maxnation"]) ?: GameConst.defaultMaxNation,
                turntime = stringConfig(config["turntime"]),
                turnterm = turnterm,
                turnOptions = TURN_OPTIONS,
                blockedWrites = GAME_SETTING_WRITES,
                editableFields = buildEditableFields(config, msg, turnterm ?: DEFAULT_TURN_TERM),
            ),
        )
    }

    @GetMapping("/general-moderation")
    fun generalModeration(
        @RequestHeader(value = "Authorization", required = false) authorization: String?,
    ): ResponseEntity<Any> {
        requireAdmin(authorization)?.let { return it }

        val all = generals.findAll().sortedWith(compareBy<GeneralReadEntity> { it.npcState }.thenBy { it.name }.thenBy { it.id })
        val turns = if (all.isEmpty()) emptyMap() else generalTurns.findReservedByGeneralIds(all.map { it.id }).groupBy { it.generalId }
        val rows = all.map { g ->
            val ring = turns[g.id].orEmpty()
            AdminGeneralModerationRow(
                no = g.id,
                name = g.name,
                npc = g.npcState,
                block = intConfig(g.meta["block"]) ?: intConfig(g.meta["blockLevel"]) ?: intConfig(g.penalty["blockLevel"]) ?: 0,
                killturn = intConfig(g.meta["killturn"]) ?: intConfig(g.penalty["killturn"]),
                nationId = g.nationId,
                turnTime = TurnTimeFormatter.full(g.turnTime),
                command0 = ring.firstOrNull { it.turnIdx == 0 }?.brief,
                command1 = ring.firstOrNull { it.turnIdx == 1 }?.brief,
            )
        }

        return ResponseEntity.ok(
            AdminGeneralModerationResponse(
                generals = rows,
                bulkActions = GENERAL_BULK_WRITES,
                selectedActions = GENERAL_SELECTED_WRITES,
            ),
        )
    }

    // ──────────────────────────────────────────────────────────────────────
    // B3a — 일제정보(`_admin5.php`) 국가별 전체 통계
    // ──────────────────────────────────────────────────────────────────────

    /**
     * `GET /admin/nation-stats?type=&type2=` — 국가별 전체 통계 + 정렬(type 0~17, type2 0~6) verbatim.
     *
     * PHP `_admin5.php`:
     *  - type/type2 범위 클램프(`:13-18`): type<0||>17→0, type2<0||>6→0.
     *  - nation×general JOIN GROUP BY로 dex1~5 평균 + power/tech/gold/rice(`:135-205`), type별 ORDER BY.
     *  - 국가별 general 집계(cnt/avgGold/avgRice/sumLeadership/avgL·S·I·E/sumCrew, `:207-215`),
     *    city 집계(cnt/pop/popMax/popRate/agri/comm/secu/wall/def, `:217-226`).
     *  - historyStats(`statistic`)/sabotageLog는 opensamguk 스키마 원천 부재 → BLOCKED(DTO 주석 참조).
     *
     * read-only. ADMIN 게이트.
     */
    @GetMapping("/nation-stats")
    fun nationStats(
        @RequestHeader(value = "Authorization", required = false) authorization: String?,
        @RequestParam(value = "type", defaultValue = "0") typeRaw: Int,
        @RequestParam(value = "type2", defaultValue = "0") type2Raw: Int,
    ): ResponseEntity<Any> {
        requireAdmin(authorization)?.let { return it }

        // PHP `:13-18` 범위 클램프(verbatim).
        val type = if (typeRaw < 0 || typeRaw > 17) 0 else typeRaw
        val type2 = if (type2Raw < 0 || type2Raw > 6) 0 else type2Raw

        // N+1 방지: 장수/도시를 1회 로드 후 nationId로 그룹.
        val allGenerals = generals.findAll()
        val genByNation = allGenerals.groupBy { it.nationId }
        val cityByNation = cities.findAll().groupBy { it.nationId }

        // PHP `FROM nation A, general B WHERE A.nation=B.nation GROUP BY B.nation`(`:151-153`)은
        // INNER JOIN이라 **소속 장수가 1명 이상인 국가만** 통계행에 포함된다(장수 0인 국가/재야=0 제외).
        // opensamguk도 동형으로 genByNation에 키가 있는 일반 국가(id!=0)만 산출한다.
        val rows = nations.findAll()
            .filter { it.id != 0 && genByNation.containsKey(it.id) }
            .map { nation ->
                buildNationStatsRow(nation, genByNation[nation.id].orEmpty(), cityByNation[nation.id].orEmpty())
            }

        // type별 정렬(PHP `_admin5.php:156-205` ORDER BY ... DESC verbatim). 모두 내림차순.
        // 2/11/12는 legacy에도 case 부재(결번) → 정렬 미적용(PHP는 GROUP BY 자연순서 유지).
        val sortedRows = when (type) {
            0 -> rows.sortedByDescending { it.power }
            1 -> rows.sortedByDescending { it.genCnt }
            2 -> rows.sortedByDescending { it.tech }
            3 -> rows.sortedByDescending { it.gold }
            4 -> rows.sortedByDescending { it.rice }
            5 -> rows.sortedByDescending { it.avgGold }
            6 -> rows.sortedByDescending { it.avgRice }
            7 -> rows.sortedByDescending { it.avgLeadership }
            8 -> rows.sortedByDescending { it.avgStrength }
            9 -> rows.sortedByDescending { it.avgIntel }
            10 -> rows.sortedByDescending { it.avgExpLevel }
            13 -> rows.sortedByDescending { it.dex1 }
            14 -> rows.sortedByDescending { it.dex2 }
            15 -> rows.sortedByDescending { it.dex3 }
            16 -> rows.sortedByDescending { it.dex4 }
            17 -> rows.sortedByDescending { it.dex5 }
            else -> rows
        }

        return ResponseEntity.ok(
            AdminNationStatsResponse(
                type = type,
                type2 = type2,
                sortOptions = SORT_OPTIONS,
                sortOptions2 = SORT_OPTIONS2,
                rows = sortedRows,
            ),
        )
    }

    /** 한 국가의 통계 1행 산출(PHP general/city 집계 쿼리 충실 포팅). */
    private fun buildNationStatsRow(
        nation: NationReadEntity,
        gens: List<GeneralReadEntity>,
        cityList: List<CityReadEntity>,
    ): AdminNationStatsRow {
        val genCnt = gens.size
        // PHP general 집계: ROUND(AVG(gold/rice)) 0자리, ROUND(AVG(L/S/I/exp),1) 1자리, SUM(leadership/crew).
        val avgGold = avgRound0(gens.map { it.gold.toDouble() })
        val avgRice = avgRound0(gens.map { it.rice.toDouble() })
        val avgL = avgRound1(gens.map { it.leadership.toDouble() })
        val avgS = avgRound1(gens.map { it.strength.toDouble() })
        val avgI = avgRound1(gens.map { it.intel.toDouble() })
        // PHP `explevel` = 경험 레벨. opensamguk은 explevel 컬럼이 없고 experience(누적 경험)를 쓴다 —
        // legacy general.explevel 등가가 부재해 experience 평균을 표기(BLOCKED 대신 충실 근사: 동일 의미축).
        val avgE = avgRound1(gens.map { it.experience.toDouble() })
        val sumLeadership = gens.sumOf { it.leadership.toLong() }
        val sumCrew = gens.sumOf { it.crew.toLong() }
        // dex1~5: general.meta 라이드, PHP ROUND(AVG(dexN)) 0자리.
        val dex1 = avgRound0(gens.map { metaDouble(it.meta, "dex1") })
        val dex2 = avgRound0(gens.map { metaDouble(it.meta, "dex2") })
        val dex3 = avgRound0(gens.map { metaDouble(it.meta, "dex3") })
        val dex4 = avgRound0(gens.map { metaDouble(it.meta, "dex4") })
        val dex5 = avgRound0(gens.map { metaDouble(it.meta, "dex5") })

        // PHP city 집계: SUM(pop/pop_max), 비율은 ROUND(SUM(x)/SUM(x_max)*100, 2) 2자리.
        val cityCnt = cityList.size
        val pop = cityList.sumOf { it.population.toLong() }
        val popMax = cityList.sumOf { it.populationMax.toLong() }
        val agri = ratioRound2(cityList.sumOf { it.agriculture.toLong() }, cityList.sumOf { it.agricultureMax.toLong() })
        val comm = ratioRound2(cityList.sumOf { it.commerce.toLong() }, cityList.sumOf { it.commerceMax.toLong() })
        val secu = ratioRound2(cityList.sumOf { it.security.toLong() }, cityList.sumOf { it.securityMax.toLong() })
        val wall = ratioRound2(cityList.sumOf { it.wall.toLong() }, cityList.sumOf { it.wallMax.toLong() })
        val def = ratioRound2(cityList.sumOf { it.defense.toLong() }, cityList.sumOf { it.defenseMax.toLong() })
        // PHP popRate(`:251`): sprintf('%.1f', pop / valueFit(pop_max,1) * 100) — pop_max 최소 1로 클램프.
        val popRate = round1(pop.toDouble() / maxOf(popMax, 1L).toDouble() * 100.0)

        return AdminNationStatsRow(
            nationId = nation.id,
            name = nation.name,
            color = nation.color,
            power = nation.power,
            genCnt = genCnt,
            cityCnt = cityCnt,
            tech = round1(nation.tech),
            strategicCmdLimit = (nation.meta["strategicCmdLimit"] as? Number)?.toInt() ?: 0,
            gold = nation.gold,
            rice = nation.rice,
            avgGold = avgGold,
            avgRice = avgRice,
            avgLeadership = avgL,
            avgStrength = avgS,
            avgIntel = avgI,
            avgExpLevel = avgE,
            dex1 = dex1,
            dex2 = dex2,
            dex3 = dex3,
            dex4 = dex4,
            dex5 = dex5,
            sumCrew = sumCrew,
            sumLeadership = sumLeadership,
            pop = pop,
            popMax = popMax,
            popRate = popRate,
            agri = agri,
            comm = comm,
            secu = secu,
            wall = wall,
            def = def,
        )
    }

    // ──────────────────────────────────────────────────────────────────────
    // B4a — 로그정보(`_admin7.php`) 장수 상세/기록
    // ──────────────────────────────────────────────────────────────────────

    /**
     * `GET /admin/general-log?gen=&query_type=` — 장수 상세 + 4개 로그 패널 + 정렬(queryMap 4종) verbatim.
     *
     * PHP `_admin7.php`:
     *  - queryMap(`:15-31`): turntime/recent_war/name/warnum, 각 정렬 비교자(comp) verbatim.
     *  - query_type 무효/부재 → 첫 키(turntime, `:33-35`).
     *  - 정렬된 generalBasicList에서 gen 미지정이면 첫 장수(`:75-77`).
     *  - 선택 장수의 action/battle/history/battle_brief 로그 24건(history는 전량) `order by id desc`.
     *
     * read-only. ADMIN 게이트.
     */
    @GetMapping("/general-log")
    fun generalLog(
        @RequestHeader(value = "Authorization", required = false) authorization: String?,
        @RequestParam(value = "gen", defaultValue = "0") genRaw: Int,
        @RequestParam(value = "query_type", required = false) queryTypeRaw: String?,
    ): ResponseEntity<Any> {
        requireAdmin(authorization)?.let { return it }

        // PHP `:33-35`: query_type 무효/부재 → queryMap 첫 키(turntime).
        val queryType = if (queryTypeRaw != null && QUERY_KEYS.contains(queryTypeRaw)) queryTypeRaw else QUERY_KEYS.first()

        val allGenerals = generals.findAll()

        // warnum 정렬은 rank_data(type='warnum') 조인 필요 — 해당 정렬일 때만 N+1-safe 일괄 조회.
        val warnumByGeneral: Map<Int, Int> =
            if (queryType == "warnum") {
                ranks.findByGeneralIdsAndTypes(allGenerals.map { it.id }, listOf("warnum"))
                    .associate { it.generalId to it.value }
            } else {
                emptyMap()
            }

        // PHP comp(`:16-30`) verbatim 정렬:
        //  turntime  : -(turntime <=> )  → turn_time DESC (newest first)
        //  recent_war: -(recent_war <=> )→ recent_war_time DESC
        //  name      : npc ASC, 그다음 name ASC
        //  warnum    : -(warnum <=> )    → warnum DESC
        val sorted: List<GeneralReadEntity> = when (queryType) {
            "turntime" -> allGenerals.sortedWith(
                compareByDescending<GeneralReadEntity> { it.turnTime }.thenBy { it.id },
            )
            "recent_war" -> allGenerals.sortedWith(
                compareByDescending<GeneralReadEntity> { it.recentWarTime }.thenBy { it.id },
            )
            "name" -> allGenerals.sortedWith(
                compareBy<GeneralReadEntity> { it.npcState }.thenBy { it.name }.thenBy { it.id },
            )
            "warnum" -> allGenerals.sortedWith(
                compareByDescending<GeneralReadEntity> { warnumByGeneral[it.id] ?: 0 }.thenBy { it.id },
            )
            else -> allGenerals
        }

        // PHP `:75-77`: gen 미지정(0)이면 정렬 첫 장수.
        val gen = if (genRaw != 0) genRaw else (sorted.firstOrNull()?.id ?: 0)

        val selectOptions = sorted.map {
            AdminGeneralSelectOption(
                no = it.id,
                name = it.name,
                // PHP `substr($turntime, 14, 5)`(HH:MM 보조 라벨). opensamguk turn_time → "HH:MM".
                turnTimeHm = turnTimeHm(it),
            )
        }

        val detail = sorted.firstOrNull { it.id == gen }?.let { g -> buildGeneralDetail(g) }

        return ResponseEntity.ok(
            AdminGeneralLogResponse(
                queryType = queryType,
                sortOptions = SORT_OPTIONS_GENERAL,
                generalList = selectOptions,
                gen = gen,
                detail = detail,
            ),
        )
    }

    /** 선택 장수의 상세 + 4개 로그 패널(category별 24건/전량). PHP `_admin7.php:133-168`. */
    private fun buildGeneralDetail(g: GeneralReadEntity): AdminGeneralDetail = AdminGeneralDetail(
        no = g.id,
        name = g.name,
        nationId = g.nationId,
        npc = g.npcState,
        leadership = g.leadership,
        strength = g.strength,
        intel = g.intel,
        politics = g.politics, // 정치/매력 (RTK14 divergence)
        charm = g.charm, // 정치/매력 (RTK14 divergence)
        officerLevel = g.officerLevel,
        turnTime = TurnTimeFormatter.full(g.turnTime),
        actionLog = generalLogs.findRecentByGeneral(g.id, "ACTION", LOG_RECENT_COUNT).map { it.text },
        battleDetailLog = generalLogs.findRecentByGeneral(g.id, "BATTLE_DETAIL", LOG_RECENT_COUNT).map { it.text },
        historyLog = generalLogs.findAllHistoryByGeneral(g.id).map { it.text },
        battleResultLog = generalLogs.findRecentByGeneral(g.id, "BATTLE_BRIEF", LOG_RECENT_COUNT).map { it.text },
    )

    // ──────────────────────────────────────────────────────────────────────
    // B4b — 외교정보(`_admin8.php`) 전 국가간 외교 (NO masking)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * `GET /admin/diplomacy-all` — 전 국가간 외교(교전/선포/통상/불가침) 전체. **마스킹 없음**(어드민).
     *
     * PHP `_admin8.php:78-111`:
     *  - `SELECT * from diplomacy where me < you order by state desc`,
     *  - `state == 2`(통상) 행은 skip(`:82-84`),
     *  - state→문자열 switch verbatim(0 교 전 / 1 선포중 / 2 통 상 / 7 불가침),
     *  - 국가명/색은 getAllNationStaticInfo() 맵.
     *
     * `GetDiplomacy.php`(중립 마스킹)와 달리 **모든 state 원본 노출**(어드민 read). read-only. ADMIN 게이트.
     */
    @GetMapping("/diplomacy-all")
    fun diplomacyAll(
        @RequestHeader(value = "Authorization", required = false) authorization: String?,
    ): ResponseEntity<Any> {
        requireAdmin(authorization)?.let { return it }

        val nationName = mutableMapOf<Int, String>()
        val nationColor = mutableMapOf<Int, String>()
        for (n in nations.findAll()) {
            nationName[n.id] = n.name
            nationColor[n.id] = n.color
        }

        // PHP: where me < you, order by state desc, state==2 skip.
        val relations = diplomacy.findAll()
            .filter { it.srcNationId < it.destNationId }
            .filter { it.stateCode != 2 }
            .sortedWith(
                // state DESC(PHP order by state desc). 동률 안정성 위해 me,you 2차 키(결정적 출력, 패러티 무관).
                compareByDescending<DiplomacyReadEntity> { it.stateCode }
                    .thenBy { it.srcNationId }
                    .thenBy { it.destNationId },
            )
            .map { d ->
                AdminDiplomacyRow(
                    me = d.srcNationId,
                    meName = nationName[d.srcNationId] ?: "",
                    meColor = nationColor[d.srcNationId] ?: "#000000",
                    you = d.destNationId,
                    youName = nationName[d.destNationId] ?: "",
                    youColor = nationColor[d.destNationId] ?: "#000000",
                    state = d.stateCode,
                    stateText = diplomacyStateText(d.stateCode),
                    term = d.term,
                )
            }

        return ResponseEntity.ok(AdminDiplomacyAllResponse(relations = relations))
    }

    // ──────────────────────────────────────────────────────────────────────
    // 집계/포맷 헬퍼
    // ──────────────────────────────────────────────────────────────────────

    /** general.meta의 dexN을 Double로(부재/비숫자 → 0). */
    private fun metaDouble(meta: Map<String, Any?>, key: String): Double =
        (meta[key] as? Number)?.toDouble() ?: 0.0

    /** PHP ROUND(AVG(x)) 0자리, half-away-from-zero. 빈 집합 → 0(PHP AVG of empty = NULL → 표기 0). */
    private fun avgRound0(values: List<Double>): Long {
        if (values.isEmpty()) return 0
        val avg = values.sum() / values.size
        return BigDecimal.valueOf(avg).setScale(0, RoundingMode.HALF_UP).toLong()
    }

    /** PHP ROUND(AVG(x), 1) 1자리, half-away. 빈 집합 → 0.0. */
    private fun avgRound1(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val avg = values.sum() / values.size
        return BigDecimal.valueOf(avg).setScale(1, RoundingMode.HALF_UP).toDouble()
    }

    /** PHP ROUND(SUM(x)/SUM(x_max)*100, 2) 2자리, half-away. 분모 0 → 0.0(div-by-zero 회피). */
    private fun ratioRound2(sum: Long, sumMax: Long): Double {
        if (sumMax == 0L) return 0.0
        return BigDecimal.valueOf(sum.toDouble() / sumMax.toDouble() * 100.0)
            .setScale(2, RoundingMode.HALF_UP).toDouble()
    }

    /** PHP sprintf('%.1f', x)와 동형의 1자리 half-away 반올림. */
    private fun round1(value: Double): Double =
        BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).toDouble()

    /**
     * PHP `substr($turntime, 14, 5)` 등가 — 장수 select 보조 라벨용 "HH:MM". opensamguk turn_time은
     * timestamptz라 TurnTimeFormatter.full("YYYY-MM-DD HH:MM:SS")에서 11번째 5자(HH:MM)를 취한다.
     * (PHP는 microsecond 포함 문자열의 14,5 = 시:분이지만 opensamguk은 초까지만 정규화 — HH:MM 동일 의미.)
     */
    private fun turnTimeHm(g: GeneralReadEntity): String {
        val full = TurnTimeFormatter.full(g.turnTime) ?: return ""
        // "YYYY-MM-DD HH:MM:SS" → index 11..15 = "HH:MM".
        return if (full.length >= 16) full.substring(11, 16) else ""
    }

    /** PHP `_admin8.php:86-101` state→문자열 switch verbatim. */
    private fun diplomacyStateText(state: Int): String = when (state) {
        0 -> "교 전"
        1 -> "선포중"
        2 -> "통 상"
        7 -> "불가침"
        // PHP는 미지 state에 Exception을 던지나, read API에선 500 대신 원시값 라벨로 graceful 노출.
        else -> "상태$state"
    }

    private fun firstGameEnvText(key: String): String? =
        gameKv.findByTableAndNamespaceAndKey("game_env", "global", key)?.value?.let(::jsonScalar)
            ?: gameKv.findByTableAndNamespaceAndKey("game_env", "game_env", key)?.value?.let(::jsonScalar)
            ?: gameKv.findByTableAndNamespaceAndKey("game_env", "", key)?.value?.let(::jsonScalar)
            ?: gameKv.findByTableAndNamespaceAndKey("game_env", "default", key)?.value?.let(::jsonScalar)

    private fun stringConfig(value: Any?): String? = when (value) {
        null -> null
        is String -> value
        is Number, is Boolean -> value.toString()
        else -> null
    }

    private fun intConfig(value: Any?): Int? = when (value) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }

    private fun jsonScalar(raw: String): String =
        when (val decoded = runCatching { jsonDecodeAny(raw) }.getOrNull()) {
            null -> ""
            is String -> decoded
            is Number, is Boolean -> decoded.toString()
            else -> raw.trim()
        }

    /** 어드민이 라이브에서 수정할 수 있는 게임 환경 설정 항목 정의. */
    private fun buildEditableFields(
        config: Map<String, Any?>,
        msg: String,
        currentTurnTerm: Int,
    ): List<AdminEditableField> {
        val npcMode = intConfig(config["npcmode"]) ?: 0
        val blockGeneralCreate = intConfig(config["block_general_create"]) ?: 0
        return listOf(
            AdminEditableField(
                key = "msg",
                label = "운영자 메세지",
                type = "text",
                value = msg,
            ),
            AdminEditableField(
                key = "npcmode",
                label = "NPC 빙의",
                type = "select",
                value = npcMode,
                options = listOf(
                    AdminFieldOption("0", "불가"),
                    AdminFieldOption("1", "가능"),
                    AdminFieldOption("2", "선택 생성 가능"),
                ),
            ),
            AdminEditableField(
                key = "block_general_create",
                label = "장수 임의 생성",
                type = "select",
                value = blockGeneralCreate,
                options = listOf(
                    AdminFieldOption("0", "가능"),
                    AdminFieldOption("1", "불가"),
                    AdminFieldOption("2", "장수명 무작위"),
                ),
            ),
            AdminEditableField(
                key = "maxgeneral",
                label = "최대 장수",
                type = "number",
                value = intConfig(config["maxgeneral"]) ?: GameConst.defaultMaxGeneral,
            ),
            AdminEditableField(
                key = "maxnation",
                label = "최대 국가",
                type = "number",
                value = intConfig(config["maxnation"]) ?: GameConst.defaultMaxNation,
            ),
            AdminEditableField(
                key = "startyear",
                label = "시작 년도",
                type = "number",
                value = intConfig(config["startyear"]) ?: GameConst.defaultStartYear,
            ),
            AdminEditableField(
                key = "starttime",
                label = "시작 시간",
                type = "text",
                value = stringConfig(config["starttime"]) ?: "",
            ),
            AdminEditableField(
                key = "turnterm",
                label = "턴 시간(분)",
                type = "select",
                value = currentTurnTerm,
                options = TURN_OPTIONS.map { AdminFieldOption(it.toString(), "${it}분") },
            ),
        )
    }

    private companion object {
        const val ADMIN_ROLE = "ADMIN"
        const val LOG_RECENT_COUNT = 24 // PHP _admin7 *Recent($gen, 24)
        const val DEFAULT_TURN_TERM = 60
        val TURN_OPTIONS = listOf(1, 2, 5, 10, 20, 30, 60, 120)

        val GAME_SETTING_WRITES = listOf(
            AdminBlockedWrite("중원정세추가", "log_entry append intake 미포팅"),
        )

        val GENERAL_BULK_WRITES = listOf(
            AdminBlockedWrite("전체 접속허용", "PHP _admin2_submit 전체 접속허용", "allowAccessAll", true),
            AdminBlockedWrite("전체 접속제한", "PHP _admin2_submit 전체 접속제한", "denyAccessAll", true),
        )

        val GENERAL_SELECTED_WRITES = listOf(
            AdminBlockedWrite("블럭 해제", "PHP _admin2_submit 블럭 해제", "unblock", true),
            AdminBlockedWrite("1단계 블럭", "PHP _admin2_submit 1단계 블럭", "block1", true),
            AdminBlockedWrite("2단계 블럭", "PHP _admin2_submit 2단계 블럭", "block2", true),
            AdminBlockedWrite("3단계 블럭", "PHP _admin2_submit 3단계 블럭", "block3", true),
            AdminBlockedWrite("무한삭턴", "PHP _admin2_submit 무한삭턴", "infiniteKillturn", true),
            AdminBlockedWrite("강제 사망", "PHP _admin2_submit 강제 사망", "forceDeath", true),
            AdminBlockedWrite("보숙10000", "PHP _admin2_submit 보숙10000", "dex1", true),
            AdminBlockedWrite("궁숙10000", "PHP _admin2_submit 궁숙10000", "dex2", true),
            AdminBlockedWrite("기숙10000", "PHP _admin2_submit 기숙10000", "dex3", true),
            AdminBlockedWrite("귀숙10000", "PHP _admin2_submit 귀숙10000", "dex4", true),
            AdminBlockedWrite("차숙10000", "PHP _admin2_submit 차숙10000", "dex5", true),
            AdminBlockedWrite("접속 허용", "PHP _admin2_submit 접속 허용", "allowAccess", true),
            AdminBlockedWrite("접속 제한", "PHP _admin2_submit 접속 제한", "denyAccess", true),
            AdminBlockedWrite("하야입력", "PHP _admin2_submit 하야입력", "resign", true),
            AdminBlockedWrite("방랑해산", "PHP _admin2_submit 방랑해산", "wanderDismiss", true),
            AdminBlockedWrite("메세지 전달", "PHP _admin2_submit 메세지 전달", "sendMessage", true),
        )

        /** `_admin5.php:57-74` type select 옵션(verbatim, value+label). 2/11/12 결번은 legacy도 미노출. */
        val SORT_OPTIONS = listOf(
            AdminNationStatsSortOption(0, "국력"),
            AdminNationStatsSortOption(1, "장수"),
            AdminNationStatsSortOption(2, "기술"),
            AdminNationStatsSortOption(3, "국고"),
            AdminNationStatsSortOption(4, "병량"),
            AdminNationStatsSortOption(5, "평금"),
            AdminNationStatsSortOption(6, "평쌀"),
            AdminNationStatsSortOption(7, "평통"),
            AdminNationStatsSortOption(8, "평무"),
            AdminNationStatsSortOption(9, "평지"),
            AdminNationStatsSortOption(10, "평Lv"),
            AdminNationStatsSortOption(13, "보숙"),
            AdminNationStatsSortOption(14, "궁숙"),
            AdminNationStatsSortOption(15, "기숙"),
            AdminNationStatsSortOption(16, "귀숙"),
            AdminNationStatsSortOption(17, "차숙"),
        )

        /** `_admin5.php:76-82` type2 select 옵션(verbatim). */
        val SORT_OPTIONS2 = listOf(
            AdminNationStatsSortOption(0, "국력"),
            AdminNationStatsSortOption(1, "국가별성향"),
            AdminNationStatsSortOption(2, "국가성향"),
            AdminNationStatsSortOption(3, "장수성격"),
            AdminNationStatsSortOption(4, "장수특기"),
            AdminNationStatsSortOption(5, "병종수"),
            AdminNationStatsSortOption(6, "기타"),
        )

        /** `_admin7.php:15-31` queryMap 정렬 옵션(verbatim 4종, 삽입순서 = PHP array_key_first 순). */
        val SORT_OPTIONS_GENERAL = listOf(
            AdminGeneralSortOption("turntime", "최근턴"),
            AdminGeneralSortOption("recent_war", "최근전투"),
            AdminGeneralSortOption("name", "장수명"),
            AdminGeneralSortOption("warnum", "전투수"),
        )

        val QUERY_KEYS = SORT_OPTIONS_GENERAL.map { it.queryType }
    }
}
