// 참모 제안 읽기(`GET /api/retinue/proposals?generalId=`, 계약판 K8-06, 서버 #1408) — 응답 꼴과 검증. 화면은 useRetinueProposals 훅으로만 읽는다(D105 층).
//
// 계약: 서버 docs/development/retinue-proposals-read.md(D124 C5 ACK), 고정 응답 app/game-api/src/test/resources/retinue/proposals/*.json.
//  - root 는 {status, reason, now, proposals}, 모든 키를 늘 싣는다. NOT_SEEDED(제안을 만드는 서버 producer 없음) · UNAVAILABLE 은 proposals null.
//  - READY [] 는 「이번 순 제안 없음」. 서버는 아직 READY 를 내지 않고, 제안 행의 proposalType · status enum 도 정해지지 않았다 —
//    행 화면(제안 카드 · 근거 · 기울어진 까닭)을 짓기 전에는 행이 오면 실패로 돌린다.
//  - 401 · 403 · 그 밖의 상태 · 모르는 본문 · 모르는 reason 은 실패(fail closed). 404(배포 전)를 서버 대기로 그리는 것은 훅이다.
import { fetchGame } from '@/lib/api';

export type RetinueProposalsStatus = 'READY' | 'NOT_SEEDED' | 'UNAVAILABLE';
export type RetinueProposalsReason = 'PROPOSALS_NOT_SEEDED' | 'WORLD_UNAVAILABLE';

export interface RetinueProposals {
    readonly status: RetinueProposalsStatus;
    readonly reason: RetinueProposalsReason | null;
    /** READY 면 빈 배열(이번 순 제안 없음), 그 밖에는 null. */
    readonly proposals: readonly never[] | null;
}

export type RetinueProposalsRead =
    | { readonly ok: true; readonly proposals: RetinueProposals }
    | { readonly ok: false; readonly httpStatus: number | null };

type Rec = Record<string, unknown>;
const isRec = (v: unknown): v is Rec => typeof v === 'object' && v !== null && !Array.isArray(v);
const isInt = (v: unknown): v is number => typeof v === 'number' && Number.isSafeInteger(v);
const has = (o: Rec, k: string) => Object.prototype.hasOwnProperty.call(o, k);
const STATUS: readonly RetinueProposalsStatus[] = ['READY', 'NOT_SEEDED', 'UNAVAILABLE'];
const REASONS: readonly RetinueProposalsReason[] = ['PROPOSALS_NOT_SEEDED', 'WORLD_UNAVAILABLE'];
const oneOf = <T extends string>(v: unknown, set: readonly T[]): v is T => typeof v === 'string' && (set as readonly string[]).includes(v);

function phaseOk(v: unknown): boolean {
    if (v === null) return true;
    return isRec(v) && isInt(v.year) && isInt(v.month) && isInt(v.phase) && v.month >= 1 && v.month <= 12 && v.phase >= 1 && v.phase <= 3;
}

/** 응답 본문 검증. 계약 밖 모양이면 null. */
export function parseRetinueProposals(body: unknown): RetinueProposals | null {
    if (!isRec(body) || !oneOf(body.status, STATUS)) return null;
    for (const key of ['reason', 'now', 'proposals']) if (!has(body, key)) return null;
    if (!phaseOk(body.now)) return null;
    if (body.reason !== null && !oneOf(body.reason, REASONS)) return null;
    const reason = body.reason as RetinueProposalsReason | null;
    // 연월순은 월드를 셈하지 못했을 때(WORLD_UNAVAILABLE)만 null 이고, 그때는 늘 null 이다(서버 AdviserProposalsReader).
    if ((body.now === null) !== (reason === 'WORLD_UNAVAILABLE')) return null;
    if (body.status === 'READY') {
        // 행 화면을 짓기 전에는 확인된 「제안 없음」(빈 배열)만 받는다.
        if (reason !== null || !Array.isArray(body.proposals) || body.proposals.length > 0) return null;
        return { status: 'READY', reason: null, proposals: [] };
    }
    if (reason === null || body.proposals !== null) return null;
    if ((body.status === 'NOT_SEEDED') !== (reason === 'PROPOSALS_NOT_SEEDED')) return null;
    return { status: body.status, reason, proposals: null };
}

/** 200 만 본문을 검증해 넘기고, 그 밖의 상태(401 · 403 · 404 포함) · 모르는 본문은 실패로 돌린다. */
export async function readRetinueProposals(generalId: number, signal?: AbortSignal): Promise<RetinueProposalsRead> {
    let res: Response;
    try {
        res = await fetchGame(`/api/retinue/proposals?generalId=${encodeURIComponent(String(generalId))}`, { cache: 'no-store', signal });
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
    const proposals = parseRetinueProposals(body);
    return proposals ? { ok: true, proposals } : { ok: false, httpStatus: res.status };
}
