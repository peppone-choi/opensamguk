package opensamguk.gameapi.dto

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * F2 Wave 1 DTOs — the read contracts web/game Wave 2 builds against. Field names are STABLE; serialized
 * with Jackson default (camelCase). All identity endpoints resolve the caller's general from the verified
 * JWT principal; the 404/empty shapes for "no character" are documented per-endpoint on the controllers.
 */

// ── possession (장수 점유) ────────────────────────────────────────────────────

/** A claimable (unowned NPC) candidate for GET /api/generals/claimable. */
data class ClaimableGeneral(
    val generalId: Int,
    val name: String,
    val nationId: Int,
    val nationName: String?,
    val leadership: Int,
    val strength: Int,
    val intel: Int,
    val picture: String?,
    val imageServer: Int,
    val special: String?,       // 내정특기명
    val special2: String?,      // 전투특기명
    val personal: String?,      // 성격명
    val keepCnt: Int? = null,   // legacy select_npc token keep counter
)

/** GET /api/generals/claimable body. */
data class ClaimableResponse(
    val result: Boolean,
    val hasGeneral: Boolean,
    val candidates: List<ClaimableGeneral>,
    val validUntil: String? = null,
    val pickMoreFrom: String? = null,
    val pickMoreSeconds: Int? = null,
)

/** POST /api/general/claim body (and response). */
data class ClaimRequest(val generalId: Int)

data class ClaimResponse(
    val result: Boolean,
    val generalId: Int?,
    val reason: String?,
)

// ── front-info (§3 GameInfo + identity envelope) ─────────────────────────────

data class FrontGlobalInfo(
    val year: Int,
    val month: Int,
    val turnterm: Int,
    val scenario: String,
    val scenarioText: String,
    val generalCount: Int,
    val nationCount: Int,
    val cityCount: Int,
    val npcCount: Int,

    // ── W3 FrontGlobalInfo enrichment (PHP GetFrontInfo::generateGlobalInfo) ──────────────────────
    // 모두 nullable/기본값 — FE 계약상 optional이라 미존재 시 누락해도 헤더가 깨지지 않는다.
    //
    // [§2 BLOCKED — world_state.config 미기재] 아래 game_env 키들은 PHP가 KVStorage('game_env')에서
    // 읽지만, opensamguk 엔진은 현재 world_state.config에 `startyear/starttime/turnterm`만 기록한다
    // (ScenarioImporter.insertWorldState, DatabaseHooks.toFlushPayload). 나머지 game_env 키는
    // 데몬이 채우지 않으므로 source가 없다. 따라서 config에서 **방어적으로 읽되**(데몬이 향후 채우면
    // 그대로 노출), 부재 시 null/기본값으로 둔다 — 값을 날조하지 않는다(W3_PLAN §5 faithful-port).
    val title: String? = null,
    val extendedGeneral: Boolean? = null,
    val isFiction: Boolean? = null,
    val npcMode: Int? = null,
    val joinMode: Int? = null,
    val autorunUser: AutorunUserInfo? = null,
    val lastExecuted: String? = null, // game_env.turntime(마지막 턴 실행 시각 문자열)
    val develCost: Int? = null,
    val noticeMsg: String? = null,
    val onlineUserCnt: Int? = null,
    val startyear: Int? = null,
    val generalCntLimit: Int? = null, // game_env.maxgeneral
    val blockGeneralCreate: Int? = null,
    val apiLimit: Int? = null, // game_env.refreshLimit
    val serverCnt: Int? = null,
    val isunited: Boolean? = null,

    // 토너먼트/베팅 상태(game_env.tournament/tnmt_type) — config 미기재 시 null.
    val tournamentState: Int? = null,
    val tournamentType: String? = null,
    val isTournamentActive: Boolean? = null, // tournament > 0
    val isTournamentApplicationOpen: Boolean? = null, // tournament == 1
    val isBettingActive: Boolean? = null, // tournament == 6
    val nationBetting: Boolean? = null, // 천통국 베팅 하이라이트(isBettingActive와 동치 — PHP 계산 게이트)

    // 설문(투표) 진행 여부 — vote_poll에서 미만료 폴 존재 시 true(아래 컨트롤러에서 계산).
    val vote: Boolean? = null,

    // ── W0-2(P1-002) 설문 셀 배선 — PHP GetFrontInfo.php:165,174(lastVote 키)·182-189(만료 검사)·
    // 214('lastVoteID')·231('lastVote' = VoteInfo->toArray()) ───────────────────────────────────────
    // PHP는 game_env.lastVote(마지막 설문 id)로 vote KV에서 VoteInfo를 읽고, endDate가 지났으면 null로
    // 둔다. opensamguk은 game_env.lastVote write가 없는 대신 설문 정본이 vote_poll 테이블이므로
    // (VoteController와 동일 원천 — countOpenPolls 주석의 기존 대체 규약) 최신 폴 행에서 채운다. 실원천.
    /** 마지막 설문 id(PHP `lastVoteID`). vote_poll 최신 행 id. 폴 0행이면 null. */
    val lastVoteID: Int? = null,
    /** 진행중인 마지막 설문(PHP `lastVote` = VoteInfo->toArray()). 만료/종료 시 null(PHP 동일). */
    val lastVote: FrontLastVote? = null,

    // [§2 W0-2(P1-001) — world_state.config 미기재] onlineNations: PHP game_env.online_nation
    // (func.php:1247 `join(', ', $onlineNation)` 접속중 국가명 CSV 문자열; GetFrontInfo.php:175,217).
    // 데몬이 이 키를 아직 채우지 않으므로 config에서 방어적으로 읽고 부재 시 null(날조 금지).
    val onlineNations: String? = null,

    // COUNT 집계(저렴한 인덱스 카운트) — npc_state로 user/NPC 분리.
    val createdUserCnt: Int? = null, // npc_state == 0 (사용자 점유 가능 장수)
    val createdNPCCnt: Int? = null, // npc_state > 0 (NPC 장수)

    // ng_auction.finished = 0 진행중 경매 수(아래 AuctionCountReadRepository).
    val auctionCount: Int? = null,

    // [§2 BLOCKED — plock 테이블 부재] PHP `SELECT plock FROM plock WHERE type='GAME'`.
    // 모든 마이그레이션(V1~V10)에 plock 테이블이 없다. interim으로 false 고정(W3_FrontGlobalInfo §2).
    val serverLocked: Boolean? = null,
)

data class AutorunUserInfo(
    @JsonProperty("limit_minutes")
    val limitMinutes: Int,
    val options: Map<String, Int>,
)

/**
 * W0-2(P1-002) — PHP `VoteInfo`(sammo/DTO/VoteInfo.php:5-20) → `toArray()` 동형:
 * `{id, title, multipleOptions, opener, startDate, endDate, options}`.
 * opensamguk 원천은 vote_poll 행(VoteController와 동일): startDate/endDate는 PHP 'Y-m-d H:i:s'
 * 문자열 규약([opensamguk.gameapi.read.TurnTimeFormatter.full] 슬라이스), options는 삽입순 텍스트
 * (PHP `array_values` — VoteController optionTexts와 동식).
 */
data class FrontLastVote(
    val id: Int,
    val title: String,
    val multipleOptions: Int,
    val opener: String?,
    val startDate: String?,
    val endDate: String?,
    val options: List<String>,
)

/**
 * W0-2(P1-002) — PHP GetFrontInfo.php:573-591 `aux` 블록. 현재 키는 `myLastVote`
 * (`SELECT vote_id FROM vote WHERE general_id=%i ORDER BY vote_id DESC LIMIT 1`) 하나.
 * 장수 미보유/투표 이력 없음 → null(PHP는 키 자체 미기재).
 */
data class FrontAuxInfo(
    val myLastVote: Int? = null,
)

/** The caller's general gating surface (null when no character). */
data class FrontGeneralInfo(
    val hasGeneral: Boolean,
    val generalId: Int?,
    val name: String?,
    val nationId: Int,
    val officerLevel: Int,
    val permission: Int,
    val showSecret: Boolean,
    val leadership: Int,
    val strength: Int,
    val intel: Int,
    val injury: Int,
    val gold: Int,
    val rice: Int,
    val crew: Int,
    val cityId: Int,

    // ── W3 FrontGeneralInfo enrichment (PHP GetFrontInfo::generateGeneralInfo) ───────────────────
    // 모두 nullable/기본값 — FE 계약상 optional. 빈 general(미점유) 셸에서는 전부 기본값으로 둔다.

    // 이미 GeneralReadEntity에 매핑된 V1 컬럼을 그대로 노출.
    val picture: String? = null,
    val imageServer: Int? = null,
    val experience: Int? = null,
    val dedication: Int? = null,
    val train: Int? = null,
    val atmos: Int? = null,
    val crewTypeId: Int? = null,
    val troop: Int? = null, // troop_id
    val horse: String? = null, // horse_code
    val weapon: String? = null, // weapon_code
    val book: String? = null, // book_code
    val item: String? = null, // item_code
    val age: Int? = null, // V1 general.age (실 컬럼)

    // W3-F1 4개 기존 컬럼 매핑(special_code/special2_code/personal_code/penalty).
    val specialDomestic: String? = null, // special_code(내정 특기)
    val specialWar: String? = null, // special2_code(전투 특기)
    val personal: String? = null, // personal_code(성격)
    val penalty: Map<String, Any?>? = null, // penalty jsonb

    // ── F-fix: 코드 → 한글 이름 해석값(raw 코드는 위에서 그대로 유지, 이름은 ADD) ──────────────────
    // PHP는 GetFrontInfo에서 raw 코드만 내려보내고 이름 해석을 Vue(GameConstStore)에서 했다.
    // web/game(Next)은 그 해석을 포팅하지 않으므로 API가 PHP grand-truth 이름으로 해석해 함께 내려준다.
    //  - special*Name : SpecialityHelper.domesticName/warName (= buildGeneralSpecial*Class->getName()). None→'-'.
    //  - crewTypeName : GameUnitConst.byId(id)?.name (= GameUnitConst::all()[id]->name). 0/none→'-'.
    //  - personalName : GameConst.personalityNameOf (= buildPersonalityClass->getName()). None/미등록→'-'.
    //  - horse/weapon/book/itemName : GameConst.itemNameOf (= buildItemClass->getName()). None→'-'.
    val specialDomesticName: String? = null,
    val specialWarName: String? = null,
    val crewTypeName: String? = null,
    val personalName: String? = null,
    val horseName: String? = null,
    val weaponName: String? = null,
    val bookName: String? = null,
    val itemName: String? = null,

    // meta-derived(general.meta가 explevel/dedlevel/killturn/belong/owner_name를 싣는다 — LogicEntities 주석).
    // 데몬이 안 쓴 키는 null(부재 = 미기록). 값 날조 없음.
    val explevel: Int? = null,
    val dedlevel: Int? = null,
    val killturn: Int? = null,
    val belong: Int? = null,
    val ownerName: String? = null,

    // 파생 표시값(실 컬럼/레벨에서 계산 — F4StateText / DomesticHelpers / getHonor 패러티 포팅 재사용).
    val officerLevelText: String? = null, // getOfficerLevelText(officer_level, nationLevel)
    val honorText: String? = null, // getHonor(experience)
    val dedLevelText: String? = null, // getDed(dedication)
    val lbonus: Int? = null, // calcLeadershipBonus(officer_level, nationLevel)
    val bill: Int? = null, // getBillByLevel(getDedLevel(dedication))

    // 전투 통계 — F2 RankDataReadRepository(rank_data READ 경로)로 채운다. 미기록 type은 0.
    val warnum: Int? = null,
    val killnum: Int? = null,
    val deathnum: Int? = null,
    val killcrew: Int? = null,
    val deathcrew: Int? = null,
    val firenum: Int? = null,

    // [§2 BLOCKED — opensamguk source 부재] 아래는 의도적으로 null/omit. 컨트롤러가 절대 채우지 않는다.
    //  - dex1..dex5      : V1/V6 어디에도 컬럼 없음, meta 키도 없음(W3_FrontGeneralInfo §2). null.
    //  - refreshScore / refreshScoreTotal : general_access_log 테이블 부재(W3_PLAN §2). null.
    //  - defence_train   : 컬럼 없음(W3_PLAN §2). null.
    //  - autorunLimit    : aux 컬럼 없음(W3_PLAN §2 defence_train/autorun_limit). null.
    //  - reservedCommand : general_turn READ는 GeneralList 그룹/W4로 분리(이 그룹 OUT-OF-SCOPE). null.
    //  - recentWar       : recent_war_time은 컬럼이나 PHP `recent_war` 문자열 포맷은 W4 후속. null.
    val dex1: Int? = null,
    val dex2: Int? = null,
    val dex3: Int? = null,
    val dex4: Int? = null,
    val dex5: Int? = null,
    val refreshScore: Int? = null,
    val refreshScoreTotal: Int? = null,
    val defenceTrain: Int? = null,
    val autorunLimit: Int? = null,
    val reservedCommand: List<Map<String, Any?>>? = null,

    // ── W0-2(P1-005) troopInfo — PHP GetFrontInfo.php:446-487(부대장 도시·예약 5턴·부대명 합성) ───────
    // troop/general/general_turn 실 행에서 합성. PHP 가드 체인(부대 미소속/부대 행 부재/부대장 도시
    // 부재/예약 0행 → 키 자체 미기재 = null) 충실 — 어느 단계든 끊기면 null(날조 금지).
    val troopInfo: FrontTroopInfo? = null,
)

/**
 * W0-2(P1-005) — PHP GetFrontInfo.php:478-484 `troopInfo` 블록:
 * `{leader: {city, reservedCommand}, name}`. name = troop.name(GetFrontInfo.php:452-456),
 * leader.city = 부대장 general.city(:461-465), reservedCommand = 부대장 general_turn turn_idx<5
 * (:470-473).
 */
data class FrontTroopInfo(
    val leader: FrontTroopLeader,
    val name: String,
)

/** 부대장 정보(PHP `troopInfo.leader`) — 소재 도시 + 앞 5턴 예약. */
data class FrontTroopLeader(
    val city: Int,
    val reservedCommand: List<FrontTroopReservedCommand>,
)

/**
 * 부대장 예약 1행(PHP `SELECT action, arg, brief FROM general_turn ... turn_idx < 5`).
 * PHP는 arg를 raw JSON 문자열로 내려보내지만 본 read 경로는 기존 ReservedSlot.arg와 동일하게
 * 디코드된 맵(삽입순서 보존)으로 통일한다(FE 이중 파싱 방지 — 정보 동등, 표기만 구조화).
 */
data class FrontTroopReservedCommand(
    val action: String,
    val arg: Map<String, Any?>,
    val brief: String,
)

/**
 * W3 — `population.{cityCnt,now,max}` / `crew.{generalCnt,now,max}` grouped 집계(F4) 결과 묶음.
 * 도시/장수가 0개인 국가는 {0,0,0}으로 보정해 노출한다(PHP `generateNationInfo` 동일).
 */
data class NationPopulationGroup(
    val cityCnt: Int,
    val now: Int,
    val max: Int,
)

data class NationCrewGroup(
    val generalCnt: Int,
    val now: Int,
    val max: Int,
)

/** 국가 타입 표시(PHP `type.{raw,name,pros,cons}`) — NationTypeRegistry/NationTypeModule에서 룩업. */
data class NationTypeInfo(
    val raw: String,
    val name: String,
    val pros: String,
    val cons: String,
)

/** topChiefs 1개 항목(PHP officer_level>=11 장수: officer_level/no/name/npc). */
data class NationTopChief(
    val officerLevel: Int,
    val no: Int,
    val name: String,
    val npc: Int,
)

data class FrontNationInfo(
    val id: Int,
    val name: String,
    val color: String,
    val level: Int,
    // 군주/군주대리 행 라벨(작위/직책 한글명) — F4StateText.officerLevelText(12/11, level), PHP getOfficerLevelText
    // byte-동일. 레거시 NationBasicCard.vue formatOfficerLevelText(12/11, nation.level)와 동치. 날조 아님.
    val rulerOfficerText: String? = null,
    val deputyOfficerText: String? = null,
    val gold: Int,
    val rice: Int,
    val tech: Double,
    val capitalCityId: Int?,

    // ── W3 FrontNationInfo enrichment (PHP GetFrontInfo::generateNationInfo) ─────────────────────
    val capital: Int? = null, // PHP `capital`(=capital_city_id 별칭, FE 호환)
    val typeCode: String? = null, // nation.type_code(raw)
    val power: Int? = null, // V8 nation.power(실 컬럼)
    val gennum: Int? = null, // meta.gennum

    val population: NationPopulationGroup? = null, // F4 인구 집계
    val crew: NationCrewGroup? = null, // F4 병력 집계
    val type: NationTypeInfo? = null, // 국가타입 {raw,name,pros,cons}
    val topChiefs: List<NationTopChief>? = null, // officer_level>=11 수뇌 목록

    // [§2 BLOCKED — nation.meta UNVERIFIED] PHP는 nation 행의 dedicated 컬럼에서 읽지만 opensamguk엔
    // 컬럼이 없고 meta에 싣는다(LogicEntities Nation 주석: rate/bill/surlimit/strategicCmdLimit). 그러나
    // 시드(ScenarioImporter)는 nation.meta에 infoText만 기록하고 엔진의 이들 키 write는 UNVERIFIED
    // (W3_FrontNationInfo §2). 따라서 meta에서 **방어적으로 읽되**(데몬이 채우면 노출), 부재 시 null.
    // 값 날조 없음.
    val bill: Int? = null,
    val taxRate: Int? = null, // meta.rate
    val diplomaticLimit: Int? = null, // meta.surlimit
    val strategicCmdLimit: Int? = null, // meta.strategic_cmd_limit
    val prohibitScout: Int? = null, // meta.scout
    val prohibitWar: Int? = null, // meta.war

    // [§2 BLOCKED — 계산 불가] impossibleStrategicCommand는 PHP가 명령 클래스의 getNextAvailableTurn()을
    // LastTurn 상태로 평가해 산출한다(buildNationCommandClass + LastTurn). read-only game-api에는 그
    // 명령 실행 엔진 시드/컨텍스트가 없어 패러티 재현이 불가 → 빈 리스트로 두고 W4(명령 precheck 결합)로
    // 미룬다(값 날조 금지, W3_FrontNationInfo §2). FE는 빈 리스트를 "제한 없음"으로 렌더.
    val impossibleStrategicCommand: List<List<Any?>> = emptyList(),

    // [§2 BLOCKED] onlineGen(game_env.online_genenerals) / notice(nation_env.nationNotice)는 데몬 KV
    // population이 UNVERIFIED(W3_FrontNationInfo §2). null로 둔다.
    val onlineGen: Int? = null,
    val notice: String? = null,
)

data class FrontCityInfo(
    val id: Int,
    val name: String,
    val level: Int,
    // 치소 등급 한글명 = getCityLevelList()[level] (수/진/관/이/소/중/대/특). raw 숫자 대신 표시. 미정의 → '-'.
    val levelName: String? = null,
    val nationId: Int,
    val region: Int,
    // 지역 한글명 = CityConst.regionMap[region] (하북/중원/서북/서촉/남중/초/오월/동이). raw 숫자 대신 표시. 미정의 → '-'.
    val regionName: String? = null,
    // 도시 관직 = officer_city == this.id AND officer_level∈{4태수,3군사,2종사} (PHP officerList). 빈 슬롯은 미포함.
    val officers: List<CityOfficer> = emptyList(),
    val population: Int,
    val populationMax: Int,
    val agriculture: Int,
    val agricultureMax: Int,
    val commerce: Int,
    val commerceMax: Int,
    val security: Int,
    val securityMax: Int,
    val defense: Int,
    val defenseMax: Int,
    val wall: Int,
    val wallMax: Int,
    val trust: Double,
    val trade: Int?,
)

/**
 * 도시 관직 1명 (PHP CityBasicCard officerList[4/3/2]). officerLevel 4=태수/3=군사/2=종사.
 * npc = npc_state(이름 색상 getNPCColor 입력). 빈 슬롯은 리스트에 미포함(FE가 officerLevel로 조회).
 */
data class CityOfficer(
    val officerLevel: Int,
    val name: String,
    val npc: Int,
)

/** GET /api/front-info body. `general.hasGeneral=false` (others null) when the caller has no character. */
data class FrontInfoResponse(
    val result: Boolean,
    val global: FrontGlobalInfo,
    val general: FrontGeneralInfo,
    val nation: FrontNationInfo?,
    val city: FrontCityInfo?,
    val recentRecord: List<String>,
    /** W0-2(P1-002) — PHP GetFrontInfo.php:591 `aux` 블록(myLastVote). 장수 미보유 시 빈 블록. */
    val aux: FrontAuxInfo = FrontAuxInfo(),
)

// ── my-* read endpoints ──────────────────────────────────────────────────────

/** GET /api/my-page — the caller's general detail (404 when no character). */
data class MyPageResponse(
    val generalId: Int,
    val name: String,
    val nationId: Int,
    val nationName: String?,
    val cityId: Int,
    val cityName: String?,
    val officerLevel: Int,
    val permission: Int,
    val leadership: Int,
    val strength: Int,
    val intel: Int,
    val injury: Int,
    val experience: Int,
    val dedication: Int,
    val gold: Int,
    val rice: Int,
    val crew: Int,
    val train: Int,
    val atmos: Int,
    val picture: String?,
    val imageServer: Int,
)

/**
 * GET /api/my-generals — generals in the caller's nation (the caller is always included).
 * PHP `hwe/b_myGenInfo.php`(세력장수, fid 25) 15컬럼 행: 얼굴/이름/관직/계급/명성/봉록/통솔/무력/지력/
 * 자금/군량/성격/특기2/사관/벌점. a_genList(전체 장수)와 쌍이며 자금·군량·봉록·사관(belong)이 추가다.
 * raw 코드는 그대로 두고, 한글 해석값(관직/계급/성격/특기명)은 이미 이식된 헬퍼만 재사용해 ADD(날조 아님).
 */
data class MyGeneralSummary(
    val generalId: Int,
    val name: String,
    val cityId: Int,
    val officerLevel: Int,
    val leadership: Int,
    val strength: Int,
    val intel: Int,
    val crew: Int,
    val npcState: Int,
    /** True iff this is the caller's own general. Named `mine` (not `isMe`) so Jackson keeps the field name. */
    val mine: Boolean,

    // ── b_myGenInfo 15컬럼 보강(C3①) ──────────────────────────────────────────────────────────────
    /** 장수 초상 파일명(PHP `picture`). "얼 굴" 컬럼 이미지 src. */
    val picture: String? = null,
    /** 초상 이미지 서버 번호(PHP `imgsvr`→GetImageURL). FE CDN base 합성. */
    val imageServer: Int = 0,
    /** 관직 한글명 = getOfficerLevelText(officer_level, nationLevel) (PHP). "관 직" 컬럼. */
    val officerLevelText: String = "",
    /** 계급 한글명 = getDedLevelText(getDedLevel(dedication)) (PHP getDed). "계 급" 컬럼. */
    val dedLevelText: String = "",
    /** 명성 칭호 = getHonor(experience) (PHP). "명 성" 컬럼. */
    val honorText: String = "",
    /** 봉록 = getBill(dedication) = getBillByLevel(getDedLevel(dedication)) (PHP). "봉 록" 컬럼. */
    val bill: Int = 0,
    /** 자금(PHP `gold`). "자 금" 컬럼. */
    val gold: Int = 0,
    /** 군량(PHP `rice`). "군 량" 컬럼. */
    val rice: Int = 0,
    /** 성격 한글명 = personalityNameOf(personal_code) (PHP displayCharInfo). "성 격" 컬럼. */
    val personalText: String = "",
    /** 내정 특기명 = SpecialityHelper.domesticName(special_code) (PHP displaySpecialDomesticInfo). "특 기"(앞). None→"-". */
    val specialDomesticText: String = "",
    /** 전투 특기명 = SpecialityHelper.warName(special2_code) (PHP displaySpecialWarInfo). "특 기"(뒤). None→"-". */
    val specialWarText: String = "",
    /** 사관(belong) = 입사 경과 턴 수(PHP `belong`). "사 관" 컬럼. */
    val belong: Int = 0,
    /** 부상률(0~100, PHP `injury`). >0이면 통/무/지 감산·적색 표시(FE). */
    val injury: Int = 0,
    /** 통솔보너스 = calcLeadershipBonus(officer_level, nationLevel) (PHP). >0이면 통솔에 "+{lbonus}"(cyan). */
    val lbonus: Int = 0,

    // ── W0-2(P1-071/072/075) raw 정렬 키 — PHP b_myGenInfo.php:90-110 ────────────────────────────────
    // PHP 정렬은 raw 컬럼 DESC(2계급=dedication/3명성=experience/11성격=personal/12내특=special/
    // 13전특=special2). 한글 해석값(localeCompare)이 아닌 raw 원값을 배출해 FE가 PHP usort를 재현한다.
    /** raw 공헌도(PHP `dedication`) — 계급(type 2) 정렬 키. */
    val dedication: Int = 0,
    /** raw 경험치(PHP `experience`) — 명성(type 3) 정렬 키. */
    val experience: Int = 0,
    /** raw 성격 코드(PHP `personal`, 예: che_정복) — 성격(type 11) 정렬 키. */
    val personal: String? = null,
    /** raw 내정 특기 코드(PHP `special`) — 내특(type 12) 정렬 키. */
    val special: String? = null,
    /** raw 전투 특기 코드(PHP `special2`) — 전특(type 13) 정렬 키. */
    val special2: String? = null,
    /**
     * W0-2(P1-073/075) isunited 시 소유 플레이어명(PHP b_myGenInfo.php:31-36 member 테이블 →
     * :155-157 '(ownerName)'). opensamguk은 general.meta.owner_name이 문서화된 동등 키
     * (FrontGeneralInfo.ownerName 동일 원천) — 방어적 read, 부재 시 null(날조 금지).
     */
    val ownerName: String? = null,
    // [§2 BLOCKED — general_access_log 부재, P1-069/P1-075] 벌점(refresh_score_total)은 PHP가 LEFT JOIN
    // general_access_log에서 읽는다(b_myGenInfo.php:109-110). 테이블이 opensamguk 스키마에 없어(P8 미이식)
    // read 원천이 없다 → 벌점 컬럼/정렬(type=10) 모두 노출하지 않는다(날조 금지).
)

data class MyGeneralsResponse(
    val result: Boolean,
    val nationId: Int,
    val generals: List<MyGeneralSummary>,
)

/**
 * GET /api/my-cities — cities owned by the caller's nation (PHP `hwe/b_myCityInfo.php` 세력도시, fid 22).
 * 평면 9컬럼 표가 아니라 도시당 카드(5행)로 렌더된다. 각 행은 실제 city 컬럼에서 직접 읽고, 도시당
 * 21 데이터포인트(주민/인구율/농상치수성 5종 cur·max/민심/시세/태수·군사·종사/장수CSV)를 모두 노출한다.
 * 자금/군량/둔전 "수입" 3종은 §2 BLOCKED(아래 주석) — read-only game-api에 nation-type income 파이프라인이
 * 조립돼 있지 않아(precheck는 identity-fold 빈 파이프라인) 비-중립 국가타입에서 PHP와 silently diverge하므로
 * 날조하지 않고 누락한다. FE는 부재 필드를 "-"로 렌더한다.
 */
data class MyCitySummary(
    val cityId: Int,
    val name: String,
    val level: Int,
    /** 치소 등급 한글명 = CityConst.levelMap[level] (수/진/관/이/소/중/대/특). 헤더 【지역 | 등급】. */
    val levelText: String = "",
    val region: Int,
    /** 지역 한글명 = CityConst.regionMap[region] (하북/중원/…/동이). 헤더 【지역 | 등급】. */
    val regionText: String = "",
    /** 수도 여부(nation.capital_city_id == this.id). true면 도시명 cyan 강조(PHP `<font color=cyan>[name]</font>`).
     * Jackson Kotlin 모듈이 Boolean `is`-프로퍼티 접두사를 떼어 `capital`로 직렬화하므로(F4Dto §isChief 동일)
     * FE/테스트 계약 키 `isCapital`을 @get:JsonProperty로 고정한다. */
    @get:JsonProperty("isCapital")
    val isCapital: Boolean = false,
    val population: Int,
    val populationMax: Int,
    // ── 농업/상업/치안/수비/성벽 5종 cur/max(PHP `$city['agri']/$city['agri_max']` …) ──────────────
    val agriculture: Int = 0,
    val agricultureMax: Int = 0,
    val commerce: Int = 0,
    val commerceMax: Int = 0,
    val security: Int = 0,
    val securityMax: Int = 0,
    val defense: Int,
    val defenseMax: Int = 0,
    val wall: Int,
    val wallMax: Int = 0,
    /** 민심(PHP `round($city['trust'], 1)` — 소수1자리). FE가 toFixed(1)로 렌더. */
    val trust: Double = 0.0,
    /** 시세(PHP `$city['trade']%`; null이면 FE가 "- " verbatim). nullable 유지 — 날조 금지. */
    val trade: Int? = null,
    // ── 관직자(PHP officerName[4/3/2] = formatName, 공석 "-"). npc 색상은 FE getNPCColor. ──────────
    /** 태수(officer_level 4, officer_city == this.id). 공석이면 null → FE "-". */
    val governorName: String? = null,
    val governorNpc: Int = 0,
    /** 군사(officer_level 3). 공석이면 null. */
    val strategistName: String? = null,
    val strategistNpc: Int = 0,
    /** 종사(officer_level 2). 공석이면 null. */
    val secretaryName: String? = null,
    val secretaryNpc: Int = 0,
    /** 도시 소재 장수 일람(PHP `cityGeneralList[cityID]` = formatName CSV, npc 색상). 없으면 빈 리스트 → FE "-". */
    val generals: List<MyCityGeneralName> = emptyList(),
    // [§2 BLOCKED — nation-type income 파이프라인 미조립] 자금/군량/둔전 수입 3종(PHP
    // calcCityGoldIncome/calcCityRiceIncome/calcCityWallRiceIncome × rate/20)은 read game-api에서
    // 산출하지 않는다. 비-중립 nationType의 onCalcNationalIncome fold가 PHP와 달라져(precheck는
    // identity-fold 빈 파이프라인) byte-parity가 깨지므로 날조 금지로 누락한다(MyKingdomInfo income과 동일).
)

/** 도시 소재 장수 1명(PHP formatName: 이름 + npc 색상). */
data class MyCityGeneralName(
    val name: String,
    val npc: Int,
)

data class MyCitiesResponse(
    val result: Boolean,
    val nationId: Int,
    val cities: List<MyCitySummary>,
)

/** GET /api/my-boss — the ruler (officer_level 12) of the caller's nation (인사부). */
data class MyBossResponse(
    val result: Boolean,
    val nationId: Int,
    val hasBoss: Boolean,
    val bossGeneralId: Int?,
    val bossName: String?,
    val bossOfficerLevel: Int?,
)

/**
 * GET /api/my-nation-detail — the caller's nation (PHP `hwe/b_myKingdomInfo.php` 세력정보, fid 22).
 *
 * 계약 버그 수정: 기존 FE는 {nation, generals, cities}를 가정했으나 BE는 {result, hasNation, nation:
 * FrontNationInfo, cityCount, generalCount}만 내려줬다 → 양쪽 표 공백 + pop/genNum/power undefined.
 * legacy엔 장수표·도시표가 없다 — 19필드 단일표(8열) 한 장이다. 그 19필드를 정확히 이 DTO로 옮긴다.
 *
 * 실 컬럼/집계에서 직접 산출(정상): 총주민(currPop/maxPop), 총병사(currCrew/maxCrew), 국력(power), 국고(gold),
 * 병량(rice), 속령수(cityCount), 장수수(gennum), 기술력(floor tech), 작위(nationLevelNameOf), 속령일람.
 *
 * [§2 BLOCKED — 날조 금지로 nullable] 세율(rate)/지급률(bill)은 nation.meta UNVERIFIED(IdentityDto
 * FrontNationInfo §2 동일) — 방어적 read, 부재 시 null. income 6종(세금/단기·세곡/둔전·수입/지출·금예산·미예산·
 * 금미차)은 PHP가 getGoldIncome/getRiceIncome/getWarGoldIncome/getWallIncome/getOutcome로 live 산출하나,
 * read game-api에 nation-type income 파이프라인이 조립돼 있지 않아(precheck는 identity-fold 빈 파이프라인)
 * 비-중립 nationType에서 PHP와 silently diverge → 산출하지 않고 누락(null/omit). 국가열전(getNationHistoryLogAll)은
 * nation-history read 원천 부재(스키마에 nation 이력 read entity/repository 없음) → null. FE는 부재 필드를 "-"로 렌더.
 */
data class MyNationDetailResponse(
    val result: Boolean,
    val hasNation: Boolean,
    // ── 국가 헤더(PHP `【{name}】` color/bg) ──────────────────────────────────────────────────────
    val nationId: Int = 0,
    val name: String = "",
    val color: String = "",
    // ── 19필드(8열 단일표) ────────────────────────────────────────────────────────────────────────
    /** 총주민 현재 = SUM(city.pop) over 보유 도시. */
    val population: Int = 0,
    /** 총주민 최대 = SUM(city.pop_max). */
    val populationMax: Int = 0,
    /** 총병사 현재 = SUM(general.crew) WHERE npc!=5. */
    val crew: Int = 0,
    /** 총병사 최대 = SUM(general.leadership)*100 WHERE npc!=5. */
    val crewMax: Int = 0,
    /** 국력(PHP `nation.power` raw). */
    val power: Int = 0,
    /** 국고(PHP `nation.gold`). */
    val gold: Int = 0,
    /** 병량(PHP `nation.rice`). */
    val rice: Int = 0,
    /** 속령수 = count(city). */
    val cityCount: Int = 0,
    /** 장수수(PHP `nation.gennum` = meta.gennum). */
    val generalCount: Int = 0,
    /** 기술력 = floor(nation.tech) (PHP `number_format(floor($nation['tech']))`). */
    val tech: Int = 0,
    /** 작위 한글명 = getNationLevel(level) = GameConst.nationLevelNameOf(level). */
    val levelText: String = "",
    /** raw level(작위 수치 — 표시는 levelText). */
    val level: Int = 0,
    /** 속령일람(보유 도시명, 수도는 isCapital=true → cyan). 삽입 순서(id ASC) 보존. */
    val cities: List<MyNationCityRef> = emptyList(),

    // [§2 BLOCKED — nation.meta UNVERIFIED] 세율/지급률(rate/bill): 방어적 read, 부재 시 null.
    /** 세율 % (PHP `nation.rate`) — meta.rate, UNVERIFIED → null. */
    val taxRate: Int? = null,
    /** 지급률 % (PHP `nation.bill`) — meta.bill, UNVERIFIED → null. */
    val bill: Int? = null,
    // [§2 BLOCKED — income 파이프라인 미조립] 세금(goldIncome)/단기(warIncome)/세곡(riceIncome)/
    //   둔전(wallIncome)/수입금(totalGoldIncome)/수입미(totalRiceIncome)/지출(outcome)/
    //   국고예산(budgetgold)/병량예산(budgetrice)/금미차(budgetgolddiff/budgetricediff) — 전부 누락.
    // [§2 BLOCKED — nation-history read 원천 부재] 국가열전(getNationHistoryLogAll) — 누락.
)

/** 속령일람 1개(도시명 + 수도 강조 플래그). */
data class MyNationCityRef(
    val cityId: Int,
    val name: String,
    // Jackson Kotlin 모듈의 Boolean `is`-접두사 제거 회피 — FE/테스트 계약 키 `isCapital` 고정.
    @get:JsonProperty("isCapital")
    val isCapital: Boolean,
)

// ── global-menu (§4 server-driven typed union) ───────────────────────────────

/**
 * Server-driven menu union (GlobalMenu.php parity). `type` discriminates: "item" | "split" | "multi"
 * | "line". Optional fields are present per type; the client filters via condShowVar/condHighlightVar
 * against globalInfo (see spec §4 filterMenu).
 */
data class MenuNode(
    val type: String,
    val name: String? = null,
    val url: String? = null,
    val newTab: Boolean? = null,
    val funcCall: String? = null,
    val icon: String? = null,
    val condHighlightVar: String? = null,
    val condShowVar: String? = null,
    val main: MenuNode? = null,
    val subMenu: List<MenuNode>? = null,
)

data class GlobalMenuResponse(
    val result: Boolean,
    val version: Int,
    val menu: List<MenuNode>,
)
// 구 GameConstResponse(/api/const)는 W3 GetConstController/GetConstResponse(superset)로 이관·삭제됨.
