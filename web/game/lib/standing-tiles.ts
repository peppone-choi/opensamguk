// 작전실 「맡겨 둔 일」 6칸(보드 V31K4WarRoom STANDING6) — 개인 턴을 쓰지 않고 스스로 굴러가는 것의 수. React 없음.
// 출병 → 군단(P-C01) · 배치 · 방침 · 공사 → 영지(P-T01 각 칸) · 계책 → 계책(P-S01) · 발령 → 조정 「발령 · 포상」(P-K01).
// 읽는 중은 「—」, 실패는 「?」(「없음」 · 0 으로 그리지 않는다 — 설계서 P-W01 상태 규칙).
import type { Policies, Posts, StratagemHand, Works } from './campaign-reads';
import type { DeployOptions, DispatchPendingResponse } from './types';

export type TileValue =
    | { readonly kind: 'loading' }
    | { readonly kind: 'error' }
    /** 규칙 · 시야 밖이라 서버가 수를 주지 않는다(READY 아님). */
    | { readonly kind: 'unavailable' }
    | { readonly kind: 'ready'; readonly value: string; readonly sub?: string; readonly alert?: boolean; readonly zero?: boolean };

export interface StandingTile {
    readonly key: 'deploy' | 'placement' | 'policy' | 'work' | 'stratagem' | 'dispatch';
    readonly label: string;
    /** 캠페인 화면 조각(`campaignHref`). */
    readonly slug: string;
    readonly value: TileValue;
}

/** 읽기 하나(useCampaignRead 모양). */
export interface TileRead<T> {
    readonly data: T | null;
    readonly error: string | null;
}

/** 출병 멈춤 코드 → 화면 글자(DeployForm.tsx 의 표와 같다). 모르는 코드는 원문 대신 「상태 확인 필요」. */
const STOP_LABELS: Readonly<Record<string, string>> = {
    ARRIVED: '도착', ENCOUNTER: '조우 중단', EDGE_BLOCKED: '통행로 폐쇄', ENCOUNTER_UNAVAILABLE: '진입 상태 확인 불가', BUDGET_EXHAUSTED: '행군 중',
};

function fromRead<T>(read: TileRead<T>, ready: (data: T) => TileValue): TileValue {
    if (read.error) return { kind: 'error' };
    if (!read.data) return { kind: 'loading' };
    return ready(read.data);
}

/** 수 한 칸. extra = 걸린 것은 없어도 기다리는 것(바꿈 대기 · 멈춤) — 있으면 0 칸이 아니다. */
const count = (n: number, opts: { readonly sub?: string; readonly extra?: number; readonly alert?: boolean } = {}): TileValue =>
    ({ kind: 'ready', value: String(n), sub: opts.sub, alert: opts.alert, zero: n === 0 && !opts.extra });

export function standingTiles(reads: {
    readonly deploy: TileRead<DeployOptions>;
    readonly posts: TileRead<Posts>;
    readonly policies: TileRead<Policies>;
    readonly works: TileRead<Works>;
    readonly hand: TileRead<StratagemHand>;
    readonly dispatches: TileRead<DispatchPendingResponse>;
}, generalId: number | null): readonly StandingTile[] {
    const deploy = fromRead(reads.deploy, (d) => {
        if (!d.order) return count(0);
        const stop = d.order.stop ? STOP_LABELS[d.order.stop] ?? '상태 확인 필요' : '행군 중';
        return { kind: 'ready', value: stop, alert: d.order.stop != null && d.order.stop !== 'BUDGET_EXHAUSTED' && d.order.stop !== 'ARRIVED' };
    });
    const placement = fromRead(reads.posts, (p) => {
        if (p.status !== 'READY') return { kind: 'unavailable' };
        const pending = p.cards.filter((c) => c.pending != null).length;
        return count(p.cards.filter((c) => c.active != null).length, { sub: pending ? `바꿈 대기 ${pending}` : undefined, extra: pending });
    });
    const policy = fromRead(reads.policies, (p) => {
        if (p.status !== 'READY') return { kind: 'unavailable' };
        const rows = [...p.counties, ...(p.commanderies ?? [])];
        const pending = rows.filter((r) => r.pending != null).length;
        return count(rows.filter((r) => r.active != null).length, { sub: pending ? `바꿈 대기 ${pending}` : undefined, extra: pending });
    });
    const work = fromRead(reads.works, (w) => {
        if (w.status !== 'READY') return { kind: 'unavailable' };
        const active = w.counties.filter((c) => c.active != null);
        const stopped = active.filter((c) => c.active?.stopReason != null || c.active?.stopReasonText != null).length;
        return count(active.length, { sub: stopped ? `${stopped} 멈춤` : undefined, extra: stopped, alert: stopped > 0 });
    });
    const stratagem = fromRead(reads.hand, (h) => (h.status !== 'READY' ? { kind: 'unavailable' } : count(h.cards.length, { sub: '손패' })));
    const dispatch = fromRead(reads.dispatches, (d) => {
        const open = (d.dispatches ?? []).filter((x) => x.status === 'PENDING');
        // 나에게 온 발령만 내가 응답한다. 내가 낸 발령은 상대의 응답을 기다린다.
        const toMe = open.filter((x) => generalId != null && x.targetId === generalId).length;
        const waiting = open.length - toMe;
        if (toMe > 0) return { kind: 'ready', value: `응답 ${toMe}`, sub: waiting ? `대기 ${waiting}` : undefined, alert: true };
        return count(waiting, { sub: waiting ? '대기' : undefined });
    });
    return [
        { key: 'deploy', label: '출병', slug: 'corps', value: deploy },
        { key: 'placement', label: '배치', slug: 'territory?view=placement', value: placement },
        { key: 'policy', label: '방침', slug: 'territory?view=policy', value: policy },
        { key: 'work', label: '공사', slug: 'territory?view=work', value: work },
        { key: 'stratagem', label: '계책', slug: 'stratagem', value: stratagem },
        { key: 'dispatch', label: '발령', slug: 'court?tab=orders', value: dispatch },
    ];
}

/** 칸마다 0(읽기는 다 됐고 걸린 것이 하나도 없다) — 「배치 · 방침은 영지에서」 안내를 붙인다. */
export function allZero(tiles: readonly StandingTile[]): boolean {
    return tiles.every((t) => t.value.kind === 'ready' && t.value.zero === true);
}
