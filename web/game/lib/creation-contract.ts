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
/** 본관 현의 지도 칸 — 실제 tiles 3072×2676 축의 정수 칸 번호(계약판 K5-02). */
export interface CreationCell { readonly col: number; readonly row: number }
export interface CreationCounty {
    readonly cityId: number;
    readonly name: string;
    readonly commanderyId: string | null;
    readonly commanderyName: string | null;
    readonly provinceName: string | null;
    /** 칸을 못 맞춘 현(cityIndex 없음 · 타일 城 id 불일치 등)은 null — 지도 표지 없이 목록에만 나온다. */
    readonly cell: CreationCell | null;
    readonly available: boolean;
    readonly reason: string | null;
}
/**
 * 서버 응답 그대로의 본관 현. 정본은 중첩 `cell:{col,row}|null`(K5-02, 계약판 「K5 → C7 K5-02 cell 소비 답」)이고,
 * 옮겨 가는 동안 #1137 초안의 납작한 `cellCol · cellRow` 도 받는다. api 층이 `CreationCounty` 로 바꾼다.
 */
export interface CreationCountyWire extends Omit<CreationCounty, 'cell'> {
    readonly cell?: CreationCell | null;
    readonly cellCol?: number | null;
    readonly cellRow?: number | null;
}
/**
 * 역할별 시작 자리(D121 A안, 계약판 「K5 → C7 used:null 소비 답」) — 서버 DTO 는 아직 없다(미리 짓기, D124).
 * `cap:null` = 인원 제한 없음(D121), `used:null` = 원천 없음(UNKNOWN) — 둘은 뜻이 다르다. 화면은 추정 숫자를 그리지 않는다.
 */
export interface CreationRoleOption {
    readonly path: 'CUSTOM' | 'HISTORICAL' | (string & {});
    readonly role: CreationEntryRole | (string & {});
    readonly allowed: boolean;
    readonly reason: string | null;
    readonly used: number | null;
    readonly cap: number | null;
}
/** 사람 장수 전체 자리(실제 npcState<2 · maxgeneral 원천). */
export interface CreationPlayerCap { readonly used: number; readonly max: number }
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
    /** 없으면 옛 서버 — 화면은 지금 동작 그대로(RETAINER 만 열림 · PRE_LORD 는 서버 대기 사유). */
    readonly roles?: readonly CreationRoleOption[];
    readonly playerCap?: CreationPlayerCap;
}
export interface GeneralCreationOptionsWire extends Omit<GeneralCreationOptions, 'nativeCounties'> {
    readonly nativeCounties: readonly CreationCountyWire[];
}

// ── K5-01 생성 쓰기 `POST /api/generals/creation` → 202, 결과 `GET /api/generals/creation/{requestId}` ──
export type CreationChoice =
    /** `role` 은 서버 필수(#1137 CreationEntryRole) — 없으면 400 INVALID_REQUEST. PRE_LORD 는 지금 서버가 ROLE_UNAVAILABLE 로 거절한다. */
    | { readonly kind: 'CUSTOM'; readonly name: string; readonly nativeCountyId: number; readonly stats: CreationStats; readonly ideologyId: string; readonly traitId: string; readonly role: CreationEntryRole }
    | { readonly kind: 'HISTORICAL'; readonly historicalGeneralId: number };

/** 새 장수의 시작 역할(#1137 `CreationEntryRole`). 역사 인물 접수에는 보내지 않는다(서버가 거절한다). */
export type CreationEntryRole = 'RETAINER' | 'PRE_LORD';

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
