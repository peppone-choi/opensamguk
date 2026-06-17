// web/game TS contracts.
//  - User        mirrors gateway-api UserResponse (AuthDto.kt) — returned by /api/auth/me.
//  - Front-info / global-menu / const / identity shapes mirror game-api F2 Wave 1 DTOs (IdentityDto.kt).
// Field names are STABLE (Jackson default camelCase). Keep in sync with the Kotlin DTOs.

import type { VoteInfo } from '../types/game';

// ── auth (gateway-api UserResponse) ──────────────────────────────────────────
export interface User {
    id: number;
    username: string;
    email: string | null;
    nickname: string | null;
    role: string; // "USER" | "ADMIN"
}

// ── front-info (game-api FrontInfoResponse) ──────────────────────────────────
// Required fields are the W1 contract. Optional fields are the GameInfo header (spec §3) +
// GlobalMenu highlight/show flags (spec §4) the legacy `globalInfo` block carries; game-api may not
// emit all of them yet — GameInfo renders graceful fallbacks (no fabricated values) and the menu
// flags default to falsy (the legacy filterMenu drops a condShow item whose flag is absent anyway).
export interface FrontGlobalInfo {
    year: number;
    month: number;
    turnterm: number;
    scenario: string;
    scenarioText: string;
    generalCount: number;
    nationCount: number;
    cityCount: number;
    npcCount: number;
    // ── GameInfo header (spec §3) — optional until game-api emits them ──
    title?: string;
    serverName?: string;
    generation?: number;
    serverCnt?: number;
    serverId?: string;
    extendedGeneral?: boolean;
    isFiction?: boolean;
    npcMode?: number; // 0 불가능 / 1 가능 / 2 선택 생성
    npcModeText?: string;
    npcSummaryText?: string;
    autorunUser?: {
        limit_minutes: number;
        options: Record<string, number>;
    } | null;
    otherSettingText?: string;
    onlineUserCnt?: number;
    apiLimit?: number;
    createdUserCnt?: number;
    generalCntLimit?: number;
    blockGeneralCreate?: number;
    createdNPCCnt?: number;
    auctionCount?: number;
    // [P1-002] legacy GetFrontInfo.php:183-189,231 — lastVote는 VoteInfo 전체
    // ({id,title,multipleOptions,opener,startDate,endDate,options}, 만료 시 null).
    // 새 설문 토스트 판정엔 id가 필수(lastVoteID > 저장 커서 && > aux.myLastVote — PageFront.vue:472-474).
    // TODO(P1-002, W0-2): BE FrontInfoController가 lastVoteID/lastVote/aux.myLastVote 배출 시 소비.
    lastVote?: VoteInfo | null;
    /** legacy `lastVoteID` — 최신 설문 id(없으면 0). */
    lastVoteID?: number;
    lastExecuted?: string | null;
    serverLocked?: boolean;
    isTournamentActive?: boolean;
    tournamentTermMinutes?: number;
    tournamentType?: string;
    tournamentState?: string;
    // ── GlobalMenu flags (spec §4) — drive condHighlight/condShow + control-bar highlight ──
    nationBetting?: boolean;
    vote?: boolean;
    isTournamentApplicationOpen?: boolean;
    isBettingActive?: boolean;
    /** legacy `develcost`(개발비) — 설문 보상금 산정 원천(voteReward = develcost*5). 부재 시 미정. */
    develCost?: number;
    /** legacy v_vote.php:30 `voteReward` = develcost*5 — 설문 제목 "(N금 + 추첨 유니크템)" 안내. */
    voteReward?: number;
}

export interface FrontGeneralInfo {
    hasGeneral: boolean;
    generalId: number | null;
    name: string | null;
    nationId: number;
    officerLevel: number;
    permission: number;
    showSecret: boolean;
    leadership: number;
    strength: number;
    intel: number;
    politics?: number; // 정치/매력 (RTK14 divergence)
    charm?: number;
    injury: number;
    gold: number;
    rice: number;
    crew: number;
    cityId: number;
    // ── W3 enrichment (IdentityDto.FrontGeneralInfo) — optional, the daemon may not record every meta key ──
    picture?: string | null;
    imageServer?: number | null;
    experience?: number | null;
    dedication?: number | null;
    train?: number | null;
    atmos?: number | null;
    crewTypeId?: number | null;        // 병종 id
    troop?: number | null;             // troop_id
    horse?: string | null;             // 명마 코드(horse_code)
    weapon?: string | null;            // 무기 코드(weapon_code)
    book?: string | null;              // 서적 코드(book_code)
    item?: string | null;              // 도구 코드(item_code)
    age?: number | null;
    specialDomestic?: string | null;   // 내정 특기 코드(special_code)
    specialWar?: string | null;        // 전투 특기 코드(special2_code)
    personal?: string | null;          // 성격 코드(personal_code)
    // ── F-fix: 코드 → 한글 이름 해석값(API가 PHP grand-truth getName으로 해석; None/0 → '-') ──
    specialDomesticName?: string | null; // 내정 특기 이름
    specialWarName?: string | null;      // 전투 특기 이름
    crewTypeName?: string | null;        // 병종 이름
    personalName?: string | null;        // 성격 이름
    horseName?: string | null;           // 명마 이름
    weaponName?: string | null;          // 무기 이름
    bookName?: string | null;            // 서적 이름
    itemName?: string | null;            // 도구 이름
    explevel?: number | null;          // Lv(meta.explevel)
    dedlevel?: number | null;          // meta.dedlevel
    killturn?: number | null;          // 삭턴(meta.killturn)
    officerLevelText?: string | null; // getOfficerLevelText(officer_level, nationLevel)
    honorText?: string | null;        // getHonor(experience) — 호칭
    dedLevelText?: string | null;     // getDed(dedication) — 공헌
    lbonus?: number | null;           // calcLeadershipBonus(officer_level, nationLevel) — 통솔보너스
    bill?: number | null;             // getBillByLevel(getDedLevel(dedication))
    // ── 전투 통계 (IdentityDto.kt FrontGeneralInfo war stats) ──
    warnum?: number | null;           // 전투 횟수
    killnum?: number | null;          // 승리 횟수
    deathnum?: number | null;         // 패배 횟수
    killcrew?: number | null;         // 사살 병력
    deathcrew?: number | null;        // 피살 병력
    firenum?: number | null;          // 계략 횟수
    belong?: number | null;           // 사관(입사 경과 년)
}

// nation 인구/병력 grouped 집계(IdentityDto.NationPopulationGroup / NationCrewGroup).
export interface NationPopulationGroup {
    cityCnt: number;
    now: number;
    max: number;
}
export interface NationCrewGroup {
    generalCnt: number;
    now: number;
    max: number;
}
export interface NationTypeInfo {
    raw: string;
    name: string;
    pros: string;
    cons: string;
}
// topChiefs 1개 항목(IdentityDto.NationTopChief) — officer_level>=11 수뇌(군주/군주대리).
export interface NationTopChief {
    officerLevel: number;
    no: number;
    name: string;
    npc: number;
}

export interface FrontNationInfo {
    id: number;
    name: string;
    color: string;
    level: number;
    gold: number;
    rice: number;
    tech: number;
    capitalCityId: number | null;
    // ── W3 enrichment (IdentityDto.FrontNationInfo) — optional ──
    capital?: number | null;
    typeCode?: string | null;
    power?: number | null;
    gennum?: number | null;
    population?: NationPopulationGroup | null;
    crew?: NationCrewGroup | null;
    type?: NationTypeInfo | null;
    topChiefs?: NationTopChief[] | null; // officer_level>=11 수뇌 목록
    // 군주/군주대리 행 라벨(작위/직책 한글명, 서버 해석) — 날조 아님(F4StateText.officerLevelText 패러티 테이블).
    rulerOfficerText?: string | null;   // 군주 행 라벨 = officerLevelText(12, level) (두목/영주/…/황제)
    deputyOfficerText?: string | null;  // 군주대리 행 라벨 = officerLevelText(11, level)
    // [meta UNVERIFIED — 데몬 write 전까지 null. 날조 금지] 지급률/세율/외교·임관·전쟁 제한.
    bill?: number | null;             // meta.bill — 지급률(%)
    taxRate?: number | null;          // meta.rate — 세율(%)
    diplomaticLimit?: number | null;  // meta.surlimit — 외교 제한 잔여턴
    strategicCmdLimit?: number | null; // meta.strategic_cmd_limit — 전략 제한 잔여턴
    prohibitScout?: number | null;    // meta.scout — 임관 금지(1=금지)
    prohibitWar?: number | null;      // meta.war — 전쟁 금지(1=금지)
    // 국가방침 — nation_env KV nationNotice.msg(데몬 SetNotice). devsam PageFront.vue:32
    // `v-html=notice?.msg ?? ''` 등가(opensamguk BE는 msg 문자열만 내려줌). 부재/만료 시 null(날조 금지).
    notice?: string | null;
}

export interface CityOfficer {
    officerLevel: number; // 4=태수 / 3=군사 / 2=종사
    name: string;
    npc: number; // npc_state — getNPCColor 입력
}

export interface FrontCityInfo {
    id: number;
    name: string;
    level: number;
    levelName?: string | null; // 치소 등급 한글명 getCityLevelList()[level] (수/진/관/이/소/중/대/특)
    nationId: number;
    region: number;
    regionName?: string | null; // 지역 한글명 CityConst.regionMap[region] (하북/중원/서북/서촉/남중/초/오월/동이)
    officers?: CityOfficer[]; // 도시 관직(태수4/군사3/종사2) — 빈 슬롯 미포함, FE가 officerLevel로 조회
    population: number;
    populationMax: number;
    agriculture: number;
    agricultureMax: number;
    commerce: number;
    commerceMax: number;
    security: number;
    securityMax: number;
    defense: number;
    defenseMax: number;
    wall: number;
    wallMax: number;
    trust: number;
    trade: number | null;
}

export interface FrontInfoResponse {
    result: boolean;
    global: FrontGlobalInfo;
    general: FrontGeneralInfo;
    nation: FrontNationInfo | null;
    city: FrontCityInfo | null;
    recentRecord: string[];
    // [P1-002] legacy GetFrontInfo 봉투의 aux 블록(defs/API/Global.ts:224-226) — 내가 마지막으로
    // 참여한 설문 id. 새 설문 토스트 중복 억제에 사용. TODO(P1-002, W0-2): BE 배출 후 소비.
    aux?: { myLastVote?: number } | null;
}

// ── city detail (game-api CityDetailController.CityDetailResponse) ────────────
// `GET /api/city/{id}` — the full city read shape the W4 MapCityDetail panel self-fetches by the clicked
// cityId. Distinct from FrontCityInfo: it adds supplyState / frontState / officers and types trust as a
// Double (the che math uses trust/100.0 & trust/80.0). 404 server-side when the id is absent (the panel
// falls back to a header-only render). Field names are STABLE (Jackson default camelCase) — keep in sync
// with CityDetailController.CityDetailResponse. (`trade` is NULLABLE — RandomizeCityTradeRate writes NULL
// for 상인 없음 cities; render 시세 from this value, never fabricate.)
// 첩보(fog) 마스킹: 비-아국/공백지에 첩보·주둔 없으면 내정/방어 수치 전부 null + visible=false(서버에서 마스킹,
// payload 누출 없음). FE는 visible=false면 "첩보 없음 — 정보 비공개"로 렌더(수치 자리엔 '-').
export interface CityDetailResponse {
    id: number;
    name: string;
    level: number;
    levelName: string;  // 치소 등급 한글명 (수/진/관/이/소/중/대/특)
    region: number;
    regionName: string; // 지역 한글명 (하북/중원/…/동이)
    nationId: number;
    visible: boolean;   // 내정 가시 여부(소유/첩보/주둔). false면 아래 수치 null.
    population: number | null;
    populationMax: number | null;
    agriculture: number | null;
    agricultureMax: number | null;
    commerce: number | null;
    commerceMax: number | null;
    security: number | null;
    securityMax: number | null;
    defense: number | null;
    defenseMax: number | null;
    wall: number | null;
    wallMax: number | null;
    trust: number | null;
    trade: number | null;
    supplyState: number;
    frontState: number;
    officers: number | null;
}

// ── map preview (game-api MapPreviewResponse / MapPreviewDto.kt) ─────────────
// SAME shape the gateway lobby MapPreview consumes. `cities[].nationId === 0` is neutral and has NO
// entry in `nations[]` (render with a default neutral color). A city absent from the scenario coord
// JSON is OMITTED server-side (no x/y = nothing to draw). Field names are a stable client contract.
export interface MapPreviewCity {
    id: number;
    name: string;
    level: number;
    nationId: number;
    x: number;
    y: number;
    /** 재해/사건 코드(city.state) — event<state>.gif (0=없음). */
    state: number;
    /** 보급 상태 — 깃발 f(보급)/d(미보급). */
    supply: boolean;
    /** 소속국 수도 — 수도 아이콘 event51.gif(별). */
    isCapital: boolean;
}

export interface MapPreviewNation {
    id: number;
    name: string;
    color: string;
    scoutMsg?: string | null;
    infoText?: string | null;
}

export interface MapPreviewResponse {
    serverName: string;
    /** 시나리오 시작 연도 — W0-2b(MapPreviewDto.startYear, optional). 초반 3년 색상/기술등급 툴팁(P1-060) 소비 예정. */
    startYear?: number;
    year: number;
    month: number;
    mapCode: string;
    width: number;
    height: number;
    cities: MapPreviewCity[];
    nations: MapPreviewNation[];
}

// ── in-game world map (game-api WorldMapResponse / WorldMapDto.kt — GET /api/map) ─
// PHP getWorldMap(func_map.php) 충실 이식. fog(정찰 spyList / 아국 장수 소재 shownByGeneralList /
// myCity / myNation)를 포함한다 — 로비 MapPreview와 달리 게임 클라용. 좌표(x/y)는 PHP가 클라에서
// cityConst로 유도하므로 이 응답엔 없다(좌표는 MapPreview에서 id로 머지). compact tuple 형태:
//  - cityList tuple = [city, level, state, nation, region, supply]
//  - nationList tuple = [nation, name, color, capital]
export interface WorldMapResponse {
    result: boolean;
    version: number;
    startYear: number;
    year: number;
    month: number;
    cityList: number[][];
    nationList: (number | string)[][];
    /** {cityNo: remainMonth} — 내 국가 정찰 잔여개월. myNation 없으면 빈 객체. */
    spyList: Record<number, number>;
    /** 아국 장수 소재 도시 id 목록. */
    shownByGeneralList: number[];
    myCity: number | null;
    myNation: number | null;
}

// ── global-menu (game-api GlobalMenuResponse) ────────────────────────────────
export interface MenuNode {
    type: 'item' | 'split' | 'multi' | 'line';
    name?: string;
    url?: string;
    newTab?: boolean;
    funcCall?: string;
    icon?: string;
    condHighlightVar?: string;
    condShowVar?: string;
    main?: MenuNode;
    subMenu?: MenuNode[];
}

export interface GlobalMenuResponse {
    result: boolean;
    version: number;
    menu: MenuNode[];
}

// ── const (game-api GameConstResponse) ───────────────────────────────────────
export interface GameConstResponse {
    result: boolean;
    mapName: string;
    mapWidth: number;
    mapHeight: number;
    maxTurn: number;
    // 직책 라벨 기본열 — 정본 F4StateText(PHP func_converter.php getOfficerLevelText) 직렬화.
    // 와이어 모양 = legacy hwe/ts/utilGame/formatOfficerLevelText.ts OfficerLevelMapDefault.
    officerLevelText: Record<number, string>;
    // 국가레벨(7..0)별 수뇌 직책 — OfficerLevelMapByNationLevel 와이어. PHP 미정의 코드는 키 생략.
    officerLevelTextByNationLevel: Record<number, Record<number, string>>;
}

// ── possession (game-api Claimable/Claim) ────────────────────────────────────
export interface ClaimableGeneral {
    generalId: number;
    name: string;
    nationId: number;
    nationName: string | null;
    leadership: number;
    strength: number;
    intel: number;
    politics?: number; // 정치/매력 (RTK14 divergence)
    charm?: number;
    picture: string | null;
    imageServer: number;
    // legacy select_npc.ts NPCPick 카드 필드 — 한글 표시명(서버 해석). officerLevel은 카드에 없어 제거됨.
    special: string | null; // 내정특기명 (SpecialityHelper.domesticName)
    special2: string | null; // 전투특기명 (SpecialityHelper.warName)
    personal: string | null; // 성격명 (GameConst.personalityNameOf)
    keepCnt?: number;
}

export interface ClaimableResponse {
    result: boolean;
    hasGeneral: boolean;
    candidates: ClaimableGeneral[];
    validUntil?: string;
    pickMoreFrom?: string;
    pickMoreSeconds?: number;
}

export interface ClaimResponse {
    result: boolean;
    generalId: number | null;
    reason: string | null;
}

// ── my-* identity reads (game-api IdentityDto.kt) ────────────────────────────
export interface MyPageResponse {
    generalId: number;
    name: string;
    nationId: number;
    nationName: string | null;
    cityId: number;
    cityName: string | null;
    officerLevel: number;
    permission: number;
    leadership: number;
    strength: number;
    intel: number;
    injury: number;
    experience: number;
    dedication: number;
    gold: number;
    rice: number;
    crew: number;
    train: number;
    atmos: number;
    picture: string | null;
    imageServer: number;
}

export interface MyGeneralSummary {
    generalId: number;
    name: string;
    cityId: number;
    officerLevel: number;
    leadership: number;
    strength: number;
    intel: number;
    crew: number;
    npcState: number;
    mine: boolean;
}

export interface MyGeneralsResponse {
    result: boolean;
    nationId: number;
    generals: MyGeneralSummary[];
}

export interface MyCitySummary {
    cityId: number;
    name: string;
    level: number;
    region: number;
    population: number;
    populationMax: number;
    defense: number;
    wall: number;
}

export interface MyCitiesResponse {
    result: boolean;
    nationId: number;
    cities: MyCitySummary[];
}

export interface MyBossResponse {
    result: boolean;
    nationId: number;
    hasBoss: boolean;
    bossGeneralId: number | null;
    bossName: string | null;
    bossOfficerLevel: number | null;
}

export interface MyNationDetailResponse {
    result: boolean;
    hasNation: boolean;
    nation: FrontNationInfo | null;
    cityCount: number;
    generalCount: number;
}

// ── 명령 인테이크 결과 (POST /api/command/* · /api/instant-action/*) ──────────
// [W0-1 / P0-04·P0-06 근원] game-api CommandController·InstantActionController의 실제 응답 계약.
// HTTP 200도 deny일 수 있다(BlockedResponse) — res.ok만 보고 성공 처리하면 위조 성공 토스트가 된다.
//  - 202 {status:'AVAILABLE', ...} = **큐잉됨**(intake 수락). legacy 동기 실행과 달리 성공 확정이
//    아니다 — 엔진이 비동기로 deny할 수 있다(결과 회신 채널은 W0-4). 성공 문구가 아니라
//    "접수/예약" 시멘틱으로 토스트할 것.
//  - 200 {status:'BLOCKED'|'UNKNOWN', reason} = precheck deny. reason은 PHP byte-parity 문자열 —
//    페이지는 이 문자열을 그대로(danger/INFO) 노출한다. 날조·대체 금지.
// 페이지 분기는 api.ts의 isIntakeQueued/isIntakeDenied 타입 가드로 한다.

/** 202 — intake 수락(큐잉). 단건(requestId/turnIdx)·bulk(briefList)·instant(code) 공통 봉투. */
export interface IntakeQueued {
    status: 'AVAILABLE';
    /** 단건/instant 인테이크의 요청 추적 id. bulk엔 없음. */
    requestId?: string;
    /** 단건 예약 슬롯 인덱스. */
    turnIdx?: number;
    /** bulk(BulkAcceptedResponse): PHP ReserveBulkCommand 성공 반환과 동형. */
    result?: boolean;
    briefList?: string[];
    reason?: string;
    /** instant-action(IntakeAcceptedResponse): 수락된 액션 코드. */
    code?: string;
}

/** 200 — precheck/큐 조작 deny. reason = PHP byte-parity deny 문자열. */
export interface IntakeDenied {
    status: 'BLOCKED' | 'UNKNOWN';
    reason: string;
    /** 단건 precheck deny의 제약명(BlockedResponse.constraintName). */
    constraintName?: string | null;
    /** bulk(BulkBlockedResponse): 부분 실패 지점. */
    result?: boolean;
    briefList?: string[];
    errorIdx?: number;
}

/** 인테이크 POST의 resolve 값 — status로 판별하는 discriminated union. */
export type IntakeOutcome = IntakeQueued | IntakeDenied;

// ── 예약 명령 링 read (GET /api/reserved-commands) ────────────────────────────
// [P0-01] ReservedCommandsController의 실제 응답. 메인 예약 패널은 이걸 소비해야 하며
// 전 슬롯 '휴식' 하드코딩 렌더(위조)를 금지한다 — 빈 슬롯(slots에 없는 turnIdx)만 '휴식'.

/** 예약 링 1슬롯 — {turnIdx, action, brief, arg}. brief는 패러티 마크업 원문. */
export interface ReservedSlot {
    turnIdx: number;
    action: string;
    brief: string;
    arg: Record<string, unknown>;
}

export interface ReservedCommandsResponse {
    result: boolean;
    generalId: number | null;
    slots: ReservedSlot[];
    // ── legacy GetReservedCommand.php:55-92 메타(턴 시각/연월/자율행동) — BE 미배출(W0-2에서
    //    ReservedCommandsResponse widen, P1-004). legacy 계약(hwe/ts/defs/API/Command.ts
    //    ReservedCommandResponse) 모양으로 선선언 — 값이 올 때까지 렌더는 부재 처리(날조 금지).
    /** TODO(P1-004, W0-2): 다음 턴 실행 시각 "HH:mm" — legacy `turnTime`. */
    turnTime?: string;
    /** TODO(P1-004, W0-2): 턴 간격(분) — legacy `turnTerm`. */
    turnTerm?: number;
    /** TODO(P1-004, W0-2): 현재 연/월 — legacy `year`/`month`. */
    year?: number;
    month?: number;
    /** TODO(P1-004, W0-2): 서버 현재 시각 — legacy `date`. */
    date?: string;
    /** TODO(P1-004, W0-2): 자율 행동 제한(분) — legacy `autorun_limit`(snake — PHP verbatim 키). */
    autorun_limit?: number | null;
}

// ── F4 action-page READ contracts ────────────────────────────────────────────
// Authored in ../types/game.ts (the domain-contract module the pages import from).
// Re-exported here so lib/api.ts's `from './types'` import resolves them while the
// F4 page agents keep importing the same names from '../../../types/game'.
export type {
    GeneralListItem,
    GeneralListResponse,
    PublicGeneral,
    TournamentTypeText,
    TournamentEntrant,
    TournamentBracketMatch,
    TournamentRankRow,
    TournamentResponse,
    DiplomacyLetterParty,
    DiplomacyLetter,
    DiplomacyLetterNation,
    DiplomacyLettersResponse,
    ConflictNation,
    ConflictCity,
    DiplomacyConflictResponse,
    NationFinancePolicy,
    NationFinanceIncome,
    NationFinanceWarSettingCnt,
    NationFinanceResponse,
    ChiefReservedTurn,
    ChiefPost,
    ChiefCommand,
    ChiefCommandCategory,
    ChiefReservedResponse,
    NpcPolicyLastSetter,
    NpcPolicyResponse,
    InheritSpecialWar,
    InheritUnique,
    InheritActionCost,
    InheritPointLog,
    InheritCurrentStat,
    InheritPointResponse,
    BoardComment,
    BoardArticle,
    BoardResponse,
    VoteInfo,
    VoteListResponse,
    VoteComment,
    VoteResultRow,
    VoteDetailResponse,
    TroopInfo,
    TroopMember,
    TroopListResponse,
    HistoryRecord,
    HistoryResponse,
    MailMsgType,
    MailMsgTarget,
    MailboxMessage,
} from '../types/game';
