// 상사 선택지 읽기(`GET /api/court/reward-options`) 계약 고정 응답 — 단위 · 화면 · 스모크 시험이 같이 쓴다.
//
// 모양은 계약판 docs/development/court-reward-options-read.md(백엔드 PR #1586 RewardOptionsDto)의 wire JSON 그대로다.
// 규칙 값은 계약 응답의 rule 이다(화면 코드에는 사본이 없다). fakeRewardServer 는 시험용 대역으로, 계약 표의 판정 순서와
// verdict별 null 표를 따라 응답을 만든다 — 화면이 이 값을 그대로 옮기는지만 본다.

export const RULE = { moneyPerLoyalty: 100, maxLoyaltyGainPerReward: 10, minimumMoney: 100, inputMaximumMoney: 1_000_000_000, loyaltyCap: 100 };
export const SNAPSHOT = { year: 200, month: 3, phase: 2 };
export const NOT_CHECKED = ['QUEUE_ADMISSION', 'REWARD_HISTORY', 'CONCURRENT_DEBITS', 'STATE_AFTER_SNAPSHOT'];

export type Wire = Record<string, unknown>;

export const network = (usableMoney: string, warehouseCount = 2): Wire =>
    ({ status: 'KNOWN', scope: 'NETWORK', noneReason: null, unavailableReason: null, usableMoney, warehouseCount });
export const isolated = (usableMoney: string): Wire =>
    ({ status: 'KNOWN', scope: 'ISOLATED', noneReason: null, unavailableReason: null, usableMoney, warehouseCount: usableMoney === '0' ? 0 : 1 });
export const noFunding = (noneReason: string): Wire =>
    ({ status: 'KNOWN', scope: 'NONE', noneReason, unavailableReason: null, usableMoney: '0', warehouseCount: 0 });
export const unavailableFunding = (unavailableReason: string): Wire =>
    ({ status: 'UNAVAILABLE', scope: null, noneReason: null, unavailableReason, usableMoney: null, warehouseCount: null });

/** 카드 — 충성에서 loyaltyRoom · maximumMoney 를 계약 예시(0/95/99/100 → 1000/500/100/100)대로 채운다. */
export function card(retainerId: number, loyalty: number, over: Wire = {}): Wire {
    const room = Math.max(0, Math.min(RULE.maxLoyaltyGainPerReward, RULE.loyaltyCap - loyalty));
    const missing = (over.funding as Wire | undefined)?.unavailableReason === 'RECIPIENT_MISSING';
    return {
        retainerId, recipientGeneralId: 100 + retainerId, name: missing ? null : `인물${retainerId}`, loyalty, loyaltyRoom: room,
        maximumMoney: Math.max(RULE.minimumMoney, room * RULE.moneyPerLoyalty), locationCityId: missing ? null : 2,
        funding: network('5120'), ...over,
    };
}

export interface FakeState {
    readonly generalId?: number;
    readonly cards: readonly Wire[];
    readonly queued?: Wire;
    readonly snapshot?: Wire;
    /** COVERED 의 차감 계획. 없으면 수도 창고 하나에서 전액. */
    readonly debits?: (card: Wire, money: number) => Wire[];
}

export interface FakeQuery {
    readonly generalId: number;
    readonly retainerId: number | null;
    /** 보낸 money 원문(서버는 RewardInput 문법으로 읽는다). */
    readonly money: string | null;
}

/** 계약 판정 순서: INVALID_AMOUNT → CARD → NO_AMOUNT → TOO_SMALL → OVER_CAP → FUNDING → STOCK → COVERED. */
export function preview(state: FakeState, retainerId: number, raw: string | null): Wire {
    const amount = raw != null && /^[1-9][0-9]{0,9}$/.test(raw) && Number(raw) <= RULE.inputMaximumMoney ? Number(raw) : null;
    const c = state.cards.find((x) => x.retainerId === retainerId && x.name !== null);
    const funding = c?.funding as Wire | undefined;
    const verdict = raw != null && amount == null ? 'INVALID_AMOUNT'
        : !c ? 'CARD_UNAVAILABLE'
        : amount == null ? 'NO_AMOUNT'
        : amount < RULE.minimumMoney ? 'TOO_SMALL'
        : amount > (c.maximumMoney as number) ? 'REWARD_OVER_CAP'
        : funding?.status !== 'KNOWN' ? 'FUNDING_UNAVAILABLE'
        : BigInt(amount) > BigInt(funding.usableMoney as string) ? 'INSUFFICIENT_STOCK'
        : 'COVERED_AT_SNAPSHOT';
    const effect = ['FUNDING_UNAVAILABLE', 'INSUFFICIENT_STOCK', 'COVERED_AT_SNAPSHOT'].includes(verdict);
    const gain = effect ? Math.min(Math.floor(amount! / RULE.moneyPerLoyalty), c!.loyaltyRoom as number) : null;
    const usable = verdict === 'INSUFFICIENT_STOCK' || verdict === 'COVERED_AT_SNAPSHOT' ? funding!.usableMoney : null;
    const debitPlan = verdict === 'COVERED_AT_SNAPSHOT'
        ? (state.debits ?? ((_c, m) => [{ cityId: 3, isCapital: true, take: String(m), balance: String(funding!.usableMoney), revision: '7' }]))(c!, amount!)
        : null;
    return {
        retainerId, money: amount, verdict,
        loyaltyGain: gain, loyaltyAfter: gain == null ? null : (c!.loyalty as number) + gain,
        moneyWithoutGain: gain == null ? null : amount! - gain * RULE.moneyPerLoyalty,
        usableMoney: usable, debitPlan, notChecked: [...NOT_CHECKED],
    };
}

export function readyBody(state: FakeState, q: FakeQuery): Wire {
    return {
        status: 'READY', reason: null, generalId: q.generalId,
        snapshot: state.snapshot ?? { ...SNAPSHOT }, rule: { ...RULE },
        queued: state.queued ?? { status: 'NONE', retainerId: null, money: null },
        cards: state.cards.map((c) => ({ ...c })),
        preview: q.retainerId == null ? null : preview(state, q.retainerId, q.money),
    };
}

export const closedBody = (generalId: number, status: string, reason: string): Wire =>
    ({ status, reason, generalId, snapshot: null, rule: null, queued: null, cards: null, preview: null });

/** 스모크용 — 요청 URL 의 쿼리를 읽어 응답한다. */
export function fakeRewardServer(state: FakeState) {
    return (url: URL): Wire => {
        const retainer = url.searchParams.get('retainerId');
        return readyBody(state, {
            generalId: Number(url.searchParams.get('generalId')),
            retainerId: retainer == null ? null : Number(retainer),
            money: url.searchParams.get('money'),
        });
    };
}
