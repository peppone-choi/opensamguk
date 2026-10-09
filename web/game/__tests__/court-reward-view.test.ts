// 상사 보기 모델(lib/court-reward-view) — 서버 값만 옮기는지: 알려진 0 · 확인할 수 없음 · 큰 십진 문자열 · 이름 없음 · 초상 보충 ·
// 판정별 막는 까닭(창고 금 경고는 막지 않음) · 확인하지 않은 네 가지.
import { describe, expect, it } from 'vitest';
import { parseRewardOptions } from '@/lib/api/court-reward';
import type { Retinue } from '@/lib/campaign-reads';
import type { RewardOptionsQuery, RewardOptionsReady } from '@/lib/court-reward-types';
import {
    REWARD_UNKNOWN_NAME, groupDigits, rewardCards, rewardMoney, rewardPanelView, rewardUsable, type RewardPreviewInput,
} from '@/lib/court-reward-view';
import { card, network, noFunding, readyBody, unavailableFunding, type FakeState } from './fixtures/court-reward';

const STATE: FakeState = { cards: [
    card(4, 95, { funding: network('9007199254740993123', 3) }),
    card(5, 0, { funding: unavailableFunding('WAREHOUSE_MALFORMED') }),
    card(6, 60, { funding: noFunding('LOCATION_FOREIGN') }),
    card(8, 100),
    card(9, 40, { funding: unavailableFunding('RECIPIENT_MISSING') }),
] };

function ready(retainerId: number | null = null, money: number | null = null): RewardOptionsReady {
    const query: RewardOptionsQuery = { generalId: 7, retainerId, money };
    const r = parseRewardOptions(readyBody(STATE, { ...query, money: money == null ? null : String(money) }), query);
    if (r?.status !== 'READY') throw new Error('고정 응답이 계약을 어겼다');
    return r;
}

function viewFor(retainerId: number | null, money: number | null, state: RewardPreviewInput['state'] = 'ready') {
    const options = ready(retainerId, money);
    const preview: RewardPreviewInput = state === 'ready' && options.preview ? { state, preview: options.preview } : { state: state === 'ready' ? 'idle' : state };
    return rewardPanelView({ options, retinue: null, selected: retainerId, money, preview });
}

describe('수 · 금액 칸', () => {
    it('십진 문자열은 문자열로만 묶는다 — 2^53 를 넘어도 자릿수가 정확', () => {
        expect(groupDigits('0')).toBe('0');
        expect(groupDigits('100')).toBe('100');
        expect(groupDigits('1000')).toBe('1,000');
        expect(groupDigits('9007199254740993123')).toBe('9,007,199,254,740,993,123');
        expect(groupDigits('abc')).toBe('abc');
    });
    it('금액 칸 정규화는 그대로 — 앞뒤 빈칸 · 앞자리 0 은 양의 정수로, 0 · 소수 · 음수 · 안전 범위 밖은 null', () => {
        expect([rewardMoney('300'), rewardMoney('0'), rewardMoney('1.5'), rewardMoney(' 12 '), rewardMoney('0100'), rewardMoney('-5'), rewardMoney('9007199254740993')])
            .toEqual([300, null, null, 12, 100, null, null]);
    });
});

describe('창고 금 — 알려진 0 과 확인할 수 없음은 다르다', () => {
    it('KNOWN NETWORK · NONE(금 0 과 사유) · UNAVAILABLE(확인할 수 없음과 사유)', () => {
        const [a, b, c] = ready().cards;
        expect(rewardUsable(a.funding)).toEqual({ state: 'known', amount: '금 9,007,199,254,740,993,123', note: '보급망 창고 3곳의 합계입니다.' });
        expect(rewardUsable(b.funding)).toEqual({ state: 'unavailable', amount: '확인할 수 없음', note: '창고 기록을 읽지 못했습니다.' });
        expect(rewardUsable(c.funding)).toEqual({ state: 'known', amount: '금 0', note: '받는 인물이 다른 세력 땅에 있어 낼 창고가 없습니다.' });
        expect(rewardUsable(b.funding).amount).not.toContain('0');
    });
});

describe('카드 — 서버 카드가 기준, 초상은 카드 ID · 인물 ID 가 둘 다 같을 때만', () => {
    const person = (retainerId: number, generalId: number | null, name: string) =>
        ({ retainerId, generalId, name, picture: `${name}.png`, imageServer: 1, loyalty: 1 });
    const retinue = { status: 'READY', people: [
        person(4, 104, '부 이름 넷'),      // 둘 다 같음 → 초상
        person(5, 999, '다른 인물'),       // 카드 ID 만 같음 → 없음
        person(105, 105, '번호 뒤섞임'),   // 인물 ID 만 같음(카드 5 의 recipientGeneralId) → 없음
        person(9, 109, '되살리면 안 됨'),  // 이름 없는 카드 — 초상은 둘 다 같아 보태지만 이름은 쓰지 않는다
    ] } as unknown as Retinue;

    it('카드 ID ≠ 인물 ID 를 섞지 않는다, 서버 이름이 없으면 「이름 모를 인물」', () => {
        const cards = rewardCards(ready().cards, retinue);
        expect(cards.map((c) => [c.retainerId, c.name, c.picture])).toEqual([
            [4, '인물4', '부 이름 넷.png'],
            [5, '인물5', null],
            [6, '인물6', null],
            [8, '인물8', null],
            [9, REWARD_UNKNOWN_NAME, '되살리면 안 됨.png'],
        ]);
        expect(cards.map((c) => c.name)).not.toContain('되살리면 안 됨');
    });
});

describe('보기 — 규칙 · 막는 까닭은 서버 값에서', () => {
    it('규칙 문장은 응답의 rule 로 만든다(다른 값이면 다른 문장)', () => {
        const options = ready();
        const view = rewardPanelView({ options: { ...options, rule: { ...options.rule, moneyPerLoyalty: 250, maxLoyaltyGainPerReward: 4, loyaltyCap: 80 } },
            retinue: null, selected: null, money: null, preview: { state: 'idle' } });
        expect(view.rule).toBe('금 250당 충성 +1 · 한 번에 최대 +4 · 충성은 80까지 — 충성을 올릴 수 있는 만큼까지만 냅니다.');
        expect(view.snapshot).toBe('200년 3월 중순에 저장된 값으로 낸 추정치입니다 — 접수나 지급이 확정된 것이 아닙니다.');
    });
    it('고르기 전 · 금액 없음 · 확인 중 · 읽기 실패', () => {
        expect(viewFor(null, null).blocked).toBe('상사할 인물을 고르세요.');
        expect(viewFor(4, null).blocked).toBe('금액을 1 이상의 정수로 적으세요.');
        expect(viewFor(4, 150, 'loading')).toMatchObject({ blocked: '금액을 확인하는 중입니다.', checking: true, effect: null });
        expect(viewFor(4, 150, 'error')).toMatchObject({ blocked: '미리 보기를 불러오지 못했습니다 — 다시 시도해 주세요.', effect: null, stock: null });
    });
    it.each([
        [4, 10_000_000_000, '금액은 최대 1,000,000,000까지 적을 수 있습니다.'],
        [77, 150, null],
        [4, 99, '금 100 이상이어야 충성이 오릅니다.'],
        [4, 501, '이번에 충성을 올릴 수 있는 금은 최대 500입니다.'],
        [8, 150, '충성은 이미 100입니다 — 금 100으로 상을 내린 기록만 남길 수 있습니다.'],
    ])('막는 판정 — 카드 %i 금 %i', (retainerId, money, reason) => {
        const options = ready(retainerId, money);
        const view = rewardPanelView({ options, retinue: null, selected: retainerId, money, preview: { state: 'ready', preview: options.preview! } });
        // 받은 카드에 없는 인물(77)은 고른 것으로 치지 않는다.
        expect(view.blocked).toBe(reason ?? '상사할 인물을 고르세요.');
        expect(view.effect).toBeNull();
        expect(view.stock).toBeNull();
    });
    it('창고 금을 확인할 수 없음(FUNDING_UNAVAILABLE)은 막지 않고 알린다', () => {
        const view = viewFor(5, 150);
        expect(view.blocked).toBeNull();
        expect(view.effect).toEqual({ text: '충성 +1 — 충성 없이 나가는 금 50(100 단위 나머지) · 상사 뒤 충성 1', warn: true });
        expect(view.stock).toEqual({ text: '창고 금을 확인할 수 없습니다 — 접수는 할 수 있고, 실행 때 다시 확인합니다.', warn: true });
        expect(view.usable?.amount).toBe('확인할 수 없음');
        expect(view.debits).toEqual([]);
    });
    it('모자라 보임(INSUFFICIENT_STOCK, 알려진 0)도 막지 않는다', () => {
        const view = viewFor(6, 200);
        expect(view.blocked).toBeNull();
        expect(view.effect?.text).toBe('충성 +2 · 상사 뒤 충성 62');
        expect(view.stock?.text).toBe('저장된 창고 금 0으로는 모자라 보입니다 — 접수는 할 수 있고, 실행 때 다시 확인합니다.');
        expect(view.usable).toMatchObject({ state: 'known', amount: '금 0' });
    });
    it('조회 시점 지급 가능(COVERED) — 확정이 아니라 추정, 창고별 계획은 문자열 그대로, 확인하지 않은 네 가지', () => {
        const view = viewFor(4, 150);
        expect(view.blocked).toBeNull();
        expect(view.stock).toEqual({ text: '조회 시점 창고로 지급 가능 · 실행 때 다시 확인', warn: false });
        expect(view.debits.map((d) => d.text)).toEqual(['1번째 창고(수도)에서 금 150 — 저장된 잔액 9,007,199,254,740,993,123']);
        expect(view.unchecked).toBe('이 추정치가 확인하지 않은 것 — 접수 가능 여부 · 상사 이력 · 동시 차감 · 조회 이후 상태');
        expect(`${view.stock?.text}${view.snapshot}`).not.toMatch(/보장|확정됐|예약/);
    });
    it('충성 100 · 금 100 — 기록 · 결속 사건만 남는다고 알리고 막지 않는다', () => {
        const view = viewFor(8, 100);
        expect(view.blocked).toBeNull();
        expect(view.effect).toEqual({ text: '충성은 이미 100입니다 — 상을 내린 기록 · 결속 사건만 남습니다', warn: true });
    });
});
