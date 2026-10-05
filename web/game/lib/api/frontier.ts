// 주변 세계 읽기(`GET /api/frontier?generalId=`, 계약판 K8-09, 서버 #1407) — 응답 꼴과 검증. 화면은 useFrontier 훅으로만 읽는다(D105 층).
//
// 계약: 서버 docs/development/frontier-read.md(D124 C5 ACK), 고정 응답 app/game-api/src/test/resources/frontier/*.json.
//  - root 는 {status, reason, now, actors}, 모든 키를 늘 싣는다. NOT_SEEDED(접촉 원천 없음) · UNAVAILABLE 은 actors null.
//  - READY [] 는 「검증된 무접촉(내륙)」. 서버는 아직 행을 내지 않는다 — 행 화면(행위자 카드)을 짓기 전에는 행이 오면 실패로 돌린다.
//  - 401 · 403 · 그 밖의 상태 · 모르는 본문 · 모르는 reason 은 실패(fail closed). 404(배포 전)를 서버 대기로 그리는 것은 훅이다.
import { fetchGame } from '@/lib/api';

export type FrontierStatus = 'READY' | 'NOT_SEEDED' | 'UNAVAILABLE';
export type FrontierReason = 'CONTACTS_NOT_SEEDED' | 'WORLD_UNAVAILABLE' | 'NO_NATION';

export interface Frontier {
    readonly status: FrontierStatus;
    readonly reason: FrontierReason | null;
    /** READY 면 빈 배열(확인된 무접촉), 그 밖에는 null. */
    readonly actors: readonly never[] | null;
}

export type FrontierRead = { readonly ok: true; readonly frontier: Frontier } | { readonly ok: false; readonly httpStatus: number | null };

type Rec = Record<string, unknown>;
const isRec = (v: unknown): v is Rec => typeof v === 'object' && v !== null && !Array.isArray(v);
const isInt = (v: unknown): v is number => typeof v === 'number' && Number.isSafeInteger(v);
const has = (o: Rec, k: string) => Object.prototype.hasOwnProperty.call(o, k);
const STATUS: readonly FrontierStatus[] = ['READY', 'NOT_SEEDED', 'UNAVAILABLE'];
const REASONS: readonly FrontierReason[] = ['CONTACTS_NOT_SEEDED', 'WORLD_UNAVAILABLE', 'NO_NATION'];
const oneOf = <T extends string>(v: unknown, set: readonly T[]): v is T => typeof v === 'string' && (set as readonly string[]).includes(v);

function phaseOk(v: unknown): boolean {
    if (v === null) return true;
    return isRec(v) && isInt(v.year) && isInt(v.month) && isInt(v.phase) && v.month >= 1 && v.month <= 12 && v.phase >= 1 && v.phase <= 3;
}

/** 응답 본문 검증. 계약 밖 모양이면 null. */
export function parseFrontier(body: unknown): Frontier | null {
    if (!isRec(body) || !oneOf(body.status, STATUS)) return null;
    for (const key of ['reason', 'now', 'actors']) if (!has(body, key)) return null;
    if (!phaseOk(body.now)) return null;
    if (body.reason !== null && !oneOf(body.reason, REASONS)) return null;
    const reason = body.reason as FrontierReason | null;
    if (body.status === 'READY') {
        // 행 화면을 짓기 전에는 확인된 무접촉(빈 배열)만 받는다.
        if (reason !== null || !Array.isArray(body.actors) || body.actors.length > 0) return null;
        return { status: 'READY', reason: null, actors: [] };
    }
    if (reason === null || body.actors !== null) return null;
    if ((body.status === 'NOT_SEEDED') !== (reason === 'CONTACTS_NOT_SEEDED')) return null;
    return { status: body.status, reason, actors: null };
}

/** 200 만 본문을 검증해 넘기고, 그 밖의 상태(401 · 403 · 404 포함) · 모르는 본문은 실패로 돌린다. */
export async function readFrontier(generalId: number, signal?: AbortSignal): Promise<FrontierRead> {
    let res: Response;
    try {
        res = await fetchGame(`/api/frontier?generalId=${encodeURIComponent(String(generalId))}`, { cache: 'no-store', signal });
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
    const frontier = parseFrontier(body);
    return frontier ? { ok: true, frontier } : { ok: false, httpStatus: res.status };
}
