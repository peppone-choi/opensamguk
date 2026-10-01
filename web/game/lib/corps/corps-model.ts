// 군단(P-C01) 모델 — `/api/corps` + 군 이름(`/api/visibility`) + 내 출병 명령(`/api/deploy/options`) + 군단 방침
// (`/api/policies`) + 편성 해제 후보(`court.releaseCorps` 조정 옵션). K6 설계서 §3.4. 서버가 준 것만 옮긴다:
// - 내 군단: 병력 · 행군 중인지 · 목적 구역(출병 옵션의 이름) · 방침(지금 · 다음 순부터), 남의 군단: 병력 구간(B1–B5) · 시야 · 「n순 전」.
// - 군단 방침은 `/api/corps` 의 corpsId(= 출병 orderId, 서버 VisionReader)로 방침 행(orderId)과 잇는다. 군단에는 기본 방침이
//   없다 — active 가 없으면 「방침 없음」(현 기본 방침 `defaultPolicy` 를 붙이지 않는다, #1141 판정).
// - 요격 범위 · 세력 작전 · 전투 잠김은 서버가 주지 않는다(그리지 않거나 서버 대기).
import type { CorpsList, Policies, Visibility } from '../campaign-reads';
import type { CourtActionOptions, DeployOptions } from '../types';

export interface CorpsRow {
    readonly corpsId: string;
    readonly own: boolean;
    readonly commander: { readonly generalId: number; readonly name: string | null };
    readonly owner: { readonly generalId: number; readonly name: string | null };
    readonly nationColor: string | null;
    /** 있는 곳 — 군(郡) 이름. 시야 읽기에 없으면 null. */
    readonly where: string | null;
    /** 내 군단만. */
    readonly troops: number | null;
    /** 남의 군단 — 병력 구간 이름표. */
    readonly band: string | null;
    readonly vision: string;
    /** 마지막으로 본 지 몇 순(INTEL). */
    readonly ageTurns: number | null;
    readonly marching: boolean;
    /** 목적 구역 이름 — 출병 옵션이 이름을 줄 때만. */
    readonly destination: string | null;
    /** 내 군단 방침 — 지금 방침 이름(없으면 null = 「방침 없음」) · 다음 순부터 바뀌는 방침. 방침을 못 읽었으면 policyKnown=false. */
    readonly policy: string | null;
    readonly pendingPolicy: string | null;
    readonly policyKnown: boolean;
}

export const STOP_LABEL: Readonly<Record<string, string>> = {
    ARRIVED: '도착', ENCOUNTER: '조우 중단', EDGE_BLOCKED: '통행로 폐쇄', ENCOUNTER_UNAVAILABLE: '진입 상태 확인 불가', BUDGET_EXHAUSTED: '행군 중',
};

export interface DeployOrderView {
    readonly destination: string | null;
    /** 멈춘 이유 이름표(지금 출병 폼과 같은 5개). 모르는 코드면 null. */
    readonly stop: string | null;
}

export function toCorpsRows(list: CorpsList | null, vision: Visibility | null, deploy: DeployOptions | null, policies: Policies | null = null): CorpsRow[] {
    if (!list || list.status !== 'READY') return [];
    const names = new Map((vision?.status === 'READY' ? vision.commanderies ?? [] : []).map((c) => [c.no, c.name]));
    const provinceName = new Map((deploy?.destinations ?? []).map((d) => [d.provinceId, d.name]));
    const policyReady = policies?.status === 'READY';
    const policyOf = new Map((policyReady ? policies!.corps : []).map((p) => [p.orderId, p]));
    return (list.corps ?? []).map((c) => {
        const p = c.own ? policyOf.get(c.corpsId) : undefined;
        return {
            corpsId: c.corpsId,
            own: c.own,
            commander: { generalId: c.commanderGeneralId, name: c.commanderName ?? null },
            owner: { generalId: c.ownerGeneralId, name: c.ownerName ?? null },
            nationColor: c.nationColor ?? null,
            where: names.get(c.commanderyNo) ?? null,
            troops: c.own ? c.troops ?? null : null,
            band: c.own ? null : c.troopsBand?.label ?? null,
            vision: c.visibility,
            ageTurns: c.ageTurns ?? null,
            marching: (c.marchPath?.length ?? 0) > 0,
            destination: c.destinationProvinceId ? provinceName.get(c.destinationProvinceId) ?? null : null,
            policy: p?.active?.label ?? null,
            pendingPolicy: p?.pending?.label ?? null,
            policyKnown: c.own && policyReady,
        };
    });
}

export function deployOrderOf(deploy: DeployOptions | null): DeployOrderView | null {
    const order = deploy?.order;
    if (!order) return null;
    return {
        destination: deploy!.destinations.find((d) => d.provinceId === order.destinationProvinceId)?.name ?? null,
        stop: order.stop ? STOP_LABEL[order.stop] ?? null : null,
    };
}

/** 편성 해제 — 그 군단 장수를 대상으로 한 조정 선택지. 없으면 null(단추 상태는 옵션 전체의 available · reason). */
export function releaseChoiceFor(options: CourtActionOptions | null, row: CorpsRow): CourtActionOptions['choices'][number] | null {
    if (!options) return null;
    return options.choices.find((ch) => {
        const target = ch.arguments.targetGeneralId;
        return target === row.commander.generalId || target === row.owner.generalId;
    }) ?? null;
}
