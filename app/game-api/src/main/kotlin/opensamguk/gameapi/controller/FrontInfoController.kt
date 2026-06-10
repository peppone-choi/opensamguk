package opensamguk.gameapi.controller

import opensamguk.gameapi.dto.AutorunUserInfo
import opensamguk.gameapi.dto.CityOfficer
import opensamguk.gameapi.dto.FrontAuxInfo
import opensamguk.gameapi.dto.FrontCityInfo
import opensamguk.gameapi.dto.FrontGeneralInfo
import opensamguk.gameapi.dto.FrontGlobalInfo
import opensamguk.gameapi.dto.FrontInfoResponse
import opensamguk.gameapi.dto.FrontLastVote
import opensamguk.gameapi.dto.FrontNationInfo
import opensamguk.gameapi.dto.FrontTroopInfo
import opensamguk.gameapi.dto.FrontTroopLeader
import opensamguk.gameapi.dto.FrontTroopReservedCommand
import opensamguk.gameapi.dto.NationCrewGroup
import opensamguk.gameapi.dto.NationPopulationGroup
import opensamguk.gameapi.dto.NationTopChief
import opensamguk.gameapi.dto.NationTypeInfo
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.AuctionCountReadRepository
import opensamguk.gameapi.read.CityReadEntity
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.F4StateText
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.GeneralTurnReadRepository
import opensamguk.gameapi.read.NationReadEntity
import opensamguk.gameapi.read.NationReadRepository
import opensamguk.gameapi.read.RankDataReadRepository
import opensamguk.gameapi.read.TroopReadRepository
import opensamguk.gameapi.read.TurnTimeFormatter
import opensamguk.gameapi.read.VotePollReadEntity
import opensamguk.gameapi.read.VotePollReadRepository
import opensamguk.gameapi.read.VoteReadRepository
import opensamguk.gameapi.read.WorldStateReadEntity
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.common.constants.CityConst
import opensamguk.common.constants.GameConst
import opensamguk.common.constants.GameUnitConst
import opensamguk.common.constants.getCityLevelList
import opensamguk.logic.domestic.getBillByLevel
import opensamguk.logic.domestic.getDedLevel
import opensamguk.logic.domestic.getDedLevelText
import opensamguk.logic.traits.NationTypeModule
import opensamguk.logic.traits.NationTypeRegistry
import opensamguk.logic.world.SpecialityHelper
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * F2 Wave 1 — `GET /api/front-info` (spec §3): the per-refresh envelope the `/game` main screen renders.
 *
 * Identity resolution order (transition-friendly):
 *   1. the verified JWT principal (`@AuthenticationPrincipal userId`) → owned general via [GeneralResolver];
 *   2. else the legacy `?generalId=` query param (transition fallback per Task 6 — removed once web/game
 *      always carries the Bearer);
 *   3. else no character → `general.hasGeneral=false`, nation/city null.
 *
 * Public read (no auth required): an anonymous caller still gets `global` (the GameInfo header) and an
 * empty `general`, so the header renders before login. PHP-faithful gating fields
 * (officer_level/permission/showSecret/nation level) come from the resolved general (OQ4: the JWT does
 * not carry them). Assembled entirely from existing read repos — never a game-state write.
 *
 * W3 enrichment (PHP `GetFrontInfo`): general/nation/global DTOs are widened toward the PHP contract.
 * BLOCKED fields (no opensamguk persisted source) stay null/empty — see the §2 comments on the DTOs and
 * on [buildGlobal]/[buildGeneral]/[buildNation]; never fabricated.
 */
@RestController
@RequestMapping("/api")
class FrontInfoController(
    private val resolver: GeneralResolver,
    private val world: WorldStateReadRepository,
    private val generals: GeneralReadRepository,
    private val nations: NationReadRepository,
    private val cities: CityReadRepository,
    private val ranks: RankDataReadRepository,
    private val auctions: AuctionCountReadRepository,
    private val votePolls: VotePollReadRepository,
    // W0-2(P1-002) aux.myLastVote — vote 테이블 read.
    private val votes: VoteReadRepository,
    // W0-2(P1-005) troopInfo — troop/general_turn read.
    private val troops: TroopReadRepository,
    private val generalTurns: GeneralTurnReadRepository,
) {

    @GetMapping("/front-info")
    fun frontInfo(
        @AuthenticationPrincipal userId: Long?,
        @RequestParam(required = false) generalId: Int?,
    ): ResponseEntity<FrontInfoResponse> {
        val global = buildGlobal()

        // resolve the caller's general: principal first, then the ?generalId= transition fallback.
        val resolved = userId?.let { resolver.resolve(it) }
        val general: GeneralReadEntity? = resolved?.general
            ?: generalId?.let { generals.findById(it).orElse(null) }

        if (general == null) {
            return ResponseEntity.ok(
                FrontInfoResponse(
                    result = true,
                    global = global,
                    general = emptyGeneral(),
                    nation = null,
                    city = null,
                    recentRecord = emptyList(),
                    // 장수 미보유 — PHP aux는 장수 컨텍스트에서만 채워지므로 빈 블록.
                    aux = FrontAuxInfo(),
                ),
            )
        }

        val officerLevel = general.officerLevel
        val permission = resolved?.permission ?: GeneralResolver.derivePermission(officerLevel)
        val nationEntity: NationReadEntity? =
            if (general.nationId != 0) nations.findById(general.nationId).orElse(null) else null
        val cityEntity: CityReadEntity? =
            if (general.cityId != 0) cities.findById(general.cityId).orElse(null) else null
        val nationLevel = nationEntity?.level ?: 0

        return ResponseEntity.ok(
            FrontInfoResponse(
                result = true,
                global = global,
                general = buildGeneral(general, permission, nationLevel),
                nation = nationEntity?.let { buildNation(it) },
                city = cityEntity?.let { buildCity(it) },
                // [§2 BLOCKED] recentRecord — general_record/world_history 테이블이 모든 마이그레이션에
                // 부재(W3_FrontGlobalInfo §2). log_entry 대체 feed는 W4. 여기선 빈 리스트(날조 금지).
                recentRecord = emptyList(),
                // W0-2(P1-002) aux.myLastVote — PHP GetFrontInfo.php:578-580(내 마지막 투표 vote_id).
                aux = FrontAuxInfo(myLastVote = votes.findFirstByGeneralIdOrderByVoteIdDesc(general.id)?.voteId),
            ),
        )
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // general
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * W3 — PHP `generateGeneralInfo`의 enrich 포팅. 실 컬럼/meta 파생값 + F2 rank_data 전투통계.
     * BLOCKED 필드(dex/refresh/defence_train/autorun/reservedCommand)는 DTO 기본값(null)을 그대로 둔다.
     */
    private fun buildGeneral(g: GeneralReadEntity, permission: Int, nationLevel: Int): FrontGeneralInfo {
        // rank_data READ(F2) — 전투 통계 6종. (general_id, type) UNIQUE이므로 type별 단일 값, 미기록=0.
        val rankByType = ranks.findByGeneralId(g.id).associate { it.type to it.value }

        // meta-derived: general.meta가 explevel/dedlevel/killturn/belong/owner_name를 싣는다(LogicEntities).
        // 데몬이 안 쓴 키는 null(부재 = 미기록). intOrNull로 안전 변환, 값 날조 없음.
        val meta = g.meta
        val dedLevel = getDedLevel(g.dedication.toDouble())

        return FrontGeneralInfo(
            hasGeneral = true,
            generalId = g.id,
            name = g.name,
            nationId = g.nationId,
            officerLevel = g.officerLevel,
            permission = permission,
            showSecret = permission >= 2,
            leadership = g.leadership,
            strength = g.strength,
            intel = g.intel,
            injury = g.injury,
            gold = g.gold,
            rice = g.rice,
            crew = g.crew,
            cityId = g.cityId,

            // 이미 매핑된 컬럼.
            picture = g.picture,
            imageServer = g.imageServer,
            experience = g.experience,
            dedication = g.dedication,
            train = g.train,
            atmos = g.atmos,
            crewTypeId = g.crewTypeId,
            troop = g.troopId,
            horse = g.horseCode,
            weapon = g.weaponCode,
            book = g.bookCode,
            item = g.itemCode,
            age = g.age,

            // W3-F1 4개 컬럼.
            specialDomestic = g.specialCode,
            specialWar = g.special2Code,
            personal = g.personalCode,
            penalty = g.penalty,

            // F-fix: 코드 → 한글 이름 해석(PHP grand-truth getName 충실 포팅; None/0/미등록 → '-').
            // SpecialityHelper.domesticName/warName은 미등록 시 id를 그대로 반환하므로 "None"을 '-'로 보정.
            specialDomesticName = specialName(SpecialityHelper.domesticName(g.specialCode), g.specialCode),
            specialWarName = specialName(SpecialityHelper.warName(g.special2Code), g.special2Code),
            crewTypeName = crewTypeName(g.crewTypeId),
            personalName = GameConst.personalityNameOf(g.personalCode),
            horseName = GameConst.itemNameOf(g.horseCode),
            weaponName = GameConst.itemNameOf(g.weaponCode),
            bookName = GameConst.itemNameOf(g.bookCode),
            itemName = GameConst.itemNameOf(g.itemCode),

            // meta-derived(부재 시 null — 날조 없음).
            explevel = intOrNull(meta["explevel"]),
            dedlevel = intOrNull(meta["dedlevel"]),
            killturn = intOrNull(meta["killturn"]),
            belong = intOrNull(meta["belong"]),
            ownerName = meta["owner_name"] as? String,

            // 파생 표시값(실 컬럼/레벨에서 계산 — 패러티 포팅 재사용).
            officerLevelText = F4StateText.officerLevelText(g.officerLevel, nationLevel),
            honorText = F4StateText.honorText(g.experience),
            dedLevelText = getDedLevelText(dedLevel),
            lbonus = calcLeadershipBonus(g.officerLevel, nationLevel),
            bill = getBillByLevel(dedLevel),

            // 전투 통계(rank_data) — 미기록 type은 0.
            warnum = rankByType["warnum"] ?: 0,
            killnum = rankByType["killnum"] ?: 0,
            deathnum = rankByType["deathnum"] ?: 0,
            killcrew = rankByType["killcrew"] ?: 0,
            deathcrew = rankByType["deathcrew"] ?: 0,
            firenum = rankByType["firenum"] ?: 0,

            // W0-2(P1-005) — 부대 정보 합성(PHP GetFrontInfo.php:446-487 가드 체인 충실).
            troopInfo = buildTroopInfo(g),

            // BLOCKED(dex1-5/refreshScore*/defenceTrain/autorunLimit/reservedCommand)는 DTO 기본 null 유지.
        )
    }

    /**
     * W0-2(P1-005) — PHP GetFrontInfo.php:446-487 troopInfo 가드 체인 충실 포팅:
     *  1) `troopID = general.troop` 0이면 없음(:447-450);
     *  2) `SELECT name FROM troop WHERE nation=%i AND troop_leader=%i` 부재면 없음(:452-459);
     *  3) 부대장 행의 city(같은 국가 소속이어야 함, `SELECT city FROM general WHERE nation=%i AND no=%i`)
     *     부재/0이면 없음(:461-468);
     *  4) 부대장 예약(turn_idx < 5, ORDER BY turn_idx ASC) 0행이면 없음(:470-476).
     * 어느 단계든 끊기면 null — PHP가 `troopInfo` 키를 아예 싣지 않는 것과 동등(날조 금지).
     */
    private fun buildTroopInfo(g: GeneralReadEntity): FrontTroopInfo? {
        val troopId = g.troopId
        if (troopId == 0) return null
        val troop = troops.findById(troopId).orElse(null) ?: return null
        if (troop.nation != g.nationId) return null
        val leader = generals.findById(troopId).orElse(null) ?: return null
        if (leader.nationId != g.nationId || leader.cityId == 0) return null
        val reserved = generalTurns.findByGeneralIdOrderByTurnIdxAsc(troopId)
            .filter { it.turnIdx < 5 } // PHP `turn_idx < 5` 그대로(상한만 — 음수 정규화는 GetReservedCommand 소관).
            .map { FrontTroopReservedCommand(action = it.actionCode, arg = it.arg, brief = it.brief) }
        if (reserved.isEmpty()) return null
        return FrontTroopInfo(
            leader = FrontTroopLeader(city = leader.cityId, reservedCommand = reserved),
            name = troop.name,
        )
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // nation
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    /** W3 — PHP `generateNationInfo`의 enrich 포팅. power/gennum/집계/타입/수뇌 + meta-or-null 정책값. */
    private fun buildNation(n: NationReadEntity): FrontNationInfo {
        // F4 단일-국가 집계(도시/장수 0개면 null → {0,0,0} 보정).
        val popAgg = nations.aggregatePopulationOfNation(n.id)
        val crewAgg = nations.aggregateCrewOfNation(n.id)
        val population = NationPopulationGroup(
            cityCnt = popAgg?.cityCnt?.toInt() ?: 0,
            now = popAgg?.now?.toInt() ?: 0,
            max = popAgg?.max?.toInt() ?: 0,
        )
        val crew = NationCrewGroup(
            generalCnt = crewAgg?.generalCnt?.toInt() ?: 0,
            now = crewAgg?.now?.toInt() ?: 0,
            max = crewAgg?.max?.toInt() ?: 0,
        )

        // 국가 타입 {raw,name,pros,cons} — NationTypeRegistry 룩업. None/che_중립은 null → '-'/''.
        val typeModule = NationTypeRegistry.resolve(n.typeCode) as? NationTypeModule
        val type = if (typeModule != null) {
            // NationTypeModule.info = "{pros} {cons}"(단일 공백). pros/cons 분리는 module 정의에 없으므로
            // info 그대로 cons에 두지 않고, name만 노출 + info를 pros로(FE가 표시). raw는 type_code.
            NationTypeInfo(raw = n.typeCode, name = typeModule.typeName, pros = typeModule.info, cons = "")
        } else {
            // None/che_중립: PHP generateDummyNationInfo 동일(name='-', pros/cons='').
            NationTypeInfo(raw = n.typeCode, name = "-", pros = "", cons = "")
        }

        // topChiefs(officer_level >= 11).
        val topChiefs = generals
            .findByNationIdAndOfficerLevelGreaterThanEqualOrderByOfficerLevelDescIdAsc(n.id, 11)
            .map { NationTopChief(officerLevel = it.officerLevel, no = it.id, name = it.name, npc = it.npcState) }

        // [§2 BLOCKED — meta UNVERIFIED] bill/rate/scout/war/surlimit/strategic_cmd_limit를 meta에서
        // 방어적으로 읽되 부재 시 null(데몬 write UNVERIFIED, 시드는 infoText만 기록). 날조 없음.
        val meta = n.meta

        return FrontNationInfo(
            id = n.id,
            name = n.name,
            color = n.color,
            level = n.level,
            // 군주/군주대리 행 라벨 = 국가 레벨별 작위/직책명(레거시 NationBasicCard.vue formatOfficerLevelText(12/11, level)).
            // raw 숫자/하드코딩 '군주' 대신 PHP getOfficerLevelText 패러티 테이블로 해석(F4StateText, byte-동일).
            rulerOfficerText = F4StateText.officerLevelText(12, n.level),
            deputyOfficerText = F4StateText.officerLevelText(11, n.level),
            gold = n.gold,
            rice = n.rice,
            tech = n.tech,
            capitalCityId = n.capitalCityId,

            capital = n.capitalCityId,
            typeCode = n.typeCode,
            power = n.power, // V8 실 컬럼(아래 entity 추가 매핑)
            gennum = intOrNull(meta["gennum"]),

            population = population,
            crew = crew,
            type = type,
            topChiefs = topChiefs,

            bill = intOrNull(meta["bill"]),
            taxRate = intOrNull(meta["rate"]),
            diplomaticLimit = intOrNull(meta["surlimit"]),
            strategicCmdLimit = intOrNull(meta["strategic_cmd_limit"]),
            prohibitScout = intOrNull(meta["scout"]),
            prohibitWar = intOrNull(meta["war"]),

            // [§2 BLOCKED] impossibleStrategicCommand(명령엔진 필요)/onlineGen/notice(KV UNVERIFIED)는
            // DTO 기본값(빈 리스트/null) 유지.
        )
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // city (unchanged shape)
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    private fun buildCity(c: CityReadEntity) = FrontCityInfo(
        id = c.id,
        name = c.name,
        level = c.level,
        // 치소 등급 한글명 = getCityLevelList()[level] (수/진/관/이/소/중/대/특). raw 숫자 대신 표시(레거시
        // CityBasicCard.vue cityConstMap.level[level]). 미정의 레벨 → '-'.
        levelName = getCityLevelList()[c.level] ?: "-",
        nationId = c.nationId,
        region = c.region,
        // 지역 한글명 = CityConst.regionMap[region] (하북/중원/…/동이). raw 숫자 대신 표시. 미정의 → '-'.
        regionName = CityConst.regionMap[c.region] as? String ?: "-",
        // 도시 관직(태수4/군사3/종사2) = officer_city == 이 도시 AND officer_level IN (4,3,2) (PHP officerList).
        officers = generals.findByOfficerCityAndOfficerLevelInOrderByIdAsc(c.id, listOf(4, 3, 2))
            .map { CityOfficer(officerLevel = it.officerLevel, name = it.name, npc = it.npcState) },
        population = c.population,
        populationMax = c.populationMax,
        agriculture = c.agriculture,
        agricultureMax = c.agricultureMax,
        commerce = c.commerce,
        commerceMax = c.commerceMax,
        security = c.security,
        securityMax = c.securityMax,
        defense = c.defense,
        defenseMax = c.defenseMax,
        wall = c.wall,
        wallMax = c.wallMax,
        trust = c.trust,
        trade = c.trade,
    )

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // global
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    private fun buildGlobal(): FrontGlobalInfo {
        val w = world.findAll().firstOrNull()
        val scenario = w?.scenarioCode ?: ""
        val config = w?.config ?: emptyMap()
        val scenarioText = (config["title"] ?: w?.meta?.get("title"))?.toString()
            ?.takeIf { it.isNotBlank() } ?: scenario

        // [§2 BLOCKED — world_state.config 미기재] 아래 game_env 키는 데몬이 채우지 않으므로(현재 config는
        // startyear/starttime/turnterm만), config에서 방어적으로 읽되 부재 시 null/기본값. 날조 없음.
        val tournament = intOrNull(config["tournament"])
        val auctionCount = auctions.countByFinished(false).toInt()
        val now = Instant.now()
        val openPolls = votePolls.countOpenPolls(now)

        // W0-2(P1-002) — PHP GetFrontInfo.php:182-189,214,231. 마지막 설문 = vote_poll 최신 행
        // (game_env.lastVote 대체 정본 — countOpenPolls와 동일 규약). 만료(endDate<now)/종료 시
        // lastVote는 null이되 lastVoteID는 유지(PHP 동일: lastVoteID는 raw 키 그대로 반환).
        val latestPoll = votePolls.findFirstByOrderByIdDesc()
        val lastVote = latestPoll
            ?.takeIf { it.closedAt == null && (it.endAt == null || it.endAt!!.isAfter(now)) }
            ?.let { toFrontLastVote(it) }

        return FrontGlobalInfo(
            year = w?.currentYear ?: 0,
            month = w?.currentMonth ?: 0,
            turnterm = (w?.tickSeconds ?: 0) / 60,
            scenario = scenario,
            scenarioText = scenarioText,
            generalCount = generals.count().toInt(),
            nationCount = nations.findAll().count { it.id != 0 },
            cityCount = cities.count().toInt(),
            npcCount = generals.countByNpcStateGreaterThan(0).toInt(),

            // config 방어적 read(부재 시 null/기본).
            title = config["title"]?.toString() ?: config["scenario_text"]?.toString(),
            extendedGeneral = boolOrNull(config["extended_general"]),
            isFiction = boolOrNull(config["fiction"]),
            npcMode = intOrNull(config["npcmode"]),
            joinMode = intOrNull(config["join_mode"]),
            autorunUser = autorunUserInfo(config["autorun_user"]),
            lastExecuted = config["turntime"]?.toString(),
            develCost = intOrNull(config["develcost"]),
            noticeMsg = config["msg"]?.toString(),
            onlineUserCnt = intOrNull(config["online_user_cnt"]),
            startyear = intOrNull(config["startyear"]),
            generalCntLimit = intOrNull(config["maxgeneral"]),
            blockGeneralCreate = intOrNull(config["block_general_create"]),
            apiLimit = intOrNull(config["refreshLimit"]),
            serverCnt = intOrNull(config["server_cnt"]),
            isunited = boolOrNull(config["isunited"]),

            // 토너먼트/베팅 — tournament 정수에서 파생(부재 시 모두 null/false).
            tournamentState = tournament,
            tournamentType = intOrNull(config["tnmt_type"])?.let { F4StateText.tournamentTypeText(it) },
            isTournamentActive = tournament?.let { it > 0 },
            isTournamentApplicationOpen = tournament?.let { it == 1 },
            isBettingActive = tournament?.let { it == 6 },
            nationBetting = tournament?.let { it == 6 },

            // 설문 진행 여부(미만료 폴 존재).
            vote = openPolls > 0,

            // W0-2(P1-002) — 마지막 설문 id + 진행중 설문 상세(vote_poll 실원천).
            lastVoteID = latestPoll?.id,
            lastVote = lastVote,

            // [§2 W0-2(P1-001)] onlineNations — game_env.online_nation(접속중 국가명 CSV,
            // func.php:1247). 데몬 미기재 → config 방어적 read, 부재 시 null(날조 금지).
            onlineNations = config["online_nation"]?.toString(),

            // COUNT 집계.
            createdUserCnt = generals.countByNpcState(0).toInt(),
            createdNPCCnt = generals.countByNpcStateGreaterThan(0).toInt(),
            auctionCount = auctionCount,

            // [§2 BLOCKED — plock 테이블 부재] interim false(W3_FrontGlobalInfo §2). 마이그레이션 추가 시 교체.
            serverLocked = false,
        )
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // helpers
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * calcLeadershipBonus(officerLevel, nationLevel) — func_process.php:52-61 (OfficerLevelModule와 동일):
     * officer_level==12 → nationLevel*2 ; >=5 → nationLevel ; else 0.
     */
    private fun calcLeadershipBonus(officerLevel: Int, nationLevel: Int): Int = when {
        officerLevel == 12 -> nationLevel * 2
        officerLevel >= 5 -> nationLevel
        else -> 0
    }

    /**
     * W0-2(P1-002) — vote_poll 행 → PHP `VoteInfo->toArray()` 동형(FrontLastVote).
     * startDate/endDate는 PHP 'Y-m-d H:i:s' 문자열 규약(TurnTimeFormatter.full 슬라이스),
     * options는 삽입순 텍스트(PHP array_values — VoteController optionTexts와 동식).
     */
    private fun toFrontLastVote(p: VotePollReadEntity): FrontLastVote = FrontLastVote(
        id = p.id,
        title = p.title,
        multipleOptions = p.multipleOptions,
        opener = p.openerName.takeIf { it.isNotBlank() },
        startDate = TurnTimeFormatter.full(p.startAt),
        endDate = TurnTimeFormatter.full(p.endAt),
        options = p.options.entries.map { e -> e.value?.toString() ?: e.key },
    )

    /** jsonb 값(Number/String)을 Int로 안전 변환. 부재/형변환 실패 시 null(날조 없음). */
    private fun intOrNull(v: Any?): Int? = when (v) {
        is Number -> v.toInt()
        is String -> v.toIntOrNull()
        else -> null
    }

    private fun autorunUserInfo(v: Any?): AutorunUserInfo? {
        val m = v as? Map<*, *> ?: return null
        val limitMinutes = intOrNull(m["limit_minutes"]) ?: return null
        val options = (m["options"] as? Map<*, *>)
            ?.mapNotNull { (key, value) ->
                val option = key as? String ?: return@mapNotNull null
                val weight = intOrNull(value) ?: return@mapNotNull null
                option to weight
            }
            ?.toMap()
            ?: emptyMap()
        return AutorunUserInfo(limitMinutes = limitMinutes, options = options)
    }

    /** jsonb 값(Boolean/Number 0/1/String)을 Boolean으로 안전 변환. 부재 시 null. */
    private fun boolOrNull(v: Any?): Boolean? = when (v) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        is String -> v == "1" || v.equals("true", ignoreCase = true)
        else -> null
    }

    /**
     * 특기 표시 이름 보정. SpecialityHelper.domesticName/warName은 미등록 코드를 그대로 반환하므로,
     * code가 "None"/공백이거나 resolved가 code와 동일(=미해석)하면 '-'로 보정한다(PHP None.php `$name`='-').
     */
    private fun specialName(resolved: String, code: String): String =
        if (code.isBlank() || code == "None") "-" else resolved

    /**
     * 병종 표시 이름. GameUnitConst.byId(id)?.name (= GameUnitConst::all()[id]->name). 미장착(id<1000,
     * 예: 0)이면 '-'(byId는 id<1000에서 require throw하므로 1000 미만은 조회 자체를 건너뛴다).
     */
    private fun crewTypeName(crewTypeId: Int): String =
        if (crewTypeId < 1000) "-" else GameUnitConst.byId(crewTypeId)?.name ?: "-"

    private fun emptyGeneral() = FrontGeneralInfo(
        hasGeneral = false,
        generalId = null,
        name = null,
        nationId = 0,
        officerLevel = 0,
        permission = 0,
        showSecret = false,
        leadership = 0,
        strength = 0,
        intel = 0,
        injury = 0,
        gold = 0,
        rice = 0,
        crew = 0,
        cityId = 0,
    )
}
