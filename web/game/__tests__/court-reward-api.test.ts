// 상사 선택지 읽기(lib/api/court-reward) — 주소 · no-store · HTTP 코드 · 계약 검증(null 명시 · 8판정 × 5필드 · 되돌림).
// 모르는 모양 · 어긋난 응답은 성공으로 치지 않는다(fail closed).
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { closedBody, card, isolated, network, noFunding, preview, readyBody, unavailableFunding, type FakeState, type Wire } from './fixtures/court-reward';

const mocks = vi.hoisted(() => ({ fetchGame: vi.fn<(path: string, init?: RequestInit) => Promise<Response>>() }));
vi.mock('@/lib/api', async (importOriginal) => ({ ...(await importOriginal<typeof import('@/lib/api')>()), fetchGame: mocks.fetchGame }));

import { parseRewardOptions, readRewardOptions, rewardOptionsPath } from '@/lib/api/court-reward';
import type { RewardOptionsQuery } from '@/lib/court-reward-types';

const respond = (body: unknown, status = 200) => Promise.resolve(new Response(JSON.stringify(body), { status }));
const q = (retainerId: number | null = null, money: number | null = null): RewardOptionsQuery => ({ generalId: 7, retainerId, money });
const wireQ = (query: RewardOptionsQuery) => ({ ...query, money: query.money == null ? null : String(query.money) });

/** 충성 0/95/99/100 · 자금 상태가 다른 카드 묶음. */
const STATE: FakeState = { cards: [
    card(4, 95, { funding: network('5120') }),
    card(5, 0, { funding: unavailableFunding('WAREHOUSE_MALFORMED') }),
    card(6, 60, { funding: noFunding('LOCATION_FOREIGN') }),
    card(8, 100, { funding: network('9007199254740993') }),
    card(9, 40, { funding: unavailableFunding('RECIPIENT_MISSING') }),
] };
const body = (query: RewardOptionsQuery) => readyBody(STATE, wireQ(query));
const parse = (b: unknown, query: RewardOptionsQuery) => parseRewardOptions(b, query);

beforeEach(() => mocks.fetchGame.mockReset());

describe('요청 — 주소 · no-store · 끊기 신호', () => {
    it('카드 · 정규화 금액만 보낸다 — 카드 없이 금액을 싣지 않는다', () => {
        expect(rewardOptionsPath(q())).toBe('/api/court/reward-options?generalId=7');
        expect(rewardOptionsPath(q(4))).toBe('/api/court/reward-options?generalId=7&retainerId=4');
        expect(rewardOptionsPath(q(4, 100))).toBe('/api/court/reward-options?generalId=7&retainerId=4&money=100');
        expect(rewardOptionsPath(q(null, 100))).toBe('/api/court/reward-options?generalId=7');
    });
    it('fetchGame 에 cache no-store 와 signal 을 넘긴다', async () => {
        const signal = new AbortController().signal;
        mocks.fetchGame.mockImplementationOnce(() => respond(body(q(4, 150))));
        const r = await readRewardOptions(q(4, 150), signal);
        expect(r.ok).toBe(true);
        expect(mocks.fetchGame).toHaveBeenCalledWith('/api/court/reward-options?generalId=7&retainerId=4&money=150', { cache: 'no-store', signal });
    });
    it('끊은 요청은 던지고, 그 밖의 연결 실패는 NETWORK', async () => {
        mocks.fetchGame.mockRejectedValueOnce(new DOMException('aborted', 'AbortError'));
        await expect(readRewardOptions(q())).rejects.toThrow('aborted');
        mocks.fetchGame.mockRejectedValueOnce(new TypeError('Failed to fetch'));
        expect(await readRewardOptions(q())).toEqual({ ok: false, failure: 'NETWORK', httpStatus: null });
    });
});

describe('HTTP 상태 — 계약 코드와 공개 상태만 이름 붙이고 나머지는 HTTP', () => {
    it.each([
        [401, 'AUTH_REQUIRED', 'AUTH_REQUIRED'],
        [400, 'INVALID_GENERAL_ID', 'BAD_REQUEST'],
        [400, 'INVALID_RETAINER_ID', 'BAD_REQUEST'],
        [400, 'PREVIEW_TARGET_REQUIRED', 'BAD_REQUEST'],
        [403, 'FORBIDDEN', 'FORBIDDEN'],
        [403, 'SERVER_NOT_PUBLIC', 'ADMISSION_NOT_PUBLIC'],
        [503, 'SERVER_ADMISSION_UNAVAILABLE', 'ADMISSION_UNAVAILABLE'],
        [403, 'AUTH_REQUIRED', 'HTTP'],
        [400, 'FORBIDDEN', 'HTTP'],
        [500, 'INTERNAL', 'HTTP'],
        [503, 'WORLD_UNAVAILABLE', 'HTTP'],
    ])('%i %s → %s', async (status, code, failure) => {
        mocks.fetchGame.mockImplementationOnce(() => respond({ error: { code, message: 'x' } }, status));
        expect(await readRewardOptions(q())).toEqual({ ok: false, failure, httpStatus: status });
    });
    it('본문 없는 오류 · JSON 이 아닌 200 본문', async () => {
        mocks.fetchGame.mockImplementationOnce(() => Promise.resolve(new Response('nope', { status: 502 })));
        expect(await readRewardOptions(q())).toEqual({ ok: false, failure: 'HTTP', httpStatus: 502 });
        mocks.fetchGame.mockImplementationOnce(() => Promise.resolve(new Response('<html>', { status: 200 })));
        expect(await readRewardOptions(q())).toEqual({ ok: false, failure: 'CONTRACT', httpStatus: 200 });
    });
    it('계약 밖 200 본문은 CONTRACT — 원문을 싣지 않는다', async () => {
        mocks.fetchGame.mockImplementationOnce(() => respond({ status: 'READY' }));
        expect(await readRewardOptions(q())).toEqual({ ok: false, failure: 'CONTRACT', httpStatus: 200 });
    });
});

describe('루트 — 8키 명시 · READY 아니면 다섯 칸 모두 null', () => {
    it.each([
        ['UNAVAILABLE', 'WORLD_UNAVAILABLE'], ['UNAVAILABLE', 'WORLD_DATE_INVALID'], ['UNAVAILABLE', 'ARTIFACTS_UNAVAILABLE'],
        ['UNAVAILABLE', 'ROSTER_INVALID'], ['WRONG_RULE_PROFILE', 'WRONG_RULE_PROFILE'], ['UNSUPPORTED_WORLD_FORMAT', 'UNSUPPORTED_WORLD_FORMAT'],
    ])('%s / %s 는 받는다', (status, reason) => {
        expect(parse(closedBody(7, status, reason), q(4, 100))).toMatchObject({ status, reason, cards: null, preview: null });
    });
    it.each([
        ['WRONG_RULE_PROFILE', 'WORLD_UNAVAILABLE'], ['WRONG_RULE_PROFILE', 'UNSUPPORTED_WORLD_FORMAT'], ['WRONG_RULE_PROFILE', 'PROFILE_MISMATCH'],
        ['UNSUPPORTED_WORLD_FORMAT', 'WORLD_UNAVAILABLE'], ['UNSUPPORTED_WORLD_FORMAT', 'WRONG_RULE_PROFILE'], ['UNSUPPORTED_WORLD_FORMAT', 'FORMAT_UNKNOWN'],
        ['UNAVAILABLE', 'WRONG_RULE_PROFILE'], ['UNAVAILABLE', 'UNSUPPORTED_WORLD_FORMAT'],
    ])('%s / %s — 사유가 상태와 어긋나면 실패', (status, reason) => {
        expect(parse(closedBody(7, status, reason), q(4, 100))).toBeNull();
    });
    it.each(['WRONG_RULE_PROFILE', 'UNSUPPORTED_WORLD_FORMAT'])('%s 의 reason null 은 실패', (status) => {
        expect(parse(closedBody(7, status, null as never), q())).toBeNull();
    });
    it.each(['snapshot', 'rule', 'queued', 'cards', 'preview'])('READY 가 아닌데 %s 가 있으면 실패', (key) => {
        const ready = body(q(4, 100));
        expect(parse({ ...closedBody(7, 'UNAVAILABLE', 'WORLD_UNAVAILABLE'), [key]: ready[key] }, q(4, 100))).toBeNull();
    });
    it('UNAVAILABLE 사유 · 모르는 상태 · reason null 은 실패, READY 의 reason 은 null 이어야 한다', () => {
        expect(parse(closedBody(7, 'UNAVAILABLE', 'STORAGE_UNAVAILABLE'), q())).toBeNull();
        expect(parse(closedBody(7, 'UNAVAILABLE', null as never), q())).toBeNull();
        expect(parse(closedBody(7, 'NOT_READY', 'WORLD_UNAVAILABLE'), q())).toBeNull();
        expect(parse({ ...body(q()), reason: 'WORLD_UNAVAILABLE' }, q())).toBeNull();
    });
    it.each(['status', 'reason', 'generalId', 'snapshot', 'rule', 'queued', 'cards', 'preview'])('루트 %s 키가 빠지면 실패', (key) => {
        const b = body(q());
        delete b[key];
        expect(parse(b, q())).toBeNull();
        const closed = closedBody(7, 'UNAVAILABLE', 'WORLD_UNAVAILABLE');
        delete closed[key];
        expect(parse(closed, q())).toBeNull();
    });
    it('모르는 키가 붙어도 실패, READY 카드 없음은 []', () => {
        expect(parse({ ...body(q()), extra: 1 }, q())).toBeNull();
        expect(parse(readyBody({ cards: [] }, wireQ(q())), q())).toMatchObject({ status: 'READY', cards: [] });
    });
    it('되돌림 — 다른 장수 · 다른 카드 · 다른 금액의 응답은 실패', () => {
        expect(parse({ ...body(q()), generalId: 8 }, q())).toBeNull();
        expect(parse(body(q(4, 150)), q(5, 150))).toBeNull();
        expect(parse(body(q(4, 150)), q(4, 200))).toBeNull();
        expect(parse(body(q(4, 150)), q())).toBeNull();
        expect(parse(body(q()), q(4))).toBeNull();
    });
    it('snapshot · rule · queued 범위와 키', () => {
        const b = body(q());
        expect(parse({ ...b, snapshot: { year: 200, month: 13, phase: 1 } }, q())).toBeNull();
        expect(parse({ ...b, snapshot: { year: 200, month: 3, phase: 4 } }, q())).toBeNull();
        // 백엔드는 year > 0 일 때만 READY 를 낸다.
        expect(parse({ ...b, snapshot: { year: 1, month: 1, phase: 1 } }, q())).toMatchObject({ snapshot: { year: 1, month: 1, phase: 1 } });
        expect(parse({ ...b, snapshot: { year: 0, month: 3, phase: 1 } }, q())).toBeNull();
        expect(parse({ ...b, snapshot: { year: -1, month: 3, phase: 1 } }, q())).toBeNull();
        expect(parse({ ...b, rule: { ...(b.rule as Wire), loyaltyCap: undefined } }, q())).toBeNull();
        expect(parse({ ...b, rule: { ...(b.rule as Wire), minimumMoney: 0 } }, q())).toBeNull();
        expect(parse({ ...b, queued: { status: 'QUEUED', retainerId: 4, money: 150 } }, q())).toMatchObject({ queued: { status: 'QUEUED', retainerId: 4, money: 150 } });
        expect(parse({ ...b, queued: { status: 'UNAVAILABLE', retainerId: null, money: null } }, q())).not.toBeNull();
        expect(parse({ ...b, queued: { status: 'NONE', retainerId: 4, money: null } }, q())).toBeNull();
        expect(parse({ ...b, queued: { status: 'NONE', retainerId: null, money: 150 } }, q())).toBeNull();
        expect(parse({ ...b, queued: { status: 'UNAVAILABLE', retainerId: 4, money: null } }, q())).toBeNull();
        expect(parse({ ...b, queued: { status: 'UNAVAILABLE', retainerId: null, money: 150 } }, q())).toBeNull();
        expect(parse({ ...b, queued: { status: 'MAYBE', retainerId: null, money: null } }, q())).toBeNull();
        expect(parse({ ...b, queued: { status: 'NONE', retainerId: null } }, q())).toBeNull();
    });
});

describe('카드 · 자금 — 명시 null 과 상태별 칸', () => {
    it('알려진 0 과 확인할 수 없음은 다르게 읽는다 · 2^53 를 넘는 창고 금은 문자열 그대로', () => {
        const r = parse(body(q()), q());
        if (r?.status !== 'READY') throw new Error('READY 가 아니다');
        expect(r.cards.map((c) => [c.retainerId, c.funding.status, c.funding.scope, c.funding.usableMoney])).toEqual([
            [4, 'KNOWN', 'NETWORK', '5120'], [5, 'UNAVAILABLE', null, null], [6, 'KNOWN', 'NONE', '0'],
            [8, 'KNOWN', 'NETWORK', '9007199254740993'], [9, 'UNAVAILABLE', null, null],
        ]);
        expect(r.cards[4]).toMatchObject({ name: null, locationCityId: null });
    });
    it.each(['retainerId', 'recipientGeneralId', 'name', 'loyalty', 'loyaltyRoom', 'maximumMoney', 'locationCityId', 'funding'])('카드 %s 키가 빠지면 실패', (key) => {
        const b = body(q());
        delete (b.cards as Wire[])[0][key];
        expect(parse(b, q())).toBeNull();
    });
    it.each(['status', 'scope', 'noneReason', 'unavailableReason', 'usableMoney', 'warehouseCount'])('자금 %s 키가 빠지면 실패', (key) => {
        for (const funding of [network('1'), noFunding('PAYER_LANDLESS'), unavailableFunding('TOTAL_OVERFLOW')]) {
            const f = { ...funding };
            delete f[key];
            expect(parse(readyBody({ cards: [card(4, 10, { funding: f })] }, wireQ(q())), q())).toBeNull();
        }
    });
    it.each([
        ['KNOWN 인데 사유', { ...network('10'), unavailableReason: 'TOTAL_OVERFLOW' }],
        ['UNAVAILABLE 인데 scope', { ...unavailableFunding('LOCATION_UNKNOWN'), scope: 'NETWORK' }],
        ['UNAVAILABLE 인데 0', { ...unavailableFunding('LOCATION_UNKNOWN'), usableMoney: '0' }],
        ['UNAVAILABLE 인데 창고 수', { ...unavailableFunding('LOCATION_UNKNOWN'), warehouseCount: 0 }],
        ['NONE 인데 금', { ...noFunding('LOCATION_NEUTRAL'), usableMoney: '10' }],
        ['NONE 사유 없음', { ...noFunding('LOCATION_NEUTRAL'), noneReason: null }],
        ['NETWORK 에 NONE 사유', { ...network('10'), noneReason: 'PAYER_LANDLESS' }],
        ['숫자 금', { ...network('10'), usableMoney: 10 }],
        ['앞자리 0', network('010')],
        ['음수', network('-1')],
        ['소수', network('1.5')],
        ['창고 0 인데 금', network('5', 0)],
        ['모르는 사유', unavailableFunding('DISK_FULL')],
        ['모르는 범위', { ...network('10'), scope: 'EMPIRE' }],
    ])('자금 %s → 실패', (_name, funding) => {
        expect(parse(readyBody({ cards: [card(4, 10, { funding })] }, wireQ(q())), q())).toBeNull();
    });
    it('고립 현의 0 은 받는다', () => {
        expect(parse(readyBody({ cards: [card(4, 10, { funding: isolated('0') })] }, wireQ(q())), q())).not.toBeNull();
    });
    it('이름 null 은 받는 인물 없음(RECIPIENT_MISSING)과만, 빈 이름 · 범위 밖 충성 · 중복 카드는 실패', () => {
        expect(parse(readyBody({ cards: [card(4, 10, { name: null })] }, wireQ(q())), q())).toBeNull();
        expect(parse(readyBody({ cards: [card(4, 10, { name: '' })] }, wireQ(q())), q())).toBeNull();
        expect(parse(readyBody({ cards: [card(4, 10, { funding: unavailableFunding('RECIPIENT_MISSING'), name: '대체 이름' })] }, wireQ(q())), q())).toBeNull();
        expect(parse(readyBody({ cards: [card(4, 10, { loyalty: 101 })] }, wireQ(q())), q())).toBeNull();
        expect(parse(readyBody({ cards: [card(4, 10, { loyaltyRoom: 11 })] }, wireQ(q())), q())).toBeNull();
        expect(parse(readyBody({ cards: [card(4, 10, { maximumMoney: 99 })] }, wireQ(q())), q())).toBeNull();
        expect(parse(readyBody({ cards: [card(4, 10, { locationCityId: 0 })] }, wireQ(q())), q())).toBeNull();
        expect(parse(readyBody({ cards: [card(4, 10), card(4, 20)] }, wireQ(q())), q())).toBeNull();
    });
});

// ── 미리 보기: 8판정 × 5효과 필드 ─────────────────────────────────────────

const FIELDS = ['loyaltyGain', 'loyaltyAfter', 'moneyWithoutGain', 'usableMoney', 'debitPlan'] as const;
/** [판정, 요청, 계산되는 필드]. */
const VERDICTS: readonly [string, RewardOptionsQuery, readonly (typeof FIELDS)[number][]][] = [
    ['INVALID_AMOUNT', q(4, 10_000_000_000), []],
    ['CARD_UNAVAILABLE', q(77, 150), []],
    ['NO_AMOUNT', q(4), []],
    ['TOO_SMALL', q(4, 99), []],
    ['REWARD_OVER_CAP', q(4, 501), []],
    ['FUNDING_UNAVAILABLE', q(5, 150), ['loyaltyGain', 'loyaltyAfter', 'moneyWithoutGain']],
    ['INSUFFICIENT_STOCK', q(6, 150), ['loyaltyGain', 'loyaltyAfter', 'moneyWithoutGain', 'usableMoney']],
    ['COVERED_AT_SNAPSHOT', q(4, 150), [...FIELDS]],
];

describe.each(VERDICTS)('미리 보기 %s', (verdict, query, computed) => {
    it('계약 응답을 받고 효과 필드의 null 표가 맞다', () => {
        const r = parse(body(query), query);
        if (r?.status !== 'READY' || !r.preview) throw new Error('미리 보기가 없다');
        expect(r.preview.verdict).toBe(verdict);
        for (const f of FIELDS) expect(r.preview[f] === null).toBe(!computed.includes(f));
        expect(r.preview.notChecked).toEqual(['QUEUE_ADMISSION', 'REWARD_HISTORY', 'CONCURRENT_DEBITS', 'STATE_AFTER_SNAPSHOT']);
    });
    it.each(FIELDS)('%s 를 반대로(null ↔ 값) 바꾸면 실패', (field) => {
        const b = body(query);
        const p = b.preview as Wire;
        const filled: Record<(typeof FIELDS)[number], unknown> = {
            loyaltyGain: 1, loyaltyAfter: 61, moneyWithoutGain: 50, usableMoney: '5120',
            debitPlan: [{ cityId: 3, isCapital: true, take: '150', balance: '5120', revision: '7' }],
        };
        p[field] = computed.includes(field) ? null : filled[field];
        expect(parse(b, query)).toBeNull();
    });
    it.each([...FIELDS, 'retainerId', 'money', 'verdict', 'notChecked'])('미리 보기 %s 키가 빠지면 실패(null 로 채우지 않는다)', (key) => {
        const b = body(query);
        delete (b.preview as Wire)[key];
        expect(parse(b, query)).toBeNull();
    });
});

describe('미리 보기 — 되돌림 · 순서 · 계획 · 카드와의 일치', () => {
    it('notChecked 는 정확히 네 값, 이 순서', () => {
        const b = body(q(4, 150));
        (b.preview as Wire).notChecked = ['REWARD_HISTORY', 'QUEUE_ADMISSION', 'CONCURRENT_DEBITS', 'STATE_AFTER_SNAPSHOT'];
        expect(parse(b, q(4, 150))).toBeNull();
        (b.preview as Wire).notChecked = ['QUEUE_ADMISSION', 'REWARD_HISTORY', 'CONCURRENT_DEBITS'];
        expect(parse(b, q(4, 150))).toBeNull();
    });
    it('INVALID_AMOUNT 의 money 는 null, 카드 없는 금액 생략은 CARD_UNAVAILABLE', () => {
        const b = body(q(4, 10_000_000_000));
        expect((b.preview as Wire).money).toBeNull();
        (b.preview as Wire).money = 10_000_000_000;
        expect(parse(b, q(4, 10_000_000_000))).toBeNull();
        expect(parse(body(q(77)), q(77))).toMatchObject({ preview: { verdict: 'CARD_UNAVAILABLE', money: null } });
        expect(parse(body(q(9, 150)), q(9, 150))).toMatchObject({ preview: { verdict: 'CARD_UNAVAILABLE', money: 150 } });
    });
    it('충성 100 의 금 100 은 +0, 100 전부 충성 없이 · 2^53 넘는 사용 가능 금도 정확', () => {
        const r = parse(body(q(8, 100)), q(8, 100));
        expect(r).toMatchObject({ preview: { verdict: 'COVERED_AT_SNAPSHOT', loyaltyGain: 0, loyaltyAfter: 100, moneyWithoutGain: 100, usableMoney: '9007199254740993' } });
    });
    it('판정이 카드와 어긋나면 실패 — 상한 안의 OVER_CAP · 상한 밖의 COVERED · 다른 사용 가능 금', () => {
        const over = body(q(4, 150));
        Object.assign(over.preview as Wire, { verdict: 'REWARD_OVER_CAP', loyaltyGain: null, loyaltyAfter: null, moneyWithoutGain: null, usableMoney: null, debitPlan: null });
        expect(parse(over, q(4, 150))).toBeNull();
        const covered = body(q(4, 150));
        (covered.preview as Wire).usableMoney = '5121';
        expect(parse(covered, q(4, 150))).toBeNull();
        const after = body(q(4, 150));
        (after.preview as Wire).loyaltyAfter = 97;
        expect(parse(after, q(4, 150))).toBeNull();
        const sum = body(q(4, 150));
        (sum.preview as Wire).moneyWithoutGain = 49;
        expect(parse(sum, q(4, 150))).toBeNull();
    });
    it('차감 계획 — 합이 금액과 같고, 잔액을 넘지 않고, 키 5개 · 십진 문자열이어야 한다', () => {
        const split = (takes: [string, string][]) => readyBody({ cards: STATE.cards,
            debits: () => takes.map(([take, balance], i) => ({ cityId: 10 + i, isCapital: i === 0, take, balance, revision: '9007199254740995' })) }, wireQ(q(4, 150)));
        expect(parse(split([['120', '120'], ['30', '5000']]), q(4, 150))).toMatchObject({ preview: { debitPlan: [
            { cityId: 10, isCapital: true, take: '120', balance: '120', revision: '9007199254740995' },
            { cityId: 11, isCapital: false, take: '30', balance: '5000', revision: '9007199254740995' },
        ] } });
        expect(parse(split([['120', '120'], ['20', '5000']]), q(4, 150))).toBeNull();
        expect(parse(split([['150', '120']]), q(4, 150))).toBeNull();
        expect(parse(split([['150', '0150']]), q(4, 150))).toBeNull();
        const missing = split([['150', '5120']]);
        delete ((missing.preview as Wire).debitPlan as Wire[])[0].revision;
        expect(parse(missing, q(4, 150))).toBeNull();
        const empty = body(q(4, 150));
        (empty.preview as Wire).debitPlan = [];
        expect(parse(empty, q(4, 150))).toBeNull();
    });
    it('카드 없이 money 만 오는 미리 보기 · 다른 카드의 미리 보기는 실패', () => {
        const b = body(q(4, 150));
        expect(parse({ ...b, preview: preview(STATE, 5, '150') }, q(4, 150))).toBeNull();
    });
});
