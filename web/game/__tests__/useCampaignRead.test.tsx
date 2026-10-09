// useCampaignRead 수명 — 서버 · 장수 · 순 · 다시 읽기마다 범위가 바뀌고, 늦게 끝난 옛 요청은 새 범위에 쓰지 못한다.
import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { GameSession } from '../lib/campaign-session';
import { readServerCookie } from '../lib/serverGameUrl';
import { useCampaignRead, type Read } from '../lib/campaign-reads';

const h = vi.hoisted(() => ({ session: null as unknown }));

vi.mock('../lib/api', () => ({ api: {} }));
vi.mock('../lib/serverGameUrl', () => ({ readServerCookie: vi.fn(() => 'alpha') }));
vi.mock('../lib/campaign-session', () => ({ useGameSession: () => h.session }));

function sessionFor(generalId: number | null, month = 1): GameSession {
    return {
        loading: false,
        error: null,
        frontInfo: { global: { year: 190, month, turnPhase: 1 }, general: { hasGeneral: generalId != null, generalId } },
        generalId,
        serverId: 'alpha',
        gameDate: '',
        refresh: () => undefined,
        admission: null,
    } as unknown as GameSession;
}

interface Call {
    readonly generalId: number;
    readonly signal: AbortSignal;
    readonly resolve: (value: string) => void;
    readonly reject: (error: unknown) => void;
}

/** Deferred transport that ignores AbortSignal — an aborted request can still settle later. */
function deferredLoader() {
    const calls: Call[] = [];
    const load = vi.fn((generalId: number, signal: AbortSignal) => new Promise<string>((resolve, reject) => {
        calls.push({ generalId, signal, resolve, reject });
    }));
    return { calls, load };
}

function mount(load: (generalId: number, signal: AbortSignal) => Promise<string>, initialDeps: readonly unknown[] = [0]) {
    const seen: Read<string>[] = [];
    const hook = renderHook(({ deps }: { deps: readonly unknown[] }) => {
        const read = useCampaignRead(load, deps);
        seen.push(read);
        return read;
    }, { initialProps: { deps: initialDeps } });
    return { ...hook, seen };
}

const settle = async (fn: () => void) => { await act(async () => { fn(); await new Promise((r) => setTimeout(r, 0)); }); };

beforeEach(() => {
    vi.mocked(readServerCookie).mockReturnValue('alpha');
    h.session = sessionFor(1);
});

afterEach(() => {
    vi.restoreAllMocks();
});

describe('useCampaignRead 범위 격리', () => {
    it('장수가 바뀌면 그 렌더부터 옛 장수 자료를 내보이지 않고, 늦게 끝난 옛 요청은 버린다', async () => {
        const { calls, load } = deferredLoader();
        const { result, rerender, seen } = mount(load);
        await settle(() => calls[0].resolve('A 자료'));
        expect(result.current).toEqual({ data: 'A 자료', error: null, errorCode: null, loading: false });

        h.session = sessionFor(2);
        const from = seen.length;
        rerender({ deps: [0] });
        expect(seen.slice(from).every((r) => r.data === null && r.loading)).toBe(true);
        expect(calls[1].generalId).toBe(2);

        await settle(() => calls[1].resolve('B 자료'));
        expect(result.current.data).toBe('B 자료');
    });

    it('A→B→A 로 돌아와도 처음 A 요청 · B 요청의 늦은 결과는 새 A 범위에 쓰지 못한다', async () => {
        const { calls, load } = deferredLoader();
        const { result, rerender } = mount(load);
        h.session = sessionFor(2);
        rerender({ deps: [0] });
        h.session = sessionFor(1);
        rerender({ deps: [0] });
        expect(calls.map((c) => c.generalId)).toEqual([1, 2, 1]);
        expect(calls[0].signal.aborted).toBe(true);
        expect(calls[1].signal.aborted).toBe(true);

        await settle(() => calls[0].resolve('옛 A 자료'));
        await settle(() => calls[1].resolve('B 자료'));
        expect(result.current).toEqual({ data: null, error: null, errorCode: null, loading: true });

        await settle(() => calls[2].resolve('새 A 자료'));
        expect(result.current.data).toBe('새 A 자료');
    });

    it('서버가 바뀌면 자료를 비우고 옛 서버 응답을 버린다 — 렌더 없이 쿠키만 바뀌어도 쓰지 않는다', async () => {
        const { calls, load } = deferredLoader();
        const { result, rerender, seen } = mount(load);
        await settle(() => calls[0].resolve('alpha 자료'));
        expect(result.current.data).toBe('alpha 자료');
        rerender({ deps: [1] });

        vi.mocked(readServerCookie).mockReturnValue('beta');
        const from = seen.length;
        rerender({ deps: [1] });
        expect(seen.slice(from).every((r) => r.data === null && r.loading)).toBe(true);
        expect(calls).toHaveLength(3);
        expect(calls[1].signal.aborted).toBe(true);

        await settle(() => calls[1].resolve('늦은 alpha 자료'));
        expect(result.current).toEqual({ data: null, error: null, errorCode: null, loading: true });

        vi.mocked(readServerCookie).mockReturnValue('alpha');
        await settle(() => calls[2].resolve('beta 요청이 alpha 에 도착'));
        expect(result.current.data).toBeNull();
    });

    it('순이 바뀌면 같은 장수라도 자료를 비우고 다시 읽는다', async () => {
        const { calls, load } = deferredLoader();
        const { result, rerender } = mount(load);
        await settle(() => calls[0].resolve('1월'));
        h.session = sessionFor(1, 2);
        rerender({ deps: [0] });
        expect(result.current).toEqual({ data: null, error: null, errorCode: null, loading: true });
        expect(calls).toHaveLength(2);
        await settle(() => calls[1].resolve('2월'));
        expect(result.current.data).toBe('2월');
    });
});

describe('useCampaignRead 다시 읽기', () => {
    it('다시 읽기는 같은 장수의 옛 자료를 비우고 읽는 중으로 돌아간다(옛 선택으로 명령하지 않게)', async () => {
        const { calls, load } = deferredLoader();
        const { result, rerender, seen } = mount(load);
        await settle(() => calls[0].resolve('옛 자료'));
        const from = seen.length;
        rerender({ deps: [1] });
        expect(seen.slice(from).every((r) => r.data === null && r.loading)).toBe(true);
        await settle(() => calls[1].resolve('새 자료'));
        expect(result.current.data).toBe('새 자료');
    });

    it('다시 읽은 뒤 옛 요청이 늦게 성공해도 쓰지 않는다', async () => {
        const { calls, load } = deferredLoader();
        const { result, rerender } = mount(load);
        rerender({ deps: [1] });
        await settle(() => calls[0].resolve('옛 자료'));
        expect(result.current).toEqual({ data: null, error: null, errorCode: null, loading: true });
        await settle(() => calls[1].resolve('새 자료'));
        expect(result.current.data).toBe('새 자료');
    });

    it('역순 도착 — 새 요청이 먼저 끝나면 옛 요청의 성공 · 실패가 덮어쓰지 않는다', async () => {
        const { calls, load } = deferredLoader();
        const { result, rerender } = mount(load);
        rerender({ deps: [1] });
        await settle(() => calls[1].resolve('새 자료'));
        await settle(() => calls[0].resolve('옛 자료'));
        expect(result.current.data).toBe('새 자료');
        await settle(() => calls[0].reject(new Error('503: Service Unavailable')));
        expect(result.current).toEqual({ data: '새 자료', error: null, errorCode: null, loading: false });
    });

    it('겹친 다시 읽기는 마지막 요청만 결과를 쓴다', async () => {
        const { calls, load } = deferredLoader();
        const { result, rerender } = mount(load);
        rerender({ deps: [1] });
        rerender({ deps: [2] });
        expect(calls).toHaveLength(3);
        expect(calls.slice(0, 2).every((c) => c.signal.aborted)).toBe(true);
        await settle(() => calls[2].resolve('셋째'));
        await settle(() => calls[0].resolve('첫째'));
        await settle(() => calls[1].reject(new Error('500: Internal Server Error')));
        expect(result.current).toEqual({ data: '셋째', error: null, errorCode: null, loading: false });
    });
});

describe('useCampaignRead 거절 · 장수 없음 · 해제', () => {
    it('403 은 자료를 비우고, 그 뒤 옛 요청의 늦은 성공이 거절을 덮지 않는다', async () => {
        const { calls, load } = deferredLoader();
        const { result, rerender } = mount(load);
        await settle(() => calls[0].resolve('볼 수 있던 자료'));
        rerender({ deps: [1] });
        rerender({ deps: [2] });
        await settle(() => calls[2].reject(new Error('403: Forbidden')));
        const denied = { data: null, error: '이 내용을 볼 권한이 없습니다.', errorCode: '403', loading: false };
        expect(result.current).toEqual(denied);
        await settle(() => calls[1].resolve('옛 자료'));
        expect(result.current).toEqual(denied);
    });

    it('장수가 없으면 부르지 않고, 읽던 요청의 늦은 결과도 버린다', async () => {
        const { calls, load } = deferredLoader();
        const { result, rerender, seen } = mount(load);
        h.session = sessionFor(null);
        const from = seen.length;
        rerender({ deps: [0] });
        expect(seen.slice(from).every((r) => r.data === null && !r.loading)).toBe(true);
        expect(calls).toHaveLength(1);
        expect(calls[0].signal.aborted).toBe(true);
        await settle(() => calls[0].resolve('옛 자료'));
        expect(result.current).toEqual({ data: null, error: null, errorCode: null, loading: false });
    });

    it('해제 뒤 끝난 성공 · 실패는 상태를 건드리지 않는다', async () => {
        const errors = vi.spyOn(console, 'error').mockImplementation(() => undefined);
        const { calls, load } = deferredLoader();
        const { rerender, unmount } = mount(load);
        rerender({ deps: [1] });
        unmount();
        expect(calls.every((c) => c.signal.aborted)).toBe(true);
        await settle(() => calls[1].resolve('늦은 자료'));
        await settle(() => calls[0].reject(new Error('403: Forbidden')));
        expect(errors).not.toHaveBeenCalled();
    });
});
