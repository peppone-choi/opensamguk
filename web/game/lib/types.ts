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
