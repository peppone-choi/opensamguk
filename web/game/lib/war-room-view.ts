// 작전실(P-W01) 중 지도와 무관한 칸의 보기 모델 — 「맡겨 둔 일」 6칸. React 없음.
//
// 받기 전(undefined)은 「—」 — 「없음」 으로 보이지 않는다(옛 StandingBar 가 로딩 중 「출병 없음」 을 보였다, 설계서 P-W01).
// 계책 칸은 지금 손패 수(설치 계책 수는 서버가 따로 주지 않는다).

import type { Policies, Posts, StratagemHand, Works } from './campaign-reads';
import type { DeployOptions, DispatchPendingResponse } from './types';

/** 출병 멈춤 코드 → 글자(옛 DeployForm 표 그대로). 모르는 코드는 원문 대신 「멈춤(사유 준비 중)」. */
export const DEPLOY_STOP_LABEL: Readonly<Record<string, string>> = {
    ARRIVED: '도착',
    ENCOUNTER: '조우로 멈춤',
    EDGE_BLOCKED: '통행로 폐쇄',
    ENCOUNTER_UNAVAILABLE: '진입 상태 확인 불가',
    BUDGET_EXHAUSTED: '행군 중',
};

export type EntrustedKey = 'deploy' | 'placement' | 'policy' | 'work' | 'stratagem' | 'dispatch';

export interface EntrustedCell {
    readonly key: EntrustedKey;
    readonly label: string;
    /** 「—」(받기 전) · 「없음」 · 「행군 중」 · 「3」 … */
    readonly value: string;
    /** 내 응답이 필요한 수(발령 칸만). */
    readonly alert: number | null;
}

type Maybe<T> = T | null | undefined;

const count = (n: Maybe<number>) => (n === undefined || n === null ? '—' : String(n));

export function deployValue(opt: Maybe<DeployOptions>): string {
    if (opt === undefined || opt === null) return '—';
    const order = opt.order;
    if (!order) return '없음';
    if (!order.stop) return '행군 중';
    return DEPLOY_STOP_LABEL[order.stop] ?? '멈춤(사유 준비 중)';
}

export interface EntrustedInput {
    readonly deploy?: DeployOptions | null;
    readonly posts?: Posts | null;
    readonly policies?: Policies | null;
    readonly works?: Works | null;
    readonly hand?: StratagemHand | null;
    readonly dispatches?: DispatchPendingResponse | null;
    readonly generalId: number | null;
}

/** 6칸 — 출병 · 배치 · 방침 · 공사 · 계책 · 발령. 조회 하나가 실패해도(null) 그 칸만 「—」. */
export function entrustedCells(input: EntrustedInput): EntrustedCell[] {
    const ready = <T extends { status: string }>(v: Maybe<T>) => (v && v.status === 'READY' ? v : null);
    const posts = ready(input.posts);
    const policies = ready(input.policies);
    const works = ready(input.works);
    const hand = ready(input.hand);
    const pending = input.dispatches ? input.dispatches.dispatches.filter((d) => d.status === 'PENDING') : null;
    const me = input.generalId;
    return [
        { key: 'deploy', label: '출병', value: deployValue(input.deploy), alert: null },
        { key: 'placement', label: '배치', value: count(posts?.cards.filter((c) => c.active || c.pending).length), alert: null },
        {
            key: 'policy', label: '방침',
            value: count(policies ? policies.counties.filter((c) => c.active).length + (policies.commanderies ?? []).filter((c) => c.active).length
                + policies.corps.filter((c) => c.active).length : null),
            alert: null,
        },
        { key: 'work', label: '공사', value: count(works?.counties.filter((c) => c.active).length), alert: null },
        { key: 'stratagem', label: '계책 손패', value: count(hand?.cards.length), alert: null },
        {
            key: 'dispatch', label: '발령 진행', value: count(pending?.length),
            alert: pending && me != null ? pending.filter((d) => d.targetId === me).length || null : null,
        },
    ];
}
