// useCourtReward 수명 — 금액만 늦게 읽기 · 늦은 결과 버리기(끊기를 무시해도) · 소유자(장수 · 탭 서버) A→B→A · 턴 끝 · 다시 시도 ·
// 공개 상태 · 사라진 카드 · 중복 접수 · 해제 · 옛 소유자 접수 결과 · 같은 소유자 순 갱신 중 접수 결과 유지 ·
// 같은 값으로 돌아온 고르기 · 금액(A→B→A · 150→200→150)과 붙잡아 둔 submit · 권한 · 공개 상태로 막힌 읽기.
import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { GameSession } from '@/lib/campaign-session';
import type { IntakeOutcome } from '@/lib/types';
import type { RewardOptionsQuery, RewardOptionsRead, RewardReadFailure } from '@/lib/court-reward-types';
import { deliverTurnCompleted, __resetTurnListeners } from '@/lib/turnEvents';
import { card, network, readyBody, unavailableFunding, type FakeState } from './fixtures/court-reward';

const h = vi.hoisted(() => ({
    session: null as unknown,
    read: vi.fn(),
    post: vi.fn(),
}));
vi.mock('@/lib/campaign-session', () => ({ useGameSession: () => h.session }));
vi.mock('@/lib/api', async (importOriginal) => ({ ...(await importOriginal<typeof import('@/lib/api')>()), api: { courtReward: h.post } }));
vi.mock('@/lib/api/court-reward', async (importOriginal) => ({
    ...(await importOriginal<typeof import('@/lib/api/court-reward')>()), readRewardOptions: h.read,
}));

import { parseRewardOptions } from '@/lib/api/court-reward';
import {
    REWARD_DENIED_FALLBACK, REWARD_QUEUED_TEXT, REWARD_READ_FAILED, REWARD_SEND_FAILED, rewardReadErrorText,
} from '@/lib/court-reward-view';
import { REWARD_AMOUNT_DEBOUNCE_MS, useCourtReward, type CourtReward } from '@/hooks/useCourtReward';

interface Call {
    readonly query: RewardOptionsQuery;
    readonly signal: AbortSignal;
    readonly resolve: (r: RewardOptionsRead) => void;
}
interface Post {
    readonly generalId: number;
    readonly args: { retainerId: number; money: number };
    readonly resolve: (o: IntakeOutcome) => void;
    readonly reject: (e: unknown) => void;
}

let calls: Call[] = [];
let posts: Post[] = [];
const STATE: FakeState = { cards: [card(4, 95, { funding: network('5120') }), card(5, 0, { funding: unavailableFunding('WAREHOUSE_MALFORMED') })] };

function session(generalId: number | null, over: { server?: string; month?: number; admission?: string | null } = {}): GameSession {
    return {
        loading: false, error: null, generalId, serverId: over.server ?? 'alpha', gameDate: '', refresh: () => undefined,
        frontInfo: { global: { year: 200, month: over.month ?? 3, turnPhase: 2 } },
        admission: over.admission ?? null,
    } as unknown as GameSession;
}

/** 이 요청에 대한 계약 응답(대역 서버) — 실제 검증기를 통과한 값만 넘긴다. */
function ok(query: RewardOptionsQuery, state: FakeState = STATE): RewardOptionsRead {
    const options = parseRewardOptions(readyBody(state, { ...query, money: query.money == null ? null : String(query.money) }), query);
    if (!options) throw new Error('고정 응답이 계약을 어겼다');
    return { ok: true, options };
}

const fail = (failure: RewardReadFailure, httpStatus: number | null): RewardOptionsRead => ({ ok: false, failure, httpStatus });
const flush = async (fn?: () => void) => { await act(async () => { fn?.(); await vi.advanceTimersByTimeAsync(0); }); };
const last = () => calls[calls.length - 1];
const mount = (onQueued?: () => void) => renderHook(() => useCourtReward(null, onQueued));

/** 카드를 고르고 금액을 적어 미리 보기까지 받는다. */
async function prepared(retainerId = 4, raw = '150', onQueued?: () => void) {
    const hook = mount(onQueued);
    await flush(() => last().resolve(ok(last().query)));
    act(() => hook.result.current.select(retainerId));
    act(() => hook.result.current.setAmount(raw));
    await flush(() => vi.advanceTimersByTime(REWARD_AMOUNT_DEBOUNCE_MS));
    await flush(() => last().resolve(ok(last().query)));
    return hook;
}

beforeEach(() => {
    vi.useFakeTimers();
    calls = [];
    posts = [];
    h.session = session(7);
    h.read.mockReset().mockImplementation((query: RewardOptionsQuery, signal: AbortSignal) =>
        new Promise<RewardOptionsRead>((resolve) => { calls.push({ query, signal, resolve }); }));
    h.post.mockReset().mockImplementation((generalId: number, args: Post['args']) =>
        new Promise<IntakeOutcome>((resolve, reject) => { posts.push({ generalId, args, resolve, reject }); }));
});

afterEach(() => {
    vi.useRealTimers();
    __resetTurnListeners();
});

describe('읽기 — 금액만 250ms 늦게, 바뀌는 즉시 옛 미리 보기를 숨긴다', () => {
    it('고르기는 바로, 금액은 마지막 입력 250ms 뒤 한 번 · 정규화한 금액만 보낸다', async () => {
        const { result } = mount();
        expect(calls.map((c) => c.query)).toEqual([{ generalId: 7, retainerId: null, money: null }]);
        // 고르기 전 금액은 요청을 바꾸지 않는다.
        act(() => result.current.setAmount('150'));
        await flush(() => vi.advanceTimersByTime(REWARD_AMOUNT_DEBOUNCE_MS));
        expect(calls).toHaveLength(1);
        await flush(() => calls[0].resolve(ok(calls[0].query)));
        act(() => result.current.select(4));
        expect(calls).toHaveLength(2);
        expect(calls[1].query).toEqual({ generalId: 7, retainerId: 4, money: 150 });
        await flush(() => calls[1].resolve(ok(calls[1].query)));
        expect(result.current.view?.stock?.text).toBe('조회 시점 창고로 지급 가능 · 실행 때 다시 확인');

        act(() => result.current.setAmount('01'));
        // 렌더에서 바로 숨긴다 — 타이머 · 효과 전에 옛 미리 보기가 남지 않는다.
        expect(result.current.view).toMatchObject({ effect: null, stock: null, blocked: '금액을 확인하는 중입니다.' });
        expect(calls[1].signal.aborted).toBe(true);
        await flush(() => vi.advanceTimersByTime(REWARD_AMOUNT_DEBOUNCE_MS - 1));
        act(() => result.current.setAmount(' 0099'));
        await flush(() => vi.advanceTimersByTime(REWARD_AMOUNT_DEBOUNCE_MS - 1));
        expect(calls).toHaveLength(2);
        await flush(() => vi.advanceTimersByTime(1));
        expect(calls).toHaveLength(3);
        expect(calls[2].query).toEqual({ generalId: 7, retainerId: 4, money: 99 });
        await flush(() => calls[2].resolve(ok(calls[2].query)));
        expect(result.current.view?.blocked).toBe('금 100 이상이어야 충성이 오릅니다.');
        // 정규화 값이 같은 원문 변경은 다시 읽지 않는다.
        act(() => result.current.setAmount('99'));
        await flush(() => vi.advanceTimersByTime(REWARD_AMOUNT_DEBOUNCE_MS));
        expect(calls).toHaveLength(3);
        expect(result.current.view?.blocked).toBe('금 100 이상이어야 충성이 오릅니다.');
    });

    it('A→B→A 고르기 — 끊기를 무시한 옛 요청이 늦게 · 역순으로 와도 같은 열쇠의 새 요청만 쓴다', async () => {
        const { result } = mount();
        await flush(() => calls[0].resolve(ok(calls[0].query)));
        act(() => result.current.setAmount('150'));
        act(() => result.current.select(4));
        act(() => result.current.select(5));
        act(() => result.current.select(4));
        expect(calls.slice(1).map((c) => c.query.retainerId)).toEqual([4, 5, 4]);
        expect(calls[1].signal.aborted && calls[2].signal.aborted).toBe(true);
        await flush(() => calls[1].resolve(ok(calls[1].query)));
        await flush(() => calls[2].resolve(ok(calls[2].query)));
        expect(result.current.view).toMatchObject({ previewState: 'loading', effect: null });
        await flush(() => calls[3].resolve(ok(calls[3].query)));
        expect(result.current.view?.previewState).toBe('ready');
        expect(result.current.view?.effect?.text).toContain('충성 +1');
    });

    it('턴 끝 · 다시 시도 · 순 변경은 바로 다시 읽고 옛 요청을 끊는다', async () => {
        const { result, rerender } = mount();
        await flush(() => calls[0].resolve(ok(calls[0].query)));
        act(() => deliverTurnCompleted());
        expect(calls).toHaveLength(2);
        act(() => result.current.retry());
        expect(calls).toHaveLength(3);
        expect(calls[1].signal.aborted).toBe(true);
        h.session = session(7, { month: 4 });
        rerender();
        expect(calls).toHaveLength(4);
        expect(calls[2].signal.aborted).toBe(true);
        // 다시 읽는 동안에도 받은 목록은 남는다(미리 보기는 열쇠가 같을 때만).
        expect(result.current.view?.cards).toHaveLength(2);
        await flush(() => calls[2].resolve({ ok: false, failure: 'HTTP', httpStatus: 503 }));
        expect(result.current.refreshFailed).toBe(false);
    });

    it('공개 상태가 막히면 읽던 요청을 끊고 받은 값을 버리며, 풀리면 바로 다시 읽는다', async () => {
        const { result, rerender } = await prepared();
        expect(result.current.view?.blocked).toBeNull();
        act(() => result.current.retry());
        const inflight = last();
        h.session = session(7, { admission: 'not-public' });
        rerender();
        expect(result.current).toMatchObject({ paused: true, view: null });
        expect(inflight.signal.aborted).toBe(true);
        const count = calls.length;
        await flush(() => inflight.resolve(ok(inflight.query)));
        expect(result.current.view).toBeNull();
        h.session = session(7);
        rerender();
        expect(calls).toHaveLength(count + 1);
        expect(result.current.read).toMatchObject({ data: null, loading: true });
        await flush(() => last().resolve(ok(last().query)));
        expect(result.current.view?.blocked).toBeNull();
    });

    it('다시 읽은 선택지에 고른 카드가 없으면 고르기 · 미리 보기를 비운다', async () => {
        const { result } = await prepared(4, '150');
        act(() => result.current.retry());
        await flush(() => last().resolve(ok(last().query, { cards: [STATE.cards[1]] })));
        expect(result.current.selected).toBeNull();
        expect(result.current.view?.blocked).toBe('상사할 인물을 고르세요.');
        expect(last().query.retainerId).toBeNull();
    });

    it('읽기 실패 — 선택지가 없으면 오류(HTTP 번호만, 계약 오류는 번호 없음), 받은 뒤 실패는 다시 읽기 실패로', async () => {
        const { result } = mount();
        await flush(() => calls[0].resolve({ ok: false, failure: 'HTTP', httpStatus: 503 }));
        expect(result.current.read).toMatchObject({ data: null, loading: false, error: '상사 선택지를 불러오지 못했습니다', errorCode: '503' });
        act(() => result.current.retry());
        expect(result.current.read.loading).toBe(true);
        await flush(() => last().resolve({ ok: false, failure: 'CONTRACT', httpStatus: 200 }));
        expect(result.current.read.errorCode).toBeNull();
        act(() => result.current.retry());
        await flush(() => last().resolve(ok(last().query)));
        act(() => result.current.retry());
        await flush(() => last().resolve({ ok: false, failure: 'NETWORK', httpStatus: null }));
        expect(result.current.refreshFailed).toBe(true);
        expect(result.current.read.error).toBeNull();
        expect(result.current.view?.cards).toHaveLength(2);
    });
});

/** 옛 미리 보기가 숨었고(확인 중) 지금 submit 은 아무것도 보내지 않는다. */
function expectHeld(result: { readonly current: CourtReward }) {
    expect(result.current.view).toMatchObject({ previewState: 'loading', effect: null, stock: null, blocked: '금액을 확인하는 중입니다.' });
    act(() => result.current.submit());
    expect(h.post).not.toHaveBeenCalled();
}

describe('같은 값으로 돌아와도 — 새 읽기의 결과만 미리 보기 · 접수에 쓴다', () => {
    it('준비된 A·150 → B → A — 바로 새로 읽고, 옛 A 결과를 되살리지 않으며, 끊기를 무시한 B 의 늦은 결과도 버린다', async () => {
        const { result } = await prepared(4, '150');
        const before = calls.length;
        act(() => result.current.select(5));
        act(() => result.current.select(4));
        expect(calls.slice(before).map((c) => c.query)).toEqual([
            { generalId: 7, retainerId: 5, money: 150 }, { generalId: 7, retainerId: 4, money: 150 },
        ]);
        expectHeld(result);
        await flush(() => calls[before].resolve(ok(calls[before].query)));
        expectHeld(result);
        await flush(() => last().resolve(ok(last().query)));
        expect(result.current.view).toMatchObject({ previewState: 'ready', blocked: null });
        act(() => result.current.submit());
        expect(h.post).toHaveBeenCalledTimes(1);
        expect(h.post).toHaveBeenCalledWith(7, { retainerId: 4, money: 150 });
    });

    it('한 렌더 안의 A → B → A 도 옛 A 결과를 쓰지 않고 한 번 새로 읽는다(멈춰 있지 않는다)', async () => {
        const { result } = await prepared(4, '150');
        const before = calls.length;
        act(() => { result.current.select(5); result.current.select(4); });
        expect(calls.slice(before).map((c) => c.query)).toEqual([{ generalId: 7, retainerId: 4, money: 150 }]);
        expectHeld(result);
        await flush(() => last().resolve(ok(last().query)));
        expect(result.current.view).toMatchObject({ previewState: 'ready', blocked: null });
    });

    it('준비된 150 → 200 → 150(250ms 전) — 옛 150 미리 보기를 되살리지 않고, 마지막 입력 250ms 뒤 한 번 새로 읽는다', async () => {
        const { result } = await prepared(4, '150');
        const before = calls.length;
        act(() => result.current.setAmount('200'));
        act(() => result.current.setAmount('150'));
        expectHeld(result);
        await flush(() => vi.advanceTimersByTime(REWARD_AMOUNT_DEBOUNCE_MS - 1));
        expect(calls).toHaveLength(before);
        expectHeld(result);
        await flush(() => vi.advanceTimersByTime(1));
        expect(calls.slice(before).map((c) => c.query.money)).toEqual([150]);
        expectHeld(result);
        await flush(() => last().resolve(ok(last().query)));
        expect(result.current.view).toMatchObject({ previewState: 'ready', blocked: null });
        act(() => result.current.submit());
        expect(h.post).toHaveBeenCalledTimes(1);
        expect(h.post).toHaveBeenCalledWith(7, { retainerId: 4, money: 150 });
    });

    it('150 → 200(읽는 중) → 150 — 끊기를 무시한 200 의 늦은 결과는 버리고, 새 150 결과 전까지 숨기고 보내지 않는다', async () => {
        const { result } = await prepared(4, '150');
        act(() => result.current.setAmount('200'));
        await flush(() => vi.advanceTimersByTime(REWARD_AMOUNT_DEBOUNCE_MS));
        const r200 = last();
        expect(r200.query.money).toBe(200);
        act(() => result.current.setAmount('150'));
        expect(r200.signal.aborted).toBe(true);
        await flush(() => r200.resolve(ok(r200.query)));
        expectHeld(result);
        await flush(() => vi.advanceTimersByTime(REWARD_AMOUNT_DEBOUNCE_MS));
        const r150 = last();
        expect(r150).not.toBe(r200);
        expect(r150.query.money).toBe(150);
        expectHeld(result);
        await flush(() => r150.resolve(ok(r150.query)));
        expect(result.current.view).toMatchObject({ previewState: 'ready', blocked: null });
        act(() => result.current.submit());
        expect(h.post).toHaveBeenCalledTimes(1);
    });

    it.each([
        ['금액', (r: CourtReward) => r.setAmount('200')],
        ['인물', (r: CourtReward) => r.select(5)],
        ['다시 시도', (r: CourtReward) => r.retry()],
    ])('붙잡아 둔 submit — %s 변경 뒤(같은 핸들러 안 · 다음 렌더 · 새 결과 뒤 모두) 보내지 않고, 새 결과의 submit 만 한 번 보낸다', async (_name, edit) => {
        const { result } = await prepared(4, '150');
        const stale = result.current.submit;
        act(() => { edit(result.current); stale(); });
        expect(h.post).not.toHaveBeenCalled();
        act(() => stale());
        expect(h.post).not.toHaveBeenCalled();
        await flush(() => vi.advanceTimersByTime(REWARD_AMOUNT_DEBOUNCE_MS));
        await flush(() => last().resolve(ok(last().query)));
        expect(result.current.view?.blocked).toBeNull();
        act(() => stale());
        expect(h.post).not.toHaveBeenCalled();
        act(() => result.current.submit());
        expect(h.post).toHaveBeenCalledTimes(1);
    });

    it('정규화 값이 같은 원문 변경(빈칸 · 앞자리 0)은 미리 보기 · 붙잡아 둔 submit 을 끊지 않는다', async () => {
        const { result } = await prepared(4, '150');
        const before = calls.length;
        const captured = result.current.submit;
        act(() => result.current.setAmount(' 0150 '));
        await flush(() => vi.advanceTimersByTime(REWARD_AMOUNT_DEBOUNCE_MS));
        expect(calls).toHaveLength(before);
        expect(result.current.view).toMatchObject({ previewState: 'ready', blocked: null });
        act(() => captured());
        expect(h.post).toHaveBeenCalledTimes(1);
        expect(h.post).toHaveBeenCalledWith(7, { retainerId: 4, money: 150 });
    });
});

describe('읽기 실패 — 로그인 · 소유권 · 공개 상태로 막히면 받아 둔 선택지까지 버린다', () => {
    it.each([
        ['AUTH_REQUIRED', 401], ['FORBIDDEN', 403], ['ADMISSION_NOT_PUBLIC', 403], ['ADMISSION_UNAVAILABLE', 503],
    ] as const)('%s(%i) — 카드 · 미리 보기 · 읽기 값을 비우고 까닭을 보이며, 늦은 옛 성공이 되살리지 못하고, 다시 시도로 새로 받는다', async (failure, status) => {
        const { result } = await prepared(4, '150');
        const stale = result.current.submit;
        act(() => result.current.retry());
        const older = last();
        act(() => result.current.retry());
        const denied = last();
        await flush(() => denied.resolve(fail(failure, status)));
        expect(result.current.view).toBeNull();
        expect(result.current.refreshFailed).toBe(false);
        expect(result.current.read).toEqual({ data: null, loading: false, error: rewardReadErrorText(failure), errorCode: String(status) });
        expect(result.current.read.error).not.toBe(REWARD_READ_FAILED);
        // 끊기를 무시한 옛 읽기의 늦은 성공이 막힌 뒤의 화면을 되살리지 않는다.
        await flush(() => older.resolve(ok(older.query)));
        expect(result.current.read.data).toBeNull();
        expect(result.current.view).toBeNull();
        act(() => { stale(); result.current.submit(); });
        expect(h.post).not.toHaveBeenCalled();
        act(() => result.current.retry());
        expect(result.current.read).toMatchObject({ data: null, loading: true, error: null });
        await flush(() => last().resolve(ok(last().query)));
        expect(result.current.view).toMatchObject({ previewState: 'ready', blocked: null });
        expect(result.current.view?.cards).toHaveLength(2);
        act(() => result.current.submit());
        expect(h.post).toHaveBeenCalledTimes(1);
    });

    it.each([
        ['NETWORK', null], ['HTTP', 503],
    ] as const)('일시 실패 %s(%s) — 목록은 남기고 다시 읽기 실패로, 옛 미리 보기로는 보내지 않는다', async (failure, status) => {
        const { result } = await prepared(4, '150');
        const stale = result.current.submit;
        act(() => result.current.retry());
        await flush(() => last().resolve(fail(failure, status)));
        expect(result.current.refreshFailed).toBe(true);
        expect(result.current.read).toMatchObject({ error: null, loading: false });
        expect(result.current.view?.cards).toHaveLength(2);
        expect(result.current.view).toMatchObject({
            previewState: 'error', effect: null, stock: null, blocked: '미리 보기를 불러오지 못했습니다 — 다시 시도해 주세요.',
        });
        act(() => { stale(); result.current.submit(); });
        expect(h.post).not.toHaveBeenCalled();
    });
});

describe('소유자 — 장수 · 탭 서버가 바뀌면 그 렌더에서 비우고 옛 결과를 버린다', () => {
    it.each([
        ['장수', () => session(8)],
        ['탭 서버', () => session(7, { server: 'beta' })],
    ])('%s A→B→A — 고르기 · 금액 · 알림 · 보내는 중 · 읽기를 비우고, 옛 접수 · 읽기 결과는 새 A 에 쓰지 않는다', async (_name, other) => {
        const { result, rerender } = await prepared();
        act(() => result.current.submit());
        expect(result.current.busy).toBe(true);
        const oldRead = last();
        act(() => result.current.retry());
        const aRead = last();
        h.session = other();
        rerender();
        expect(result.current).toMatchObject({ selected: null, amount: '', busy: false, notice: null, view: null });
        expect(result.current.read.loading).toBe(true);
        const bRead = last();
        h.session = session(7);
        rerender();
        expect(result.current).toMatchObject({ selected: null, amount: '', busy: false, notice: null, view: null });
        expect(aRead.signal.aborted && bRead.signal.aborted).toBe(true);
        const count = calls.length;
        await flush(() => posts[0].resolve({ status: 'AVAILABLE' } as IntakeOutcome));
        await flush(() => aRead.resolve(ok(aRead.query)));
        await flush(() => oldRead.resolve(ok(oldRead.query)));
        await flush(() => bRead.resolve(ok(bRead.query)));
        expect(result.current).toMatchObject({ notice: null, busy: false, view: null });
        // 옛 접수의 성공이 다시 읽기를 부르지 않는다.
        expect(calls).toHaveLength(count);
        await flush(() => last().resolve(ok(last().query)));
        expect(result.current.view?.cards).toHaveLength(2);
    });

    it('옛 소유자 접수의 실패(catch) · finally 도 새 소유자를 바꾸지 않고, 새 소유자는 바로 보낼 수 있다', async () => {
        const { result, rerender } = await prepared();
        act(() => result.current.submit());
        h.session = session(8);
        rerender();
        await flush(() => last().resolve(ok(last().query)));
        act(() => result.current.select(4));
        act(() => result.current.setAmount('150'));
        await flush(() => vi.advanceTimersByTime(REWARD_AMOUNT_DEBOUNCE_MS));
        await flush(() => last().resolve(ok({ ...last().query })));
        act(() => result.current.submit());
        expect(posts.map((p) => p.generalId)).toEqual([7, 8]);
        expect(result.current.busy).toBe(true);
        await flush(() => posts[0].reject(new TypeError('Failed to fetch')));
        expect(result.current).toMatchObject({ busy: true, notice: null });
        await flush(() => posts[1].resolve({ status: 'AVAILABLE' } as IntakeOutcome));
        expect(result.current).toMatchObject({ busy: false, notice: { tone: 'ok', text: REWARD_QUEUED_TEXT } });
    });

    it('해제 뒤 읽기 · 접수의 then/catch/finally 는 상태를 건드리지 않는다', async () => {
        const errors = vi.spyOn(console, 'error').mockImplementation(() => undefined);
        const { result, unmount } = await prepared();
        act(() => result.current.submit());
        act(() => result.current.retry());
        const pending = last();
        unmount();
        expect(pending.signal.aborted).toBe(true);
        const count = calls.length;
        await flush(() => posts[0].resolve({ status: 'AVAILABLE' } as IntakeOutcome));
        await flush(() => pending.resolve(ok(pending.query)));
        expect(calls).toHaveLength(count);
        expect(errors).not.toHaveBeenCalled();
        errors.mockRestore();
    });
});

describe('접수 — 같은 정규화 금액 · 한 번만 · 서버 문구', () => {
    it('「 0150 」 은 GET · POST 모두 150 — 연달아 눌러도 한 번, 같은 소유자 순 갱신 중에도 접수 결과는 남는다', async () => {
        const { result, rerender } = await prepared(4, ' 0150 ');
        // 고른 직후(금액 없음) 한 번, 금액을 적은 뒤 정규화 값으로 한 번 — 원문(앞자리 0 · 빈칸)은 보내지 않는다.
        expect(calls.filter((c) => c.query.retainerId === 4).map((c) => c.query.money)).toEqual([null, 150]);
        act(() => { result.current.submit(); result.current.submit(); });
        expect(h.post).toHaveBeenCalledTimes(1);
        expect(h.post).toHaveBeenCalledWith(7, { retainerId: 4, money: 150 });
        h.session = session(7, { month: 4 });
        rerender();
        await flush(() => last().resolve(ok(last().query)));
        await flush(() => posts[0].resolve({ status: 'AVAILABLE' } as IntakeOutcome));
        expect(result.current).toMatchObject({ busy: false, amount: '', selected: 4, notice: { tone: 'ok', text: REWARD_QUEUED_TEXT } });
        // 접수 뒤 선택지를 다시 읽는다(대기 진단이 바뀐다) — 알림은 그대로.
        expect(last().query).toEqual({ generalId: 7, retainerId: 4, money: null });
        await flush(() => last().resolve(ok(last().query)));
        expect(result.current.notice?.text).toBe(REWARD_QUEUED_TEXT);
        act(() => result.current.submit());
        expect(h.post).toHaveBeenCalledTimes(1);
    });

    it('창고 금 확인할 수 없음(FUNDING_UNAVAILABLE)도 접수를 막지 않는다', async () => {
        const { result } = await prepared(5, '150');
        expect(result.current.view?.blocked).toBeNull();
        act(() => result.current.submit());
        expect(h.post).toHaveBeenCalledWith(7, { retainerId: 5, money: 150 });
    });

    it('막는 판정 · 확인 중에는 보내지 않는다', async () => {
        const { result } = await prepared(4, '501');
        act(() => result.current.submit());
        act(() => result.current.setAmount('150'));
        act(() => result.current.submit());
        expect(h.post).not.toHaveBeenCalled();
    });

    it.each([
        [{ status: 'BLOCKED', reason: '이미 대기 중인 상사가 있습니다.' }, '이미 대기 중인 상사가 있습니다.'],
        [{ status: 'UNKNOWN', reason: '  ' }, REWARD_DENIED_FALLBACK],
    ])('거절 %o — 서버 사유 그대로, 비었으면 옛 문구', async (outcome, text) => {
        const { result } = await prepared();
        act(() => result.current.submit());
        await flush(() => posts[0].resolve(outcome as IntakeOutcome));
        expect(result.current).toMatchObject({ busy: false, notice: { tone: 'error', text }, amount: '150' });
    });

    it('접수되면 onQueued 를 한 번 부른다 — 같은 소유자 순 갱신 중에도, 거절 · 보내기 실패는 부르지 않는다', async () => {
        const onQueued = vi.fn();
        const { result, rerender } = await prepared(4, '150', onQueued);
        act(() => result.current.submit());
        await flush(() => posts[0].resolve({ status: 'BLOCKED', reason: '이미 대기 중인 상사가 있습니다.' } as IntakeOutcome));
        act(() => result.current.submit());
        await flush(() => posts[1].reject(new TypeError('Failed to fetch')));
        expect(onQueued).not.toHaveBeenCalled();
        act(() => result.current.submit());
        h.session = session(7, { month: 4 });
        rerender();
        await flush(() => posts[2].resolve({ status: 'AVAILABLE' } as IntakeOutcome));
        expect(onQueued).toHaveBeenCalledTimes(1);
        expect(result.current.notice).toEqual({ tone: 'ok', text: REWARD_QUEUED_TEXT });
    });

    it.each([
        ['장수', () => session(8)],
        ['탭 서버', () => session(7, { server: 'beta' })],
    ])('옛 소유자(%s A→B→A)의 늦은 접수 성공 · 해제 뒤 성공은 onQueued 를 부르지 않는다', async (_name, other) => {
        const onQueued = vi.fn();
        const { result, rerender, unmount } = await prepared(4, '150', onQueued);
        act(() => result.current.submit());
        h.session = other();
        rerender();
        h.session = session(7);
        rerender();
        await flush(() => posts[0].resolve({ status: 'AVAILABLE' } as IntakeOutcome));
        expect(onQueued).not.toHaveBeenCalled();
        expect(result.current.notice).toBeNull();
        // 새 A 에서 보낸 접수를 해제 뒤에 받아도 부르지 않는다.
        await flush(() => last().resolve(ok(last().query)));
        act(() => result.current.select(4));
        act(() => result.current.setAmount('150'));
        await flush(() => vi.advanceTimersByTime(REWARD_AMOUNT_DEBOUNCE_MS));
        await flush(() => last().resolve(ok(last().query)));
        act(() => result.current.submit());
        expect(posts).toHaveLength(2);
        unmount();
        await flush(() => posts[1].resolve({ status: 'AVAILABLE' } as IntakeOutcome));
        expect(onQueued).not.toHaveBeenCalled();
    });

    it('보내기 실패 — 옛 네트워크 문구, 다시 보낼 수 있다', async () => {
        const { result } = await prepared();
        act(() => result.current.submit());
        await flush(() => posts[0].reject(new TypeError('Failed to fetch')));
        expect(result.current).toMatchObject({ busy: false, notice: { tone: 'error', text: REWARD_SEND_FAILED } });
        act(() => result.current.submit());
        expect(h.post).toHaveBeenCalledTimes(2);
    });
});
