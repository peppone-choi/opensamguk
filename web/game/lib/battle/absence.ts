// 전투 · 부재 대비(P-C04) — 내가 없을 때 무엇이 싸우는지. `/api/policies`만 읽는다. K6 설계서 §3.5.
// - 내 출전 군단의 방침(지금 · 다음 순부터), 내가 맡은 현(배치 자리 — seat)의 방침.
// - 전투 목록(K6-11 `/api/battles/active`)은 서버에 없고, 캠페인 → 실시간 전투 티켓 배선도 아직 없다
//   (원장 CONTRACT:CAMPAIGN_BATTLE_PRODUCER) — 화면은 「전투가 열리지 않음(서버 준비 중)」으로 떨어진다.
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
    const counties: AbsenceRow[] = p.counties.filter((c) => c.seat?.generalId === me && c.seat.placed).map((c) => ({
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
