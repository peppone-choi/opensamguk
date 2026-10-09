// 조정(P-K01)의 보기 모델 — 발령 · 포상 · 조정 결정. React 없음.
//
// 받은 요청(발령 응답 · 정치 동의)은 K6 요청 카드(`components/requests`)가 그린다 — 여기서 만들지 않는다.
// 상사(court.reward)의 규칙 · 상한 · 쓸 수 있는 금 · 미리 보기는 서버 상사 선택지(lib/court-reward-view)가 맡는다 — 여기서 짓지 않는다.

import type { PersonOption, TargetCandidate } from '@opensamguk/ui';
import { phaseLabel } from './requests';
import type { CourtActionOptions, DispatchOptionsResponse, DispatchPendingItem, DispatchPendingResponse } from './types';

export const DISPATCH_STATUS_LABEL: Readonly<Record<DispatchPendingItem['status'], string>> = {
    PENDING: '응답 대기',
    ACCEPTED: '수락',
    REFUSED: '거절',
    CANCELLED: '취소',
};

export interface IssuedDispatchRow {
    readonly dispatchId: string;
    readonly target: string;
    readonly county: string;
    readonly status: string;
    readonly pending: boolean;
    /** 응답 기한(응답 대기일 때만). */
    readonly due: string | null;
    /** 응답 대기인데 지금 막힌 까닭(서버 currentFailure) — 서버 문장(K6-20 currentFailureReason)이 있으면 그대로, 없으면 옛 문구. */
    readonly blocked: string | null;
}

/** 서버가 막힌 까닭 문장을 주지 않을 때(null · 필드 없음 — 서버 반영 전 · 옛 응답) 쓰는 옛 조정 화면 문구. */
export const DISPATCH_BLOCKED_FALLBACK = '현재 관계나 목적지 조건으로 응답할 수 없습니다. 상태를 다시 확인해 주세요.';

/** 내가 내린 발령 — 나에게 온 발령은 받은 요청 카드(K6)가 그린다. 라벨이 없으면 id 대신 「이름 모름」. */
export function issuedDispatches(res: DispatchPendingResponse | null, meId: number | null): IssuedDispatchRow[] {
    if (!res || meId == null) return [];
    return res.dispatches.filter((d) => d.issuerId === meId).map((d) => ({
        dispatchId: d.dispatchId,
        target: d.targetLabel ?? '이름 모를 장수',
        county: d.countyLabel ?? '이름 모를 현',
        status: DISPATCH_STATUS_LABEL[d.status] ?? '알 수 없음',
        pending: d.status === 'PENDING',
        due: d.status === 'PENDING' ? phaseLabel(d.dueAt) : null,
        // 문장은 가공하지 않는다(문구는 C1 · C7) — 요청 카드(lib/requests.ts)와 같은 서버 필드.
        blocked: d.status === 'PENDING' && d.currentFailure ? (d.currentFailureReason?.trim() || DISPATCH_BLOCKED_FALLBACK) : null,
    }));
}

/** 접수만 된 발령(주공의 다음 개인 턴을 기다림). */
export const DISPATCH_QUEUED_TEXT = '발령을 접수했습니다 — 주공의 다음 개인 턴에 처리합니다.';

/** 발령할 사람 — dispatch-options.targets(내 부 사람 장수). 초상 · 소속은 서버가 주지 않는다. */
export function dispatchPeople(opt: DispatchOptionsResponse | null): PersonOption[] {
    return (opt?.targets ?? []).map((t) => ({ generalId: t.generalId, name: t.label, isHuman: true, groups: ['mine'] }));
}

/** 발령할 현 — 가능 · 불가를 사유와 함께(지도 대상 고르기 place). 옛 화면 「· 발령 불가」 꼬리 대신 사유. */
export function dispatchCounties(opt: DispatchOptionsResponse | null): TargetCandidate[] {
    return (opt?.counties ?? []).map((c) => ({
        targetKind: 'place',
        targetId: String(c.countyId),
        cityId: String(c.countyId),
        name: c.label,
        available: c.available,
        reasonCode: c.code ?? undefined,
        reason: c.available ? undefined : c.reason ?? undefined,
    }));
}

/**
 * 조정 명령 라벨에서 내부 id 꼬리를 뗀다 — 서버 `CourtActionOptionsService` 가 「이름 (id)」 로 준다(설계서 P-K01, 서버 정리는 C10).
 * 꼬리만 떼고 이름은 그대로.
 */
export function stripIdSuffix(label: string): string {
    return label.replace(/\s*\(\d+\)\s*$/, '');
}

export interface CourtChoice {
    readonly key: string;
    readonly label: string;
    readonly available: boolean;
    readonly reason: string | null;
    readonly code: string | null;
    readonly args: Readonly<Record<string, string | number>>;
}

const COURT_RESOURCE_LABELS: Readonly<Record<string, string>> = {
    MONEY: '금', GRAIN: '쌀', IRON: '철', TIMBER: '목재', HORSES: '말',
};

/** Translate only the resource suffix matching the submitted argument; preserve target names. */
function courtChoiceLabel(choice: CourtActionOptions['choices'][number]): string {
    const label = stripIdSuffix(choice.label);
    const resource = choice.arguments.resource;
    if (typeof resource !== 'string' || !/^[A-Z][A-Z0-9_]*$/.test(resource)
        || !label.endsWith(` · ${resource}`)) return label;
    return `${label.slice(0, -resource.length)}${COURT_RESOURCE_LABELS[resource] ?? '알 수 없는 물자'}`;
}

/** 조정 명령(부대 탈퇴 지시 · 현 포기 · 천도) 선택지. 수량 칸은 쓰지 않는다(해당 입력 없음). */
export function courtChoices(opt: CourtActionOptions | null): CourtChoice[] {
    return (opt?.choices ?? []).map((c, i) => ({
        key: `${i}:${JSON.stringify(c.arguments)}`,
        label: courtChoiceLabel(c),
        available: c.available,
        reason: c.available ? null : c.reason?.trim() || '사유를 받지 못했습니다',
        code: c.available ? null : c.code ?? null,
        args: c.arguments,
    }));
}
