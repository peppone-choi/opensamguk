// P-C04 reads my corps policies and counties I personally govern from /api/policies.
// placed=false means a directly assigned general; placed=true means a retainer card.
// SeatDto has no controller id, so retainer-governed counties cannot be attributed to their owner here.
// Active battles and campaign-to-realtime battle tickets are not available yet.
import type { Policies } from '../campaign-reads';

export interface AbsenceRow {
    readonly key: string;
    readonly kind: 'corps' | 'county';
    readonly name: string;
    /** 지금 방침 이름 — 없으면 기본 방침 이름, 그것도 없으면 null. */
    readonly policy: string | null;
    /** 다음 순부터 바뀌는 방침. */
    readonly pending: string | null;
    readonly settable: boolean;
    readonly blocked: string | null;
}

export type AbsenceView =
    | { readonly state: 'unreadable'; readonly status: string }
    | { readonly state: 'ready'; readonly rows: readonly AbsenceRow[] };

export function toAbsence(p: Policies, me: number): AbsenceView {
    if (p.status !== 'READY') return { state: 'unreadable', status: p.status };
    const fallback = p.defaultPolicy?.label ?? null;
    const corps: AbsenceRow[] = p.corps.map((c) => ({
        key: `corps:${c.orderId}`,
        kind: 'corps',
        name: `${c.commanderName ?? '내'} 군단`,
        policy: c.active?.label ?? fallback,
        pending: c.pending?.label ?? null,
        settable: c.settable,
        blocked: c.blocked?.reason ?? null,
    }));
    const counties: AbsenceRow[] = p.counties.filter((c) => c.seat?.generalId === me && !c.seat.placed).map((c) => ({
        key: `county:${c.countyId}`,
        kind: 'county',
        name: c.name,
        policy: c.effective?.label ?? c.active?.label ?? fallback,
        pending: c.pending?.label ?? null,
        settable: c.settable,
        blocked: null,
    }));
    return { state: 'ready', rows: [...corps, ...counties] };
}

export const ABSENCE_NOTE = '없으면 AI가 맡습니다. 들어오면 다음 틱에 조작을 넘겨받습니다.';
export const BATTLE_NOT_OPEN = { title: '전투가 열리지 않습니다(서버 준비 중)', body: '조우가 나도 아직 실시간 전투로 이어지지 않습니다. 서버가 전투를 열면 여기에 참가 대기 · 진행 중인 전투가 보입니다.' };
