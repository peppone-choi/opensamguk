import { act, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { GameSessionProvider, useGameSession, type GameSession } from '@/lib/campaign-session';
import type { FrontInfoResponse } from '@/lib/types';

const mocks = vi.hoisted(() => ({ frontInfo: vi.fn(), recheckAuth: vi.fn() }));

vi.mock('@/lib/api', async importOriginal => {
    const actual = await importOriginal<typeof import('@/lib/api')>();
    return { ...actual, api: { ...actual.api, frontInfo: mocks.frontInfo } };
});
vi.mock('@/lib/serverGameUrl', () => ({ useServerId: () => 'pep' }));
vi.mock('@/hooks/useTurnRefresh', () => ({ useTurnRefresh: () => undefined }));
vi.mock('@/lib/auth-context', () => ({ useAuthOptional: () => ({ user: null, loading: false, refresh: mocks.recheckAuth }) }));

// front-info is a public read: game-api treats an expired access token as anonymous and answers
// 200 with hasGeneral=false instead of 401 (JwtVerifyFilter + permitAll), so fetchGame never renews.
function info(generalId: number | null): FrontInfoResponse {
    return {
        global: { year: 190, month: 1, turnPhaseText: '' },
        general: generalId == null ? { hasGeneral: false } : { hasGeneral: true, generalId },
    } as unknown as FrontInfoResponse;
}

let current: GameSession | null = null;
function Probe() {
    current = useGameSession();
    const { loading, generalId, error } = current;
    const text = loading ? 'loading' : error ? `error:${error}` : `general:${generalId ?? 'none'}`;
    return <output data-testid="session">{text}</output>;
}
const shown = () => screen.getByTestId('session').textContent;

const fetchMock = vi.fn();
const me = (status: number) => new Response(JSON.stringify({ user: status === 200 ? { id: 7 } : null }), { status });
const meCalls = () => fetchMock.mock.calls.filter(([url]) => url === '/api/auth/me').length;

beforeEach(() => {
    current = null;
    mocks.frontInfo.mockReset();
    mocks.recheckAuth.mockReset();
    fetchMock.mockReset();
    vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
    vi.unstubAllGlobals();
});

describe('GameSessionProvider with an expired access token', () => {
    it('renews the session and reads front-info again when the first answer is anonymous', async () => {
        mocks.frontInfo.mockResolvedValueOnce(info(null)).mockResolvedValueOnce(info(42));
        fetchMock.mockResolvedValue(me(200));

        render(<GameSessionProvider><Probe /></GameSessionProvider>);

        await waitFor(() => expect(shown()).toBe('general:42'));
        expect(meCalls()).toBe(1);
        expect(mocks.frontInfo).toHaveBeenCalledTimes(2);
    });

    it('keeps the no-general answer only when it was read after a successful renewal', async () => {
        mocks.frontInfo.mockResolvedValue(info(null));
        fetchMock.mockResolvedValue(me(200));

        render(<GameSessionProvider><Probe /></GameSessionProvider>);

        await waitFor(() => expect(shown()).toBe('general:none'));
        expect(meCalls()).toBe(1);
        expect(mocks.frontInfo).toHaveBeenCalledTimes(2);
    });

    it('does not renew when front-info already names the general', async () => {
        mocks.frontInfo.mockResolvedValue(info(42));

        render(<GameSessionProvider><Probe /></GameSessionProvider>);

        await waitFor(() => expect(shown()).toBe('general:42'));
        expect(meCalls()).toBe(0);
        expect(mocks.frontInfo).toHaveBeenCalledTimes(1);
    });
});

describe('a renewal that fails is never read as "no general"', () => {
    it('signed out (401): login error and AuthGate is asked to re-check, no second read', async () => {
        mocks.frontInfo.mockResolvedValue(info(null));
        fetchMock.mockResolvedValue(me(401));

        render(<GameSessionProvider><Probe /></GameSessionProvider>);

        await waitFor(() => expect(shown()).toBe('error:로그인이 필요합니다. 다시 로그인해 주세요.'));
        expect(mocks.recheckAuth).toHaveBeenCalledTimes(1);
        expect(mocks.frontInfo).toHaveBeenCalledTimes(1);
        expect(current?.generalId).toBeNull();
        expect(current?.frontInfo).toBeNull();
    });

    it('a player with a general whose renewal gets 502 sees a read error, not the creation path', async () => {
        mocks.frontInfo.mockResolvedValue(info(null));
        fetchMock.mockResolvedValue(me(502));

        render(<GameSessionProvider><Probe /></GameSessionProvider>);

        await waitFor(() => expect(shown()).toBe('error:서버가 잠시 응답하지 않습니다. 잠시 뒤 다시 해 보세요.'));
        expect(mocks.recheckAuth).not.toHaveBeenCalled();
        expect(mocks.frontInfo).toHaveBeenCalledTimes(1);
        expect(current?.frontInfo).toBeNull();
    });

    it('no connection during renewal is the same read error', async () => {
        mocks.frontInfo.mockResolvedValue(info(null));
        fetchMock.mockRejectedValue(new TypeError('Failed to fetch'));

        render(<GameSessionProvider><Probe /></GameSessionProvider>);

        await waitFor(() => expect(shown()).toBe('error:서버가 잠시 응답하지 않습니다. 잠시 뒤 다시 해 보세요.'));
        expect(mocks.recheckAuth).not.toHaveBeenCalled();
        expect(current?.frontInfo).toBeNull();
    });
});

describe('a cancelled read never writes over the newer one', () => {
    const deferredMe = () => {
        let settle!: { resolve: (r: Response) => void; reject: (e: unknown) => void };
        const promise = new Promise<Response>((resolve, reject) => { settle = { resolve, reject }; });
        return { promise, settle };
    };

    it('a renewal aborted by refresh drops its anonymous answer', async () => {
        const old = deferredMe();
        mocks.frontInfo.mockResolvedValueOnce(info(null)).mockResolvedValueOnce(info(42));
        fetchMock.mockReturnValueOnce(old.promise);

        render(<GameSessionProvider><Probe /></GameSessionProvider>);
        await waitFor(() => expect(meCalls()).toBe(1));

        act(() => current!.refresh());
        await waitFor(() => expect(shown()).toBe('general:42'));

        await act(async () => old.settle.reject(new DOMException('The operation was aborted.', 'AbortError')));
        expect(shown()).toBe('general:42');
        expect(current?.generalId).toBe(42);
    });

    it('a renewal that succeeds after its read was cancelled does not apply its late second answer', async () => {
        const old = deferredMe();
        mocks.frontInfo
            .mockResolvedValueOnce(info(null)) // old read: anonymous
            .mockResolvedValueOnce(info(42)) // new read after refresh
            .mockResolvedValueOnce(info(null)); // old read's late second answer
        fetchMock.mockReturnValueOnce(old.promise);

        render(<GameSessionProvider><Probe /></GameSessionProvider>);
        await waitFor(() => expect(meCalls()).toBe(1));

        act(() => current!.refresh());
        await waitFor(() => expect(shown()).toBe('general:42'));

        await act(async () => old.settle.resolve(me(200)));
        await waitFor(() => expect(mocks.frontInfo).toHaveBeenCalledTimes(3));
        expect(shown()).toBe('general:42');
        expect(current?.generalId).toBe(42);
    });
});
