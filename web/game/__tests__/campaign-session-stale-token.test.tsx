import { render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { GameSessionProvider, useGameSession } from '@/lib/campaign-session';
import type { FrontInfoResponse } from '@/lib/types';

const mocks = vi.hoisted(() => ({ frontInfo: vi.fn() }));

vi.mock('@/lib/api', async importOriginal => {
    const actual = await importOriginal<typeof import('@/lib/api')>();
    return { ...actual, api: { ...actual.api, frontInfo: mocks.frontInfo } };
});
vi.mock('@/lib/serverGameUrl', () => ({ useServerId: () => 'pep' }));
vi.mock('@/hooks/useTurnRefresh', () => ({ useTurnRefresh: () => undefined }));

// front-info is a public read: game-api treats an expired access token as anonymous and answers
// 200 with hasGeneral=false instead of 401 (JwtVerifyFilter + permitAll), so fetchGame never renews.
function info(generalId: number | null): FrontInfoResponse {
    return {
        global: { year: 190, month: 1, turnPhaseText: '' },
        general: generalId == null ? { hasGeneral: false } : { hasGeneral: true, generalId },
    } as unknown as FrontInfoResponse;
}

function Probe() {
    const { loading, generalId } = useGameSession();
    return <output data-testid="session">{loading ? 'loading' : `general:${generalId ?? 'none'}`}</output>;
}

const fetchMock = vi.fn();

beforeEach(() => {
    mocks.frontInfo.mockReset();
    fetchMock.mockReset();
    vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
    vi.unstubAllGlobals();
});

const meCalls = () => fetchMock.mock.calls.filter(([url]) => url === '/api/auth/me').length;

describe('GameSessionProvider with an expired access token', () => {
    it('renews the session and reads front-info again when the first answer is anonymous', async () => {
        mocks.frontInfo.mockResolvedValueOnce(info(null)).mockResolvedValueOnce(info(42));
        fetchMock.mockResolvedValue(new Response(JSON.stringify({ user: { id: 7 } }), { status: 200 }));

        render(<GameSessionProvider><Probe /></GameSessionProvider>);

        await waitFor(() => expect(screen.getByTestId('session').textContent).toBe('general:42'));
        expect(meCalls()).toBe(1);
        expect(mocks.frontInfo).toHaveBeenCalledTimes(2);
    });

    it('keeps the no-general answer for an account that really has no general', async () => {
        mocks.frontInfo.mockResolvedValue(info(null));
        fetchMock.mockResolvedValue(new Response(JSON.stringify({ user: { id: 7 } }), { status: 200 }));

        render(<GameSessionProvider><Probe /></GameSessionProvider>);

        await waitFor(() => expect(screen.getByTestId('session').textContent).toBe('general:none'));
        expect(meCalls()).toBe(1);
        expect(mocks.frontInfo).toHaveBeenCalledTimes(2);
    });

    it('keeps the first answer without a second read when the session cannot be renewed', async () => {
        mocks.frontInfo.mockResolvedValue(info(null));
        fetchMock.mockResolvedValue(new Response(JSON.stringify({ user: null }), { status: 401 }));

        render(<GameSessionProvider><Probe /></GameSessionProvider>);

        await waitFor(() => expect(screen.getByTestId('session').textContent).toBe('general:none'));
        expect(meCalls()).toBe(1);
        expect(mocks.frontInfo).toHaveBeenCalledTimes(1);
    });

    it('does not renew when front-info already names the general', async () => {
        mocks.frontInfo.mockResolvedValue(info(42));

        render(<GameSessionProvider><Probe /></GameSessionProvider>);

        await waitFor(() => expect(screen.getByTestId('session').textContent).toBe('general:42'));
        expect(meCalls()).toBe(0);
        expect(mocks.frontInfo).toHaveBeenCalledTimes(1);
    });
});
