package opensamguk.gameapi.dto

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
    val officerLevel: Int,
    val picture: String?,
    val imageServer: Int,
)

/** GET /api/generals/claimable body. */
data class ClaimableResponse(
    val result: Boolean,
    val hasGeneral: Boolean,
    val candidates: List<ClaimableGeneral>,
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
    val autorunUser: Boolean? = null,
    val lastExecuted: String? = null, // game_env.turntime(마지막 턴 실행 시각 문자열)
    val develCost: Int? = null,
    val noticeMsg: String? = null,
    val onlineUserCnt: Int? = null,
    val startyear: Int? = null,
    val generalCntLimit: Int? = null, // game_env.maxgeneral
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

    // COUNT 집계(저렴한 인덱스 카운트) — npc_state로 user/NPC 분리.
    val createdUserCnt: Int? = null, // npc_state == 0 (사용자 점유 가능 장수)
    val createdNPCCnt: Int? = null, // npc_state > 0 (NPC 장수)

    // ng_auction.finished = 0 진행중 경매 수(아래 AuctionCountReadRepository).
    val auctionCount: Int? = null,

    // [§2 BLOCKED — plock 테이블 부재] PHP `SELECT plock FROM plock WHERE type='GAME'`.
    // 모든 마이그레이션(V1~V10)에 plock 테이블이 없다. interim으로 false 고정(W3_FrontGlobalInfo §2).
    val serverLocked: Boolean? = null,
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
    //  - recentWar / troopInfo : recent_war_time은 컬럼이나 PHP `recent_war` 문자열 포맷/troop 합성은
    //                            W4 후속(이 read 그룹 OUT-OF-SCOPE). null.
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

/** GET /api/front-info body. `general.hasGeneral=false` (others null) when the caller has no character. */
data class FrontInfoResponse(
    val result: Boolean,
    val global: FrontGlobalInfo,
    val general: FrontGeneralInfo,
    val nation: FrontNationInfo?,
    val city: FrontCityInfo?,
    val recentRecord: List<String>,
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

/** GET /api/my-generals — generals in the caller's nation (the caller is always included). */
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
)

data class MyGeneralsResponse(
    val result: Boolean,
    val nationId: Int,
    val generals: List<MyGeneralSummary>,
)

/** GET /api/my-cities — cities owned by the caller's nation. */
data class MyCitySummary(
    val cityId: Int,
    val name: String,
    val level: Int,
    val region: Int,
    val population: Int,
    val populationMax: Int,
    val defense: Int,
    val wall: Int,
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

/** GET /api/my-nation-detail — the caller's nation, fuller surface (404 shape if no nation). */
data class MyNationDetailResponse(
    val result: Boolean,
    val hasNation: Boolean,
    val nation: FrontNationInfo?,
    val cityCount: Int,
    val generalCount: Int,
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
