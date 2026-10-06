// 지방 관직 읽기(`GET /api/court/local-offices?generalId=`, 계약판 K8-03) — 응답 꼴과 검증. 화면은 useCourtLocalOffices 훅으로만 읽는다(D105 층).
//
// 계약: 서버 docs/development/court-local-offices-read.md(D124 C5 ACK · K3 #1406), 고정 응답 app/game-api/src/test/resources/court/local-offices/*.json.
// 행 모양(재임 · 선택지 · 보낸 제안)은 계약 문서 court-office-vassal-api-contract.md 「읽기」 + ACK 의 한글 이름 칸 셋(서버가 행을 낼 때).
//  - root 는 {status, reason, now, localOffices, appointmentOptions, pendingOffers}, 모든 키를 늘 싣는다.
//    NOT_SEEDED(원천 없음) · UNAVAILABLE(셈 못 함)은 목록이 null — 「관직 0개」가 아니다. 확인된 빈 결과만 READY [].
//  - 경로가 없으면(404, 배포 전) 훅이 「서버 대기」로 그린다.
//  - 401 · 403 · 그 밖의 상태 · 모르는 본문 · 모르는 reason 은 실패로 돌린다(fail closed). 빠진 키를 null 로 채우지 않는다.
import { fetchGame } from '@/lib/api';

export type LocalOfficesStatus = 'READY' | 'NOT_SEEDED' | 'UNAVAILABLE';
/** 서버 LocalOfficesReason. READY 면 null. */
export type LocalOfficesReason =
    | 'TENURES_NOT_SEEDED'
    | 'WORLD_UNAVAILABLE'
    | 'TENURES_INVALID'
    | 'NO_NATION'
    | 'JURISDICTION_SNAPSHOT_UNAVAILABLE';
/** 임명 상태(계약 「임명 state」). 실효(EFFECTIVE)가 아니면 능력이 없다. */
export type TenureState = 'PENDING_ACCEPTANCE' | 'AWAITING_ARRIVAL' | 'EFFECTIVE' | 'NOMINAL';
/** 실효 근거 코드(logic OfficeEvidence). missing 에 든 것이 부족한 근거다. */
export type OfficeEvidence =
    | 'LIVING_CLAIM'
    | 'ACCEPTED_TENURE'
    | 'ASSUMED_SEAT'
    | 'SEAT_OWNED'
    | 'HOLDER_AT_SEAT'
    | 'COUNTY_MAJORITY'
    | 'WAREHOUSE_CONNECTION'
    | 'LOCAL_MAGISTRATE_OR_GARRISON';
/** 보낸 임명 제안 상태(logic OfficeOfferStatus). 기한이 지나도 화면이 수락으로 바꾸지 않는다. */
export type OfficeOfferState = 'PENDING' | 'ACCEPTED' | 'REFUSED';

export interface Phase {
    readonly year: number;
    readonly month: number;
    readonly phase: number;
}

export interface LocalTenure {
    readonly tenureId: string;
    readonly officeId: string;
    /** 서버가 준 관직 이름(사료 표기). */
    readonly officeName: string;
    /** 한글 관직 이름 — 검증된 한글 원천이 없으면 null(ACK). */
    readonly officeLabel: string | null;
    /** `zhou:<…>` 또는 `hhs-group:<…>` 정규 ID. */
    readonly jurisdictionId: string;
    /** 한글 관할 이름 — 서버 원천이 없으면 null. 지도 표시명으로 짐작해 잇지 않는다(ACK). */
    readonly jurisdictionName: string | null;
    readonly seatCountyId: number;
    readonly seatCountyName: string | null;
    readonly holderId: number;
    readonly holderName: string;
    readonly state: TenureState;
    /** 실효일 때만 채운다(계약). */
    readonly actualCountyIds: readonly number[];
    readonly missing: readonly OfficeEvidence[];
}

export interface AppointmentOption {
    readonly officeId: string;
    readonly jurisdictionId: string;
    readonly seatCountyId: number;
    readonly candidateId: number;
    readonly candidateName: string;
    /** false 면 보이지만 지금 접수할 수 없다 — blocked 에 서버의 정확한 실패 이유가 있다. 표시는 권한의 최종 보증이 아니다. */
    readonly available: boolean;
    readonly blocked: { readonly code: string; readonly reason: string } | null;
}

export interface PendingOffer {
    readonly offerId: string;
    readonly candidateId: number;
    readonly officeId: string;
    readonly jurisdictionId: string;
    readonly status: OfficeOfferState;
    readonly dueAt: Phase;
}

export interface CourtLocalOffices {
    readonly status: LocalOfficesStatus;
    readonly reason: LocalOfficesReason | null;
    readonly now: Phase | null;
    /** READY 면 배열(빈 배열 = 확인된 없음), 그 밖에는 null. */
    readonly localOffices: readonly LocalTenure[] | null;
    /** 권한 · 원천이 없으면 null — 「선택지 0개」 [] 와 다르다. */
    readonly appointmentOptions: readonly AppointmentOption[] | null;
    readonly pendingOffers: readonly PendingOffer[] | null;
}

export type CourtLocalOfficesRead =
    | { readonly ok: true; readonly offices: CourtLocalOffices }
    | { readonly ok: false; readonly httpStatus: number | null };

const STATES: readonly TenureState[] = ['PENDING_ACCEPTANCE', 'AWAITING_ARRIVAL', 'EFFECTIVE', 'NOMINAL'];
const ROOT: readonly LocalOfficesStatus[] = ['READY', 'NOT_SEEDED', 'UNAVAILABLE'];
const REASONS: readonly LocalOfficesReason[] = ['TENURES_NOT_SEEDED', 'WORLD_UNAVAILABLE', 'TENURES_INVALID', 'NO_NATION', 'JURISDICTION_SNAPSHOT_UNAVAILABLE'];
export const OFFICE_EVIDENCE: readonly OfficeEvidence[] = [
    'LIVING_CLAIM',
    'ACCEPTED_TENURE',
    'ASSUMED_SEAT',
    'SEAT_OWNED',
    'HOLDER_AT_SEAT',
    'COUNTY_MAJORITY',
    'WAREHOUSE_CONNECTION',
    'LOCAL_MAGISTRATE_OR_GARRISON',
];
const OFFER_STATES: readonly OfficeOfferState[] = ['PENDING', 'ACCEPTED', 'REFUSED'];

type Rec = Record<string, unknown>;
const isRec = (v: unknown): v is Rec => typeof v === 'object' && v !== null && !Array.isArray(v);
const isInt = (v: unknown): v is number => typeof v === 'number' && Number.isSafeInteger(v);
const isText = (v: unknown): v is string => typeof v === 'string' && v.length > 0;
const oneOf = <T extends string>(v: unknown, set: readonly T[]): v is T => typeof v === 'string' && (set as readonly string[]).includes(v);
const has = (o: Rec, k: string) => Object.prototype.hasOwnProperty.call(o, k);
/** 관할은 행정 축 정규 ID 만 — 내정 commanderyId 문자열을 그대로 보내지 않는다(계약). */
const isJurisdiction = (v: unknown): v is string => isText(v) && (v.startsWith('zhou:') || v.startsWith('hhs-group:'));
/** 이름 칸은 키가 있어야 하고 값은 글자 또는 null. */
const nameOrNull = (o: Rec, k: string): string | null | undefined => (!has(o, k) ? undefined : o[k] === null ? null : isText(o[k]) ? (o[k] as string) : undefined);

function phase(v: unknown): Phase | undefined {
    if (!isRec(v) || !isInt(v.year) || !isInt(v.month) || !isInt(v.phase)) return undefined;
    if (v.month < 1 || v.month > 12 || v.phase < 1 || v.phase > 3) return undefined;
    return { year: v.year, month: v.month, phase: v.phase };
}

function tenure(v: unknown): LocalTenure | null {
    if (!isRec(v)) return null;
    if (!isText(v.tenureId) || !isText(v.officeId) || !isText(v.officeName) || !isJurisdiction(v.jurisdictionId)) return null;
    const officeLabel = nameOrNull(v, 'officeLabel');
    const jurisdictionName = nameOrNull(v, 'jurisdictionName');
    const seatCountyName = nameOrNull(v, 'seatCountyName');
    if (officeLabel === undefined || jurisdictionName === undefined || seatCountyName === undefined) return null;
    if (!isInt(v.seatCountyId) || !isInt(v.holderId) || !isText(v.holderName) || !oneOf(v.state, STATES)) return null;
    if (!Array.isArray(v.actualCountyIds) || !v.actualCountyIds.every(isInt)) return null;
    if (!Array.isArray(v.missing) || !v.missing.every((m) => oneOf(m, OFFICE_EVIDENCE))) return null;
    // 실효 현은 실효일 때만 채운다 — 명목 · 대기 자리에 실효 현이 오면 계약 밖이다.
    if (v.state !== 'EFFECTIVE' && v.actualCountyIds.length > 0) return null;
    return {
        tenureId: v.tenureId,
        officeId: v.officeId,
        officeName: v.officeName,
        officeLabel,
        jurisdictionId: v.jurisdictionId,
        jurisdictionName,
        seatCountyId: v.seatCountyId,
        seatCountyName,
        holderId: v.holderId,
        holderName: v.holderName,
        state: v.state,
        actualCountyIds: v.actualCountyIds as number[],
        missing: v.missing as OfficeEvidence[],
    };
}

function option(v: unknown): AppointmentOption | null {
    if (!isRec(v)) return null;
    if (!isText(v.officeId) || !isJurisdiction(v.jurisdictionId) || !isInt(v.seatCountyId)) return null;
    if (!isInt(v.candidateId) || !isText(v.candidateName) || typeof v.available !== 'boolean') return null;
    let blocked: AppointmentOption['blocked'] = null;
    if (v.available) {
        // 계약 예시에는 막힌 선택지만 있다 — 열린 선택지의 blocked 는 없거나 null 이어야 한다.
        if (has(v, 'blocked') && v.blocked !== null) return null;
    } else {
        if (!isRec(v.blocked) || !isText(v.blocked.code) || !isText(v.blocked.reason)) return null;
        blocked = { code: v.blocked.code, reason: v.blocked.reason };
    }
    return {
        officeId: v.officeId,
        jurisdictionId: v.jurisdictionId,
        seatCountyId: v.seatCountyId,
        candidateId: v.candidateId,
        candidateName: v.candidateName,
        available: v.available,
        blocked,
    };
}

function pendingOffer(v: unknown): PendingOffer | null {
    if (!isRec(v)) return null;
    if (!isText(v.offerId) || !isInt(v.candidateId) || !isText(v.officeId) || !isJurisdiction(v.jurisdictionId)) return null;
    if (!oneOf(v.status, OFFER_STATES)) return null;
    const dueAt = phase(v.dueAt);
    if (!dueAt) return null;
    return { offerId: v.offerId, candidateId: v.candidateId, officeId: v.officeId, jurisdictionId: v.jurisdictionId, status: v.status, dueAt };
}

/** 배열이면 항목을 검증하고(하나라도 틀리면 undefined), null 은 null, 그 밖은 undefined(계약 밖). */
function list<T>(v: unknown, item: (x: unknown) => T | null): T[] | null | undefined {
    if (v === null) return null;
    if (!Array.isArray(v)) return undefined;
    const out = v.map(item);
    return out.some((x) => x === null) ? undefined : (out as T[]);
}

/** 응답 본문 검증. 계약 밖 모양이면 null. */
export function parseCourtLocalOffices(body: unknown): CourtLocalOffices | null {
    if (!isRec(body) || !oneOf(body.status, ROOT)) return null;
    for (const key of ['reason', 'now', 'localOffices', 'appointmentOptions', 'pendingOffers']) if (!has(body, key)) return null;
    const now = body.now === null ? null : phase(body.now);
    if (now === undefined) return null;
    if (body.reason !== null && !oneOf(body.reason, REASONS)) return null;
    const reason = body.reason as LocalOfficesReason | null;
    const localOffices = list(body.localOffices, tenure);
    const appointmentOptions = list(body.appointmentOptions, option);
    const pendingOffers = list(body.pendingOffers, pendingOffer);
    if (localOffices === undefined || appointmentOptions === undefined || pendingOffers === undefined) return null;
    if (body.status === 'READY') {
        // 확인된 결과 — 이유가 없고 재임 목록은 배열이다(빈 배열 = 확인된 없음).
        if (reason !== null || localOffices === null) return null;
    } else {
        // 원천 없음 · 셈 못 함은 「관직 0개」가 아니다 — 이유가 있고 목록은 모두 null.
        if (reason === null || localOffices !== null || appointmentOptions !== null || pendingOffers !== null) return null;
        if ((body.status === 'NOT_SEEDED') !== (reason === 'TENURES_NOT_SEEDED')) return null;
    }
    return { status: body.status, reason, now, localOffices, appointmentOptions, pendingOffers };
}

/** 200 만 본문을 검증해 넘기고, 그 밖의 상태(401 · 403 · 404 포함) · 모르는 본문은 실패로 돌린다. 404(경로 없음)를 서버 대기로 그리는 것은 훅이 정한다. */
export async function readCourtLocalOffices(generalId: number, signal?: AbortSignal): Promise<CourtLocalOfficesRead> {
    let res: Response;
    try {
        res = await fetchGame(`/api/court/local-offices?generalId=${encodeURIComponent(String(generalId))}`, { cache: 'no-store', signal });
    } catch (e) {
        if (e instanceof DOMException && e.name === 'AbortError') throw e;
        return { ok: false, httpStatus: null };
    }
    if (res.status !== 200) return { ok: false, httpStatus: res.status };
    let body: unknown;
    try {
        body = await res.json();
    } catch {
        return { ok: false, httpStatus: res.status };
    }
    const offices = parseCourtLocalOffices(body);
    return offices ? { ok: true, offices } : { ok: false, httpStatus: res.status };
}
