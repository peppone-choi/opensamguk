// @vitest-environment node
import { afterAll, afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';

const registryMocks = vi.hoisted(() => ({
    getServers: vi.fn(),
    resolveGameApiOrigin: vi.fn(),
}));

let cookieValues: Record<string, string | undefined> = {};

vi.mock('next/headers', () => ({
    cookies: async () => ({
        get: (name: string) => {
            const value = cookieValues[name];
            return value ? { value } : undefined;
        },
    }),
}));

vi.mock('@/lib/serverRegistry', () => registryMocks);

import { GET } from '@/app/api/game/[...path]/route';

const originalGameApiOrigin = process.env.GAME_API_ORIGIN;

function request(path: string): NextRequest {
    return new NextRequest(`http://gateway.example.test${path}`);
}

function context(path: string[]) {
    return { params: Promise.resolve({ path }) };
}

describe('game API proxy server selection', () => {
    beforeEach(() => {
        cookieValues = {};
        process.env.GAME_API_ORIGIN = 'http://default-game-api';
        registryMocks.getServers.mockReturnValue([{ id: 'pep', name: 'Pep', gameApiUrl: 'http://pep-game-api' }]);
        registryMocks.resolveGameApiOrigin.mockImplementation((id: string) =>
            id === 'pep' ? 'http://pep-game-api' : undefined,
        );
        vi.stubGlobal('fetch', vi.fn());
    });

    afterEach(() => {
        vi.restoreAllMocks();
        vi.unstubAllGlobals();
    });

    afterAll(() => {
        if (originalGameApiOrigin === undefined) delete process.env.GAME_API_ORIGIN;
        else process.env.GAME_API_ORIGIN = originalGameApiOrigin;
    });

    it.each([
        ['/api/game/front-info?server=stale', {}],
        ['/api/game/front-info?server=A1', {}],
        ['/api/game/front-info', { sam_server: 'stale' }],
        ['/api/game/front-info', { sam_server: 'A1' }],
    ])('fails closed for an explicit unknown or noncanonical selection', async (path, cookies) => {
        cookieValues = cookies;

        const response = await GET(request(path), context(['front-info']));

        expect(response.status).toBe(503);
        expect(fetch).not.toHaveBeenCalled();
    });

    it('uses the explicitly selected canonical server and preserves the httpOnly Bearer bridge', async () => {
        cookieValues = { sam_access: 'access-token' };
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{"ok":true}', {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
        })));

        const response = await GET(request('/api/game/front-info?server=pep'), context(['front-info']));

        expect(response.status).toBe(200);
        expect(fetch).toHaveBeenCalledWith('http://pep-game-api/front-info', {
            method: 'GET',
            headers: { Authorization: 'Bearer access-token' },
            cache: 'no-store',
        });
    });

    it('uses the default origin only when there is no explicit selection', async () => {
        registryMocks.getServers.mockReturnValue([]);
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{"ok":true}', {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
        })));

        const response = await GET(request('/api/game/front-info'), context(['front-info']));

        expect(response.status).toBe(200);
        expect(fetch).toHaveBeenCalledWith('http://default-game-api/front-info', {
            method: 'GET',
            headers: {},
            cache: 'no-store',
        });
    });
});
