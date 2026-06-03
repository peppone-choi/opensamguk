// web/game TS contracts.
//  - User        mirrors gateway-api UserResponse (AuthDto.kt) — returned by /api/auth/me.
//  - Front-info / global-menu / const / identity shapes mirror game-api F2 Wave 1 DTOs (IdentityDto.kt).
// Field names are STABLE (Jackson default camelCase). Keep in sync with the Kotlin DTOs.

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
    serverCnt?: number;
    extendedGeneral?: boolean;
    isFiction?: boolean;
    npcMode?: number; // 0 불가능 / 1 가능 / 2 선택 생성
    onlineUserCnt?: number;
    apiLimit?: number;
    createdUserCnt?: number;
    generalCntLimit?: number;
    createdNPCCnt?: number;
    auctionCount?: number;
    lastVote?: { title: string } | null;
    lastExecuted?: string | null;
    serverLocked?: boolean;
    isTournamentActive?: boolean;
    tournamentType?: string;
    tournamentState?: string;
    // ── GlobalMenu flags (spec §4) — drive condHighlight/condShow + control-bar highlight ──
    nationBetting?: boolean;
    vote?: boolean;
    isTournamentApplicationOpen?: boolean;
    isBettingActive?: boolean;
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
    injury: number;
    gold: number;
    rice: number;
    crew: number;
    cityId: number;
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
}

export interface FrontCityInfo {
    id: number;
    name: string;
    level: number;
    nationId: number;
    region: number;
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
}

// ── city detail (game-api CityDetailController.CityDetailResponse) ────────────
// `GET /api/city/{id}` — the full city read shape the W4 MapCityDetail panel self-fetches by the clicked
// cityId. Distinct from FrontCityInfo: it adds supplyState / frontState / officers and types trust as a
// Double (the che math uses trust/100.0 & trust/80.0). 404 server-side when the id is absent (the panel
// falls back to a header-only render). Field names are STABLE (Jackson default camelCase) — keep in sync
// with CityDetailController.CityDetailResponse. (`trade` is NULLABLE — RandomizeCityTradeRate writes NULL
// for 상인 없음 cities; render 시세 from this value, never fabricate.)
export interface CityDetailResponse {
    id: number;
    name: string;
    level: number;
    region: number;
    nationId: number;
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
    supplyState: number;
    frontState: number;
    officers: number;
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
    /** 전선 상태(front_state 0~3) — 상태 아이콘 event<state>.gif (0=없음). */
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
}

export interface MapPreviewResponse {
    serverName: string;
    year: number;
    month: number;
    mapCode: string;
    width: number;
    height: number;
    cities: MapPreviewCity[];
    nations: MapPreviewNation[];
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
    officerLevelText: Record<number, string>;
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
    officerLevel: number;
    picture: string | null;
    imageServer: number;
}

export interface ClaimableResponse {
    result: boolean;
    hasGeneral: boolean;
    candidates: ClaimableGeneral[];
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

// ── F4 action-page READ contracts ────────────────────────────────────────────
// Authored in ../types/game.ts (the domain-contract module the pages import from).
// Re-exported here so lib/api.ts's `from './types'` import resolves them while the
// F4 page agents keep importing the same names from '../../../types/game'.
export type {
    GeneralListItem,
    GeneralListResponse,
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
    TroopListResponse,
    HistoryRecord,
    HistoryResponse,
} from '../types/game';
