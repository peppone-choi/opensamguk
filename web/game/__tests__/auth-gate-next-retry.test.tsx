// game AuthGate(K3 2026-10-04, 원장 D90): 로그인으로 보낼 때 next 는 경로 + 쿼리만, 서버가 잠시 답하지 않으면(5xx · 연결 실패)
// 로그인으로 보내지 않고 기다렸다 다시 묻는다.
import { act, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import AuthGate from '@/components/AuthGate';
import { authRetryDelayMs } from '@/lib/auth-context';

vi.mock('next/navigation', () => ({ usePathname: () => '/game/pep/retinue' }));

const realLocation = window.location;
let assigned = '';

beforeEach(() => {
    assigned = '';
    Object.defineProperty(window, 'location', {
        configurable: true,
        value: {
            origin: 'http://localhost',
            pathname: '/game/pep/retinue',
            search: '?tab=yuedan',
            get href() { return 'http://localhost/game/pep/retinue?tab=yuedan'; },
            set href(v: string) { assigned = v; },
        },
    });
});

afterEach(() => {
    Object.defineProperty(window, 'location', { configurable: true, value: realLocation });
    vi.unstubAllGlobals();
    vi.useRealTimers();
});

function reply(status: number, body: unknown) {
    return Promise.resolve(new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }));
}

describe('AuthGate', () => {
    it('로그아웃(401)이면 게이트웨이 로그인으로, next 는 경로 + 쿼리만', async () => {
        vi.stubGlobal('fetch', vi.fn(() => reply(401, { user: null })));
        render(<AuthGate><p>본문</p></AuthGate>);
        await vi.waitFor(() => expect(assigned).not.toBe(''));
        const url = new URL(assigned, 'http://localhost');
        expect(url.pathname).toBe('/login');
        expect(url.searchParams.get('next')).toBe('/game/pep/retinue?tab=yuedan');
        expect(screen.queryByText('본문')).toBeNull();
    });

    it('서버가 잠시 답하지 않으면(502 · 연결 실패) 로그인으로 보내지 않고 다시 묻는다', async () => {
        vi.useFakeTimers({ shouldAdvanceTime: true });
        const fetchMock = vi.fn()
            .mockImplementationOnce(() => reply(502, { error: '일시적 오류' }))
            .mockImplementationOnce(() => Promise.reject(new TypeError('network')))
            .mockImplementation(() => reply(200, { user: { id: 1, loginId: 'k3', displayName: '시험', role: 'USER' } }));
        vi.stubGlobal('fetch', fetchMock);
        render(<AuthGate><p>본문</p></AuthGate>);
        // 응답 처리(json · 상태 갱신)가 끝나야 다음 타이머가 걸린다 — 시계를 조금씩 밀면서 다음 호출을 기다린다.
        const advanceUntilCalls = async (n: number) => {
            await vi.waitFor(async () => {
                await act(async () => { await vi.advanceTimersByTimeAsync(500); });
                expect(fetchMock).toHaveBeenCalledTimes(n);
            }, { timeout: 5_000, interval: 0 });
        };
        await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
        expect(assigned).toBe(''); // 502 — 로그인으로 보내지 않았다
        await advanceUntilCalls(2);
        expect(assigned).toBe(''); // 연결 실패 — 여전히 보내지 않는다
        await advanceUntilCalls(3);
        expect(await screen.findByText('본문')).toBeInTheDocument();
        expect(assigned).toBe('');
    });

    it('다시 묻는 간격은 2 · 4 · 8 · 16초, 그 뒤 30초', () => {
        expect([1, 2, 3, 4, 5, 9].map(authRetryDelayMs)).toEqual([2000, 4000, 8000, 16000, 30000, 30000]);
    });
});
