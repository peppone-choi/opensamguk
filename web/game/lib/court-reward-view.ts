// 상사(court.reward) 보기 모델 — 서버 상사 선택지(lib/api/court-reward)를 화면 문장으로 옮긴다. React 없음.
//
// 규칙 · 상한 · 카드 · 창고 금 · 미리 보기는 모두 서버 값이다. 화면은 셈하지 않고(프론트 규칙 사본 없음) 받은 필드만 보인다.
// null 은 0 이 아니다 — 모르는 값은 「확인할 수 없음」, 아는 0 은 「금 0」으로 따로 보인다.
// 창고 금 · 차감량 · 잔액은 십진 문자열 그대로 자릿수만 묶는다(Number 로 바꾸지 않는다).
// 추정치는 저장된 스냅샷 기준이며 접수 · 지급 확정이 아니다. 접수와 실행이 다시 검사한다.

import type { Retinue } from './campaign-reads';
import type {
    RewardCard, RewardFunding, RewardFundingUnavailableReason, RewardNoneReason, RewardOptionsReady, RewardPreview,
    RewardQueued, RewardReadFailure, RewardRule, RewardSnapshot, RewardUnchecked,
} from './court-reward-types';
import { TURN_PHASE_LABELS } from './format';

/** 「200년 3월 중순」. */
const snapshotLabel = (s: RewardSnapshot) => `${s.year}년 ${s.month}월 ${TURN_PHASE_LABELS[s.phase - 1]}`;

/** 접수(202)만 됐다 — 처리는 다음 개인 턴. 옛 조정 화면 문구 그대로. */
export const REWARD_QUEUED_TEXT = '상사를 접수했습니다 — 다음 개인 턴에 처리합니다.';
export const REWARD_DENIED_FALLBACK = '접수하지 못했습니다.';
export const REWARD_SEND_FAILED = '보내지 못했습니다 — 다시 해 보세요.';
export const REWARD_READ_FAILED = '상사 선택지를 불러오지 못했습니다';
/** 권한 · 공개 상태로 막힌 읽기의 제목 — 서버 원문은 보이지 않는다. 그 밖의 실패는 REWARD_READ_FAILED 한 문구. */
const READ_DENIED: Partial<Readonly<Record<RewardReadFailure, string>>> = {
    AUTH_REQUIRED: '로그인이 필요합니다 — 다시 로그인한 뒤 시도해 주세요.',
    FORBIDDEN: '이 장수의 상사 선택지를 볼 수 없습니다.',
    ADMISSION_NOT_PUBLIC: '서버가 아직 공개되지 않아 상사 선택지를 읽지 않습니다.',
    ADMISSION_UNAVAILABLE: '서버 공개 상태를 확인하지 못해 상사 선택지를 읽지 않습니다.',
};
export const rewardReadErrorText = (failure: RewardReadFailure): string => READ_DENIED[failure] ?? REWARD_READ_FAILED;
/** 받는 인물 행이 없을 때 — 부 인물 카드의 이름으로 채우지 않는다. */
export const REWARD_UNKNOWN_NAME = '이름 모를 인물';

/** 금액 칸 검사 — 양의 정수만(앞뒤 빈칸 · 앞자리 0 은 정규화). 조회 · 접수에는 이 정규화 값만 보낸다. */
export function rewardMoney(raw: string): number | null {
    const t = raw.trim();
    if (!/^\d+$/.test(t)) return null;
    const n = Number(t);
    return n > 0 && Number.isSafeInteger(n) ? n : null;
}

/** 십진 문자열의 자릿수 묶기 — 문자열로만 다룬다(2^53 을 넘어도 정확). 십진 문자열이 아니면 그대로. */
export function groupDigits(decimal: string): string {
    return /^\d+$/.test(decimal) ? decimal.replace(/\B(?=(\d{3})+(?!\d))/g, ',') : decimal;
}

/** 상한이 있는 수(규칙 · 금액 · 충성 없이 나가는 금)의 자릿수 묶기. */
const won = (n: number) => groupDigits(String(n));

const NONE_REASON: Readonly<Record<RewardNoneReason, string>> = {
    PAYER_LANDLESS: '소속 세력이 없어 낼 창고가 없습니다.',
    LOCATION_FOREIGN: '받는 인물이 다른 세력 땅에 있어 낼 창고가 없습니다.',
    LOCATION_NEUTRAL: '받는 인물이 주인 없는 땅에 있어 낼 창고가 없습니다.',
};

const UNAVAILABLE_REASON: Readonly<Record<RewardFundingUnavailableReason, string>> = {
    RECIPIENT_MISSING: '받는 인물을 찾지 못했습니다.',
    LOCATION_UNKNOWN: '받는 인물의 위치를 확인하지 못했습니다.',
    PAYER_NATION_MISSING: '낼 세력을 확인하지 못했습니다.',
    WAREHOUSE_MALFORMED: '창고 기록을 읽지 못했습니다.',
    TOTAL_OVERFLOW: '창고 금 합계를 셀 수 없습니다.',
};

const REWARD_UNCHECKED_LABEL: Readonly<Record<RewardUnchecked, string>> = {
    QUEUE_ADMISSION: '접수 가능 여부',
    REWARD_HISTORY: '상사 이력',
    CONCURRENT_DEBITS: '동시 차감',
    STATE_AFTER_SNAPSHOT: '조회 이후 상태',
};

export interface RewardUsableView {
    /** known = 창고 금을 앎(0 포함) · unavailable = 확인할 수 없음. */
    readonly state: 'known' | 'unavailable';
    /** 「금 1,234」 · 「금 0」 · 「확인할 수 없음」. */
    readonly amount: string;
    readonly note: string;
}

export function rewardUsable(funding: RewardFunding): RewardUsableView {
    if (funding.status === 'UNAVAILABLE') {
        return { state: 'unavailable', amount: '확인할 수 없음', note: UNAVAILABLE_REASON[funding.unavailableReason] };
    }
    const amount = `금 ${groupDigits(funding.usableMoney)}`;
    if (funding.scope === 'NONE') return { state: 'known', amount, note: NONE_REASON[funding.noneReason] };
    if (funding.scope === 'ISOLATED') return { state: 'known', amount, note: '고립된 현의 창고만 셉니다.' };
    return { state: 'known', amount, note: `보급망 창고 ${funding.warehouseCount}곳의 합계입니다.` };
}

export interface RewardCardView {
    readonly retainerId: number;
    readonly name: string;
    readonly loyalty: number;
    readonly picture: string | null;
    readonly imageServer: number;
    readonly usable: RewardUsableView;
}

/**
 * 상사 대상 — 서버 카드가 기준이다. 부 인물 카드는 카드 ID 와 인물 장수 ID 가 둘 다 같을 때만 초상을 보탠다.
 * 서버가 이름을 주지 않으면 「이름 모를 인물」 — 부 인물 카드의 이름으로 되살리지 않는다.
 */
export function rewardCards(cards: readonly RewardCard[], retinue: Retinue | null): RewardCardView[] {
    return cards.map((c) => {
        const person = retinue?.people.find((p) => p.retainerId === c.retainerId && p.generalId === c.recipientGeneralId);
        return {
            retainerId: c.retainerId,
            name: c.name ?? REWARD_UNKNOWN_NAME,
            loyalty: c.loyalty,
            picture: person?.picture ?? null,
            imageServer: person?.imageServer ?? 0,
            usable: rewardUsable(c.funding),
        };
    });
}

export function rewardRuleText(rule: RewardRule): string {
    return `금 ${won(rule.moneyPerLoyalty)}당 충성 +1 · 한 번에 최대 +${rule.maxLoyaltyGainPerReward} · 충성은 ${rule.loyaltyCap}까지 — 충성을 올릴 수 있는 만큼까지만 냅니다.`;
}

/** 대기 상사 진단 — 접수를 막지 않는다(접수 판단은 서버). */
export function rewardQueueText(queued: RewardQueued): string | null {
    if (queued.status === 'QUEUED') return '대기 중인 상사 기록이 있습니다 — 새 상사의 접수 여부는 접수할 때 서버가 정합니다.';
    if (queued.status === 'UNAVAILABLE') return '대기 중인 상사 기록을 읽지 못했습니다.';
    return null;
}

export interface RewardDebitView {
    readonly key: string;
    readonly text: string;
}

export type RewardPreviewInput =
    | { readonly state: 'idle' | 'loading' | 'error' }
    | { readonly state: 'ready'; readonly preview: RewardPreview };

export interface RewardPanelInput {
    readonly options: RewardOptionsReady;
    readonly retinue: Retinue | null;
    readonly selected: number | null;
    readonly money: number | null;
    readonly preview: RewardPreviewInput;
}

export interface RewardPanelView {
    readonly rule: string;
    readonly snapshot: string;
    readonly queue: string | null;
    readonly cards: readonly RewardCardView[];
    /** 고른 인물의 창고 금. 고르기 전이면 null. */
    readonly usable: RewardUsableView | null;
    /** 접수를 막는 까닭. null 이면 접수할 수 있다(창고 금 경고는 막지 않는다). */
    readonly blocked: string | null;
    readonly previewState: RewardPreviewInput['state'];
    /** 인물 · 금액이 정해졌고 그 미리 보기를 기다린다. */
    readonly checking: boolean;
    readonly effect: { readonly text: string; readonly warn: boolean } | null;
    readonly stock: { readonly text: string; readonly warn: boolean } | null;
    readonly debits: readonly RewardDebitView[];
    readonly unchecked: string | null;
}

function blockedBy(preview: RewardPreview, card: RewardCard | undefined, rule: RewardRule): string | null {
    switch (preview.verdict) {
        case 'INVALID_AMOUNT': return `금액은 최대 ${won(rule.inputMaximumMoney)}까지 적을 수 있습니다.`;
        case 'CARD_UNAVAILABLE': return '이 인물에게는 지금 상사를 내릴 수 없습니다.';
        case 'NO_AMOUNT': return '금액을 1 이상의 정수로 적으세요.';
        case 'TOO_SMALL': return `금 ${won(rule.minimumMoney)} 이상이어야 충성이 오릅니다.`;
        case 'REWARD_OVER_CAP':
            if (!card) return '이 인물에게는 지금 상사를 내릴 수 없습니다.';
            return card.loyaltyRoom === 0
                ? `충성은 이미 ${rule.loyaltyCap}입니다 — 금 ${won(card.maximumMoney)}으로 상을 내린 기록만 남길 수 있습니다.`
                : `이번에 충성을 올릴 수 있는 금은 최대 ${won(card.maximumMoney)}입니다.`;
        default: return null;
    }
}

function effectOf(preview: RewardPreview, card: RewardCard | undefined, rule: RewardRule): RewardPanelView['effect'] {
    if (preview.verdict !== 'FUNDING_UNAVAILABLE' && preview.verdict !== 'INSUFFICIENT_STOCK' && preview.verdict !== 'COVERED_AT_SNAPSHOT') return null;
    const waste = preview.moneyWithoutGain;
    if (card?.loyaltyRoom === 0) return { text: `충성은 이미 ${rule.loyaltyCap}입니다 — 상을 내린 기록 · 결속 사건만 남습니다`, warn: waste > 0 };
    const tail = waste > 0 ? ` — 충성 없이 나가는 금 ${won(waste)}(${won(rule.moneyPerLoyalty)} 단위 나머지)` : '';
    return { text: `충성 +${preview.loyaltyGain}${tail} · 상사 뒤 충성 ${preview.loyaltyAfter}`, warn: waste > 0 };
}

function stockOf(preview: RewardPreview): RewardPanelView['stock'] {
    if (preview.verdict === 'FUNDING_UNAVAILABLE') return { text: '창고 금을 확인할 수 없습니다 — 접수는 할 수 있고, 실행 때 다시 확인합니다.', warn: true };
    if (preview.verdict === 'INSUFFICIENT_STOCK') {
        return { text: `저장된 창고 금 ${groupDigits(preview.usableMoney)}으로는 모자라 보입니다 — 접수는 할 수 있고, 실행 때 다시 확인합니다.`, warn: true };
    }
    if (preview.verdict === 'COVERED_AT_SNAPSHOT') return { text: '조회 시점 창고로 지급 가능 · 실행 때 다시 확인', warn: false };
    return null;
}

function debitsOf(preview: RewardPreview): RewardDebitView[] {
    if (preview.verdict !== 'COVERED_AT_SNAPSHOT') return [];
    return preview.debitPlan.map((d, i) => ({
        key: `${d.cityId}`,
        text: `${i + 1}번째 창고${d.isCapital ? '(수도)' : ''}에서 금 ${groupDigits(d.take)} — 저장된 잔액 ${groupDigits(d.balance)}`,
    }));
}

/** 접수 칸의 보기 — 막는 까닭은 서버 판정에서만 나온다(금액 칸의 정수 문법만 화면이 본다). */
export function rewardPanelView({ options, retinue, selected, money, preview }: RewardPanelInput): RewardPanelView {
    const { rule } = options;
    const card = options.cards.find((c) => c.retainerId === selected);
    const ready = preview.state === 'ready' ? preview.preview : null;
    const blocked = card == null ? '상사할 인물을 고르세요.'
        : money == null ? '금액을 1 이상의 정수로 적으세요.'
        : preview.state === 'loading' || preview.state === 'idle' ? '금액을 확인하는 중입니다.'
        : ready == null ? '미리 보기를 불러오지 못했습니다 — 다시 시도해 주세요.'
        : blockedBy(ready, card, rule);
    const shown = ready && card && money != null ? ready : null;
    return {
        rule: rewardRuleText(rule),
        snapshot: `${snapshotLabel(options.snapshot)}에 저장된 값으로 낸 추정치입니다 — 접수나 지급이 확정된 것이 아닙니다.`,
        queue: rewardQueueText(options.queued),
        cards: rewardCards(options.cards, retinue),
        usable: card ? rewardUsable(card.funding) : null,
        blocked,
        previewState: preview.state,
        checking: card != null && money != null && (preview.state === 'loading' || preview.state === 'idle'),
        effect: shown ? effectOf(shown, card, rule) : null,
        stock: shown ? stockOf(shown) : null,
        debits: shown ? debitsOf(shown) : [],
        unchecked: shown && blocked == null
            ? `이 추정치가 확인하지 않은 것 — ${shown.notChecked.map((k) => REWARD_UNCHECKED_LABEL[k]).join(' · ')}`
            : null,
    };
}
