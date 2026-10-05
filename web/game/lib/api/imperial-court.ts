// 황실 court 읽기(`GET /api/imperial/court?generalId=`, C6 #1389 · 사용자 D123) — 응답 꼴과 검증. 화면은 useImperialCourt 훅으로만 읽는다(D105 층).
//
// 계약: docs/design/imperial-court-api.md(C6), 계약판 7487(H01–06) · 7502(K8 소비 답).
//  - 200 {status:'READY', lines:[…]}   READY + lines=[] 은 검증된 빈 황실이다.
//  - 200 {status:'NOT_SEEDED', lines:[]}   황실 없음 — 공위 · 멸망이 아니다.
//  - 409 {status:'STATE_UNAVAILABLE', lines:[]}   부분 상세를 내리지 않는다.
//  - 401 · 403 · 그 밖의 상태 · 모르는 본문은 실패다(fail closed). 404 는 경로가 아직 없다는 뜻이다(서버가 main 에 들어오기 전 — 화면은 서버 대기).
// 줄의 키는 늘 다 온다(명시 null). VACANT · ENDED 는 code · name · status 만 공개하고 상세는 null, fieldStates 넷은 NOT_APPLICABLE 이다.
// 황제 위치(H03)는 이 응답에 없다 — 기존 presence 가 맡는다. lines[].code = presence lineCode 로 붙인다.
import { fetchGame } from '@/lib/api';

export type ImperialCourtStatus = 'READY' | 'NOT_SEEDED' | 'STATE_UNAVAILABLE';
export type ImperialLineStatus = 'ACTIVE' | 'VACANT' | 'ENDED';
export type CourtFieldState = 'READY' | 'NOT_APPLICABLE' | 'UNAVAILABLE';

export interface CourtFieldStates {
    readonly holder: CourtFieldState;
    readonly courtCity: CourtFieldState;
    readonly regent: CourtFieldState;
    readonly courtNation: CourtFieldState;
}

export interface ImperialCourtLine {
    readonly code: string;
    readonly name: string;
    readonly status: ImperialLineStatus;
    readonly holderGeneralId: number | null;
    readonly emperorName: string | null;
    readonly courtCityId: number | null;
    readonly courtCityName: string | null;
    readonly regentGeneralId: number | null;
    readonly regentName: string | null;
    readonly courtNationId: number | null;
    readonly courtNationName: string | null;
    readonly fieldStates: CourtFieldStates;
}

export interface ImperialCourt {
    readonly status: ImperialCourtStatus;
    readonly lines: readonly ImperialCourtLine[];
}

export type ImperialCourtRead =
    | { readonly ok: true; readonly court: ImperialCourt }
    | { readonly ok: false; readonly httpStatus: number | null };

const STATUSES: readonly ImperialCourtStatus[] = ['READY', 'NOT_SEEDED', 'STATE_UNAVAILABLE'];
const LINE_STATUSES: readonly ImperialLineStatus[] = ['ACTIVE', 'VACANT', 'ENDED'];
const FIELD: readonly CourtFieldState[] = ['READY', 'NOT_APPLICABLE', 'UNAVAILABLE'];
const DETAIL_KEYS = ['holderGeneralId', 'emperorName', 'courtCityId', 'courtCityName', 'regentGeneralId', 'regentName', 'courtNationId', 'courtNationName'] as const;
const FIELD_KEYS = ['holder', 'courtCity', 'regent', 'courtNation'] as const;

type Rec = Record<string, unknown>;
const isRec = (v: unknown): v is Rec => typeof v === 'object' && v !== null && !Array.isArray(v);
const oneOf = <T extends string>(v: unknown, set: readonly T[]): v is T => typeof v === 'string' && (set as readonly string[]).includes(v);
const has = (o: Rec, k: string) => Object.prototype.hasOwnProperty.call(o, k);
const intOrNull = (v: unknown) => v === null || (typeof v === 'number' && Number.isSafeInteger(v));
const strOrNull = (v: unknown) => v === null || typeof v === 'string';

function line(v: unknown): ImperialCourtLine | null {
    if (!isRec(v)) return null;
    if (typeof v.code !== 'string' || v.code.length === 0 || typeof v.name !== 'string') return null;
    if (!oneOf(v.status, LINE_STATUSES)) return null;
    // 계약은 키를 늘 다 보낸다 — 빠진 키를 null 로 채우지 않는다.
    if (!DETAIL_KEYS.every((k) => has(v, k))) return null;
    if (![v.holderGeneralId, v.courtCityId, v.regentGeneralId, v.courtNationId].every(intOrNull)) return null;
    if (![v.emperorName, v.courtCityName, v.regentName, v.courtNationName].every(strOrNull)) return null;
    const fs = v.fieldStates;
    if (!isRec(fs) || !FIELD_KEYS.every((k) => oneOf(fs[k], FIELD))) return null;
    const states = fs as unknown as CourtFieldStates;
    if (v.status === 'ACTIVE') {
        // ACTIVE 는 칸을 공개한다 — 적용 없음(NOT_APPLICABLE)이 올 자리가 아니다.
        if (FIELD_KEYS.some((k) => states[k] === 'NOT_APPLICABLE')) return null;
    } else if (FIELD_KEYS.some((k) => states[k] !== 'NOT_APPLICABLE') || DETAIL_KEYS.some((k) => v[k] !== null)) {
        // VACANT · ENDED 는 code · name · status 만 — 상세 값이 있으면 공개 범위(D123) 밖이다.
        return null;
    }
    return {
        code: v.code,
        name: v.name,
        status: v.status,
        holderGeneralId: v.holderGeneralId as number | null,
        emperorName: v.emperorName as string | null,
        courtCityId: v.courtCityId as number | null,
        courtCityName: v.courtCityName as string | null,
        regentGeneralId: v.regentGeneralId as number | null,
        regentName: v.regentName as string | null,
        courtNationId: v.courtNationId as number | null,
        courtNationName: v.courtNationName as string | null,
        fieldStates: { holder: states.holder, courtCity: states.courtCity, regent: states.regent, courtNation: states.courtNation },
    };
}

function parseImperialCourt(body: unknown): ImperialCourt | null {
    if (!isRec(body) || !oneOf(body.status, STATUSES) || !Array.isArray(body.lines)) return null;
    if (body.status !== 'READY' && body.lines.length > 0) return null;
    const lines = body.lines.map(line);
    if (lines.some((l) => l === null)) return null;
    return { status: body.status, lines: lines as ImperialCourtLine[] };
}

/** 200 · 409 는 본문을 검증해 넘기고(409 는 STATE_UNAVAILABLE 만), 그 밖의 상태 · 모르는 본문은 실패로 돌린다. */
export async function readImperialCourt(generalId: number, signal?: AbortSignal): Promise<ImperialCourtRead> {
    let res: Response;
    try {
        res = await fetchGame(`/api/imperial/court?generalId=${encodeURIComponent(String(generalId))}`, { cache: 'no-store', signal });
    } catch (e) {
        if (e instanceof DOMException && e.name === 'AbortError') throw e;
        return { ok: false, httpStatus: null };
    }
    if (res.status !== 200 && res.status !== 409) return { ok: false, httpStatus: res.status };
    let body: unknown;
    try {
        body = await res.json();
    } catch {
        return { ok: false, httpStatus: res.status };
    }
    const court = parseImperialCourt(body);
    if (!court || (res.status === 409) !== (court.status === 'STATE_UNAVAILABLE')) return { ok: false, httpStatus: res.status };
    return { ok: true, court };
}
