// 전투 · 부재 대비(P-C04) — 내가 없을 때 무엇이 싸우는지. `/api/policies`만 읽는다. K6 설계서 §3.5.
// - 내 출전 군단의 방침(지금 · 다음 순부터). 군단엔 기본 방침이 없다 — active 가 없으면 반응(요격 · 회피)에 들지 않는다
//   (엔진 ReactionInventory). `defaultPolicy`는 현 기본 방침이라 군단 행에 붙이지 않는다.
// - 내가 직접 맡은 현: seat.placed=false 가 발령된 장수 본인, placed=true 는 수하 카드(자리 사람 = 카드 인물).
//   SeatDto 에 자리 주인(controller) id 가 없어 수하에게 맡긴 현은 여기서 내 것으로 가를 수 없다.
// - 전투 목록(K6-11 `/api/battles/active`)은 별도 읽기다. 목록 응답만으로 캠페인 producer의 가용성을 추정하지 않는다.
import type { Policies } from '../campaign-reads';

export interface AbsenceRow {
    readonly key: string;
    readonly kind: 'corps' | 'county';
    readonly name: string;
    /** 지금 방침 이름. 현은 없으면 현 기본 방침 이름, 군단은 없으면 null(「방침 없음」). */
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
        policy: c.active?.label ?? null,
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

export const BATTLE_LIST_STATUS = {
    waiting: { title: '전투 목록을 아직 확인하지 못했습니다', body: '목록을 불러온 뒤 현재 전투를 확인해 주세요.' },
    empty: { title: '조회된 전투가 없습니다', body: '이번 조회에서 전투 목록이 비어 있습니다. 실시간 전투 제공 여부는 아직 확인되지 않았습니다.' },
    unauthorized: { title: '전투 목록을 보려면 로그인해 주세요', body: '로그인 상태를 확인한 뒤 게임에 다시 들어와 주세요.' },
    forbidden: { title: '이 장수의 전투 목록을 볼 권한이 없습니다', body: '본인 장수로 게임에 들어와 있는지 확인해 주세요.' },
    sourceUnavailable: { title: '전투 목록 원천을 사용할 수 없습니다', body: '서버가 전투 목록을 제공할 수 없다고 응답했습니다. 전투가 없다는 뜻은 아닙니다. 잠시 후 다시 읽어 주세요.' },
} as const;
