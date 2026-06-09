import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const cookieValues = new Map<string, string>();

vi.mock('next/headers', () => ({
    cookies: vi.fn(async () => ({
        get: (name: string) => {
            const value = cookieValues.get(name);
            return value == null ? undefined : { name, value };
        },
    })),
}));

const user = {
    id: 7,
    username: 'auth_user',
    email: 'auth@example.test',
    nickname: 'Auth',
    role: 'USER',
};

function jsonResponse(body: unknown, status = 200): Response {
    return new Response(JSON.stringify(body), {
        status,
        headers: { 'Content-Type': 'application/json' },
    });
}

async function loadRoute() {
    vi.resetModules();
    return import('../app/api/auth/me/route');
}

describe('web/game /api/auth/me route', () => {
    beforeEach(() => {
        cookieValues.clear();
        process.env.GATEWAY_API_URL = 'http://gateway.test';
    });

    afterEach(() => {
        vi.unstubAllGlobals();
        vi.clearAllMocks();
    });

    it('refreshes expired access with sam_refresh and sets renewed cookies', async () => {
        cookieValues.set('sam_access', 'expired-access');
        cookieValues.set('sam_refresh', 'refresh-ok');
        const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
            if (url === 'http://gateway.test/auth/me') {
                expect(init?.headers).toEqual({ Authorization: 'Bearer expired-access' });
                return jsonResponse({ message: 'expired' }, 401);
            }
            if (url === 'http://gateway.test/auth/refresh') {
                expect(init?.method).toBe('POST');
                expect(JSON.parse(init?.body as string)).toEqual({ refreshToken: 'refresh-ok' });
                return jsonResponse({
                    accessToken: 'new-access',
                    refreshToken: 'new-refresh',
                    user,
                });
            }
            throw new Error(`unexpected fetch ${url}`);
        });
        vi.stubGlobal('fetch', fetchMock);
        const { GET } = await loadRoute();

        const response = await GET();
        const body = await response.json();
        const setCookie = response.headers.getSetCookie();

        expect(response.status).toBe(200);
        expect(body).toEqual({ user });
        expect(setCookie.some((v) => v.startsWith('sam_access=new-access;'))).toBe(true);
        expect(setCookie.some((v) => v.startsWith('sam_refresh=new-refresh;'))).toBe(true);
        expect(response.headers.get('x-middleware-set-cookie')).toBeNull();
        expect(fetchMock).toHaveBeenCalledTimes(2);
    });

    it('clears cookies when access and refresh are both invalid', async () => {
        cookieValues.set('sam_access', 'expired-access');
        cookieValues.set('sam_refresh', 'refresh-bad');
        vi.stubGlobal('fetch', vi.fn(async (url: string) => {
            if (url === 'http://gateway.test/auth/me') return jsonResponse({ message: 'expired' }, 401);
            if (url === 'http://gateway.test/auth/refresh') return jsonResponse({ message: 'bad refresh' }, 401);
            throw new Error(`unexpected fetch ${url}`);
        }));
        const { GET } = await loadRoute();

        const response = await GET();
        const body = await response.json();
        const setCookie = response.headers.getSetCookie();

        expect(response.status).toBe(401);
        expect(body).toEqual({ user: null });
        expect(setCookie.some((v) => v.startsWith('sam_access=;'))).toBe(true);
        expect(setCookie.some((v) => v.startsWith('sam_refresh=;'))).toBe(true);
        expect(response.headers.get('x-middleware-set-cookie')).toBeNull();
    });

    it('keeps cookies on transient gateway failure', async () => {
        cookieValues.set('sam_access', 'access-present');
        cookieValues.set('sam_refresh', 'refresh-present');
        vi.stubGlobal('fetch', vi.fn(async (url: string) => {
            if (url === 'http://gateway.test/auth/me') return jsonResponse({ message: 'temporary' }, 500);
            throw new Error(`unexpected fetch ${url}`);
        }));
        const { GET } = await loadRoute();

        const response = await GET();
        const body = await response.json();

        expect(response.status).toBe(502);
        expect(body).toEqual({ error: '일시적 오류가 발생했습니다.' });
        expect(response.headers.getSetCookie()).toEqual([]);
    });
});
