// 장수 만들기 계약(계약판 K5-01 · K5-02 · K5-03) — 서버 #1137(draft, head 862c6cc1f) `GeneralCreationDto.kt` 모양 그대로.
// 화면은 이 모양만 읽는다. 요구 문서 §4 의 역할 · 주인 · 묶음 · 결속 · 본관 · 위치 · 세력 이름은 이 계약에 없다 — 서버 대기로 그린다.
// main 에 서버가 없으면(404) · 정책이 닫혔으면(503 CREATION_POLICY_UNAVAILABLE) 화면은 「생성 대기」 그대로다.

export interface CreationStats {
    readonly leadership: number;
    readonly strength: number;
    readonly intel: number;
    readonly politics: number;
    readonly charm: number;
}

/** 서버 거절 — 문장(`message`)은 서버 것을 그대로 보인다. */
export interface CreationError {
    readonly code: string;
    readonly message: string;
}

// ── K5-03 역사 인물 후보 `GET /api/generals/creation/historical?q=&nation=&status=&sort=ID_ASC&cursor=&limit=` ──
export type HistoricalStatus = 'AVAILABLE' | 'TAKEN' | 'NOT_APPEARED';

export interface HistoricalCreationPerson {
    readonly historicalGeneralId: number;
    readonly name: string;
    /** 동명이인 한자(같은 읽기 인물이 함께 보일 때만 쓴다). */
    readonly nameCh: string | null;
    /** 초상 파일 이름. 이미지 서버 번호는 계약에 없다. */
    readonly portrait: string | null;
    readonly stats: CreationStats;
    /** 지금 월드의 소속 세력 id(재야 · 미등장 = null). 이름 · 색은 계약에 없다. */
    readonly nationId: number | null;
    readonly appeared: boolean;
    readonly available: boolean;
    readonly unavailableCode: string | null;
}

export interface HistoricalCreationPage {
    readonly schemaVersion: number;
    readonly worldId: number;
    readonly people: readonly HistoricalCreationPerson[];
    readonly nextCursor: string | null;
}

// ── K5-02 생성 옵션 `GET /api/generals/creation/options` ──
export interface CreationOption { readonly id: string; readonly label: string }
export interface CreationNameRule {
    readonly minimumCodePoints: number;
    readonly maximumCodePoints: number;
    readonly normalization: string;
    readonly allowedCharacters: string;
    readonly uniquenessScope: string;
}
export interface CreationMode { readonly kind: 'CUSTOM' | 'HISTORICAL' | (string & {}); readonly allowed: boolean; readonly reason: string | null }
export interface CreationStatRule { readonly min: number; readonly max: number; readonly total: number }
export interface CreationPolicy { readonly customAllowed: boolean; readonly historicalAllowed: boolean; readonly reason: string | null }
export interface CreationCounty {
    readonly cityId: number;
    readonly name: string;
    readonly commanderyId: string | null;
    readonly commanderyName: string | null;
    readonly provinceName: string | null;
    readonly cellCol: number | null;
    readonly cellRow: number | null;
    readonly available: boolean;
    readonly reason: string | null;
}
export interface GeneralCreationOptions {
    readonly schemaVersion: number;
    readonly worldId: number;
    readonly statRule: CreationStatRule;
    readonly nameRule: CreationNameRule;
    readonly policy: CreationPolicy;
    readonly modes: readonly CreationMode[];
    readonly ideologies: readonly CreationOption[];
    readonly traits: readonly CreationOption[];
    readonly nativeCounties: readonly CreationCounty[];
}

// ── K5-01 생성 쓰기 `POST /api/generals/creation` → 202, 결과 `GET /api/generals/creation/{requestId}` ──
export type CreationChoice =
    | { readonly kind: 'CUSTOM'; readonly name: string; readonly nativeCountyId: number; readonly stats: CreationStats; readonly ideologyId: string; readonly traitId: string }
    | { readonly kind: 'HISTORICAL'; readonly historicalGeneralId: number };

export interface CreationRequest {
    readonly expectedWorldId: number;
    readonly clientRequestId: string;
    readonly choice: CreationChoice;
}

export interface CreationAccepted {
    readonly schemaVersion: number;
    readonly status: 'ACCEPTED';
    readonly requestId: string;
    readonly worldId: number;
}

export interface CreationResult {
    readonly schemaVersion: number;
    readonly requestId: string;
    readonly worldId: number;
    readonly status: 'PENDING' | 'CREATED' | 'REJECTED';
    readonly generalId: number | null;
    readonly error: CreationError | null;
}
