// 봉신 저장 조건 읽기(`GET /api/court/vassals?generalId=`, C5 #1373) — 응답 꼴과 검증. 화면은 useCourtVassals 훅으로만 읽는다(D105 층).
//
// 계약: docs/development/vassal-stored-terms-read-draft.md, 응답 예시 app/game-api/src/test/resources/court/vassal/*.json(11개).
//  - 200 {status:'PARTIAL', contractsStatus:'READY', contracts:[…]}  저장된 계약 조건만 읽었다. 활성 · 달력 · 원군 요청 · 설립 선택지는 UNAVAILABLE.
//  - 200 {status:'UNAVAILABLE', contractsStatus:'NOT_SEEDED' | 'UNAVAILABLE', contracts:[]}  계약 0개 확정이 아니다.
//  - 401 · 403 · 그 밖의 상태 · 모르는 본문은 실패로 돌린다(fail closed).
import { fetchGame } from '@/lib/api';

export type VassalFieldStatus = 'READY' | 'UNAVAILABLE';
export type VassalContractsStatus = 'READY' | 'NOT_SEEDED' | 'UNAVAILABLE';
export type VassalAutonomy = 'COUNTY_POLICY' | 'TAX_ALLOCATION' | 'GARRISON_COMMAND';
export type VassalDiplomacyRight = 'NONE' | 'WITH_APPROVAL' | 'INDEPENDENT';
export type MonthlyTributeStatus = 'PAID' | 'UNPAID' | 'ZERO_DUE' | 'NO_RECEIPT' | 'UNAVAILABLE';

export interface Phase {
    readonly year: number;
    readonly month: number;
    readonly phase: number;
}

export interface Resources {
    readonly money: number;
    readonly grain: number;
    readonly iron: number;
    readonly timber: number;
    readonly horses: number;
}

export interface TributeReceipt {
    readonly year: number;
    readonly month: number;
    readonly due: Resources;
    readonly paid: Resources;
    readonly unpaid: Resources;
}

export interface VassalContract {
    readonly contractId: string;
    readonly sovereignLordId: number;
    readonly vassalLordId: number;
    /** 같은 월드 · 세력 장수 행에서 확인한 이름. 미확인은 null — 지어 채우지 않는다. */
    readonly vassalName: string | null;
    /** isHumanStatus 가 READY 일 때만 true/false. UNAVAILABLE 이면 null(NPC 로 바꿔 넣지 않는다). */
    readonly isHuman: boolean | null;
    readonly isHumanStatus: VassalFieldStatus;
    readonly fiefCountyIds: readonly number[];
    readonly tributePercent: number;
    readonly reinforcementTroops: number;
    readonly autonomy: readonly VassalAutonomy[];
    readonly diplomacyRight: VassalDiplomacyRight;
    readonly loyalty: number;
    /** 원본 Long 시점. 달력으로 해석하지 않는다(calendarStatus UNAVAILABLE). */
    readonly signedTurn: number;
    readonly expiresTurn: number | null;
    readonly endedTurn: number | null;
    readonly calendarStatus: VassalFieldStatus;
    readonly signedAt: Phase | null;
    readonly endedAt: Phase | null;
    readonly tributeHistory: readonly TributeReceipt[];
    readonly monthlyTribute: { readonly status: MonthlyTributeStatus; readonly receipt: TributeReceipt | null };
    readonly reinforcementResponse: { readonly status: VassalFieldStatus; readonly dueAt: Phase | null };
}

export interface CourtVassals {
    readonly status: 'PARTIAL' | 'UNAVAILABLE';
    readonly now: Phase | null;
    readonly contractsStatus: VassalContractsStatus;
    readonly contracts: readonly VassalContract[];
    readonly activityStatus: VassalFieldStatus;
    readonly foundingOptionsStatus: VassalFieldStatus;
}

export type CourtVassalsRead =
    | { readonly ok: true; readonly vassals: CourtVassals }
    | { readonly ok: false; readonly httpStatus: number | null };

const FIELD: readonly VassalFieldStatus[] = ['READY', 'UNAVAILABLE'];
const CONTRACTS: readonly VassalContractsStatus[] = ['READY', 'NOT_SEEDED', 'UNAVAILABLE'];
const AUTONOMY: readonly VassalAutonomy[] = ['COUNTY_POLICY', 'TAX_ALLOCATION', 'GARRISON_COMMAND'];
const DIPLOMACY: readonly VassalDiplomacyRight[] = ['NONE', 'WITH_APPROVAL', 'INDEPENDENT'];
const MONTHLY: readonly MonthlyTributeStatus[] = ['PAID', 'UNPAID', 'ZERO_DUE', 'NO_RECEIPT', 'UNAVAILABLE'];

type Rec = Record<string, unknown>;
const isRec = (v: unknown): v is Rec => typeof v === 'object' && v !== null && !Array.isArray(v);
const isInt = (v: unknown): v is number => typeof v === 'number' && Number.isSafeInteger(v);
const oneOf = <T extends string>(v: unknown, set: readonly T[]): v is T => typeof v === 'string' && (set as readonly string[]).includes(v);
/** 계약은 null 을 「명시」한다 — 빠진 키를 null 로 채우지 않는다. */
const has = (o: Rec, k: string) => Object.prototype.hasOwnProperty.call(o, k);

function phase(v: unknown): Phase | null | undefined {
    if (v === null) return null;
    if (!isRec(v) || !isInt(v.year) || !isInt(v.month) || !isInt(v.phase)) return undefined;
    if (v.month < 1 || v.month > 12 || v.phase < 1 || v.phase > 3) return undefined;
    return { year: v.year, month: v.month, phase: v.phase };
}

function resources(v: unknown): Resources | null {
    if (!isRec(v)) return null;
    const { money, grain, iron, timber, horses } = v;
    if (![money, grain, iron, timber, horses].every(isInt)) return null;
    return { money, grain, iron, timber, horses } as Resources;
}

function receipt(v: unknown): TributeReceipt | null {
    if (!isRec(v) || !isInt(v.year) || !isInt(v.month)) return null;
    const due = resources(v.due);
    const paid = resources(v.paid);
    const unpaid = resources(v.unpaid);
    if (!due || !paid || !unpaid) return null;
    return { year: v.year, month: v.month, due, paid, unpaid };
}

function contract(v: unknown): VassalContract | null {
    if (!isRec(v)) return null;
    if (typeof v.contractId !== 'string' || v.contractId.length === 0) return null;
    if (!isInt(v.sovereignLordId) || !isInt(v.vassalLordId)) return null;
    if (!has(v, 'vassalName') || (v.vassalName !== null && typeof v.vassalName !== 'string')) return null;
    if (!oneOf(v.isHumanStatus, FIELD) || !has(v, 'isHuman')) return null;
    if (v.isHumanStatus === 'READY' ? typeof v.isHuman !== 'boolean' : v.isHuman !== null) return null;
    if (!Array.isArray(v.fiefCountyIds) || !v.fiefCountyIds.every(isInt)) return null;
    if (!isInt(v.tributePercent) || !isInt(v.reinforcementTroops) || !isInt(v.loyalty)) return null;
    if (!Array.isArray(v.autonomy) || !v.autonomy.every((a) => oneOf(a, AUTONOMY))) return null;
    if (!oneOf(v.diplomacyRight, DIPLOMACY)) return null;
    if (!isInt(v.signedTurn) || !has(v, 'expiresTurn') || !has(v, 'endedTurn')) return null;
    if ((v.expiresTurn !== null && !isInt(v.expiresTurn)) || (v.endedTurn !== null && !isInt(v.endedTurn))) return null;
    if (!oneOf(v.calendarStatus, FIELD)) return null;
    const signedAt = phase(v.signedAt);
    const endedAt = phase(v.endedAt);
    if (signedAt === undefined || endedAt === undefined || phase(v.expiresAt) === undefined) return null;
    if (!Array.isArray(v.tributeHistory)) return null;
    const history = v.tributeHistory.map(receipt);
    if (history.some((r) => r === null)) return null;
    const m = v.monthlyTribute;
    if (!isRec(m) || !oneOf(m.status, MONTHLY) || !has(m, 'receipt')) return null;
    const monthly = m.receipt === null ? null : receipt(m.receipt);
    if (m.receipt !== null && !monthly) return null;
    // 당월 영수증이 없으면 NO_RECEIPT · receipt null 이 계약이다.
    if ((m.status === 'NO_RECEIPT') !== (monthly === null) && m.status !== 'UNAVAILABLE') return null;
    const rr = v.reinforcementResponse;
    if (!isRec(rr) || !oneOf(rr.status, FIELD)) return null;
    const dueAt = phase(rr.dueAt);
    if (dueAt === undefined) return null;
    return {
        contractId: v.contractId,
        sovereignLordId: v.sovereignLordId,
        vassalLordId: v.vassalLordId,
        vassalName: v.vassalName as string | null,
        isHuman: v.isHuman as boolean | null,
        isHumanStatus: v.isHumanStatus,
        fiefCountyIds: v.fiefCountyIds as number[],
        tributePercent: v.tributePercent,
        reinforcementTroops: v.reinforcementTroops,
        autonomy: v.autonomy as VassalAutonomy[],
        diplomacyRight: v.diplomacyRight,
        loyalty: v.loyalty,
        signedTurn: v.signedTurn,
        expiresTurn: v.expiresTurn as number | null,
        endedTurn: v.endedTurn as number | null,
        calendarStatus: v.calendarStatus,
        signedAt,
        endedAt,
        tributeHistory: history as TributeReceipt[],
        monthlyTribute: { status: m.status, receipt: monthly },
        reinforcementResponse: { status: rr.status, dueAt },
    };
}

/** 응답 본문 검증. 계약 밖 모양이면 null. */
function parseCourtVassals(body: unknown): CourtVassals | null {
    if (!isRec(body)) return null;
    if (body.status !== 'PARTIAL' && body.status !== 'UNAVAILABLE') return null;
    if (!has(body, 'now')) return null;
    const now = phase(body.now);
    if (now === undefined) return null;
    if (!oneOf(body.contractsStatus, CONTRACTS) || !oneOf(body.activityStatus, FIELD) || !oneOf(body.foundingOptionsStatus, FIELD)) return null;
    if (!Array.isArray(body.contracts)) return null;
    // PARTIAL 은 저장 조건 READY 일 때만, UNAVAILABLE 은 계약 없이만 온다.
    if ((body.status === 'PARTIAL') !== (body.contractsStatus === 'READY')) return null;
    if (body.status === 'UNAVAILABLE' && body.contracts.length > 0) return null;
    const contracts = body.contracts.map(contract);
    if (contracts.some((c) => c === null)) return null;
    return {
        status: body.status,
        now,
        contractsStatus: body.contractsStatus,
        contracts: contracts as VassalContract[],
        activityStatus: body.activityStatus,
        foundingOptionsStatus: body.foundingOptionsStatus,
    };
}

/** 200 만 본문을 검증해 넘기고, 그 밖의 상태(401 · 403 포함) · 모르는 본문은 실패로 돌린다. */
export async function readCourtVassals(generalId: number, signal?: AbortSignal): Promise<CourtVassalsRead> {
    let res: Response;
    try {
        res = await fetchGame(`/api/court/vassals?generalId=${encodeURIComponent(String(generalId))}`, { cache: 'no-store', signal });
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
    const vassals = parseCourtVassals(body);
    return vassals ? { ok: true, vassals } : { ok: false, httpStatus: res.status };
}
