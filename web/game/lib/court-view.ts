// 조정(P-K01)의 보기 모델 — 발령 · 포상 · 조정 결정. React 없음.
//
// 받은 요청(발령 응답 · 정치 동의)은 K6 요청 카드(`components/requests`)가 그린다 — 여기서 만들지 않는다.
// 짓지 않는 것: 상사 비율 · 상한 · 쓸 수 있는 금(옛 화면 프론트 상수 · 프론트 합산 — 계약판 K4-15 서버 값 전까지 「준비 중」).

import type { PersonOption, TargetCandidate } from '@opensamguk/ui';
import type { Retinue } from './campaign-reads';
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

/** 조정 명령(부대 탈퇴 지시 · 현 포기 · 천도) 선택지. 수량 칸은 쓰지 않는다(해당 입력 없음). */
export function courtChoices(opt: CourtActionOptions | null): CourtChoice[] {
    return (opt?.choices ?? []).map((c, i) => ({
        key: `${i}:${JSON.stringify(c.arguments)}`,
        label: stripIdSuffix(c.label),
        available: c.available,
        reason: c.available ? null : c.reason?.trim() || '사유를 받지 못했습니다',
        code: c.available ? null : c.code ?? null,
        args: c.arguments,
    }));
}

export interface RewardTarget {
    readonly retainerId: number;
    readonly name: string;
    readonly loyalty: number;
    readonly picture: string | null;
    readonly imageServer: number;
}

/**
 * 상사 대상 — 내 부 인물(직속) 중 장수 카드(generalId 있음) — 옛 조정 결정 화면과 같은 거르기. 입력은 retainerId 로
 * 보낸다(`court.reward` {retainerId, money}).
 */
export function rewardTargets(retinue: Retinue | null): RewardTarget[] {
    return (retinue?.people ?? []).filter((p) => p.generalId != null).map((p) => ({ retainerId: p.retainerId, name: p.name, loyalty: p.loyalty, picture: p.picture, imageServer: p.imageServer }));
}

/**
 * 상사 규칙 — 서버 `CampaignBalance.REWARD_MONEY_PER_LOYALTY` · `REWARD_MAX_LOYALTY_GAIN`(logic/…/war/CampaignBalance.kt)과
 * `RewardExecutor` 의 충성 상한 100. 서버는 오른 충성과 상관없이 **적은 금 전부**를 낸다 — 그래서 화면이 미리 알려야 한다.
 * 계약판 K4-15(reward-options)가 오면 그 값으로 바꾼다. 서버 값과 같은지는 court-reward-rule 시험이 Kotlin 원문으로 잰다.
 */
export const REWARD_RULE = { moneyPerLoyalty: 100, maxGain: 10, loyaltyCap: 100 } as const;

export interface RewardPreview {
    /** 실제로 오를 충성. */
    readonly gain: number;
    /** 충성 없이 나가는 금(상한 · 100 미만 나머지 · 충성 100). */
    readonly wasted: number;
}

/** 금액 · 지금 충성으로 상사 결과를 미리 잰다(서버 RewardExecutor 와 같은 셈). 100 미만이면 서버가 TOO_SMALL 로 거절한다(gain 0). */
export function rewardPreview(money: number, loyalty: number): RewardPreview {
    const raw = Math.min(Math.floor(money / REWARD_RULE.moneyPerLoyalty), REWARD_RULE.maxGain);
    const gain = Math.max(0, Math.min(raw, REWARD_RULE.loyaltyCap - loyalty));
    return { gain, wasted: money - gain * REWARD_RULE.moneyPerLoyalty };
}

/**
 * 이번에 낼 수 있는 상사 금의 최대 — 사용자 결정 2026-10-01(원장 D16): 100 ≤ 금 ≤ max(100, min(10, 100 − 충성) × 100).
 * 충성을 올릴 수 있는 만큼까지만 받고, 충성 100이면 100(상을 내린 기록 · 결속 사건만 남는다). 서버 반영은 K4-22 대기 —
 * 그때까지 서버는 넘는 금도 받아 전부 낸다(RewardExecutor), 그래서 화면이 접수 전에 막는다.
 */
export function rewardMaxMoney(loyalty: number): number {
    const room = Math.max(0, Math.min(REWARD_RULE.maxGain, REWARD_RULE.loyaltyCap - loyalty));
    return Math.max(REWARD_RULE.moneyPerLoyalty, room * REWARD_RULE.moneyPerLoyalty);
}

/** 금액 칸 검사 — 양의 정수만. 창고 잔액은 서버가 판정한다(K4-15 전까지 화면이 짓지 않는다). */
export function rewardMoney(raw: string): number | null {
    const t = raw.trim();
    if (!/^\d+$/.test(t)) return null;
    const n = Number(t);
    return n > 0 && Number.isSafeInteger(n) ? n : null;
}
