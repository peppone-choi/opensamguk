import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NextRequest, type NextResponse } from 'next/server';

// Browser/Next platform boundary only: the jar is shared between tabs. Registry,
// entry middleware, client serialization and BFF forwarding all use product code.
vi.mock('next/headers', () => ({ cookies: async () => ({
    get: (name: string) => {
        const row = document.cookie.split('; ').find((v) => v.startsWith(`${name}=`));
        return row ? { value: row.slice(name.length + 1) } : undefined;
    },
}) }));
import { DELETE, GET, PATCH, POST } from '../app/api/game/[...path]/route';

// Load the real game client at runtime (its @/ aliases belong to web/game).
const { api, fetchGame } = await vi.importActual<{
    api: { frontInfo(): Promise<{ general: { generalId: number | null }; global: { serverId: string } }> };
    fetchGame(path: string, init?: RequestInit): Promise<Response>;
}>('../../game/lib/api');
const { middleware: gameEntry } = await vi.importActual<{
    middleware(req: NextRequest): Promise<NextResponse>;
}>('../../game/middleware');

const routes = { GET, POST, PATCH, DELETE } as const;
const publicServers = [{ id: 'pep', name: 'Pep' }, { id: 'other', name: 'Other' }];
const revision = '7d0c2f4e-5b1a-4c3d-9e8f-0a1b2c3d4e5f';
const cancelKey = '00000000-0000-4000-8000-000000000789';
const slot = 4; // zero-based turnIdx (05순)
const cancelled = {
    requestId: cancelKey, status: 'RESOLVED', type: 'reservationCancelled', ok: true, accepted: true,
    receiptRecorded: true, committedWorldVersion: 12,
    result: {
        type: 'reservationCancelled', ok: true, commandKind: 'QUEUE_MUTATION', actionCode: 'cancelReservedTurn',
        generalId: 7, turnIdx: slot, reservationRevision: revision, slotEmpty: true,
    },
};

interface Upstream { url: string; init: RequestInit }

describe('game client fetchGame → sole BFF route → selected game-api: reservation cancel', () => {
    let upstream: Upstream[];
    let reply: () => Response;
    let authMe: () => Response;

    beforeEach(() => {
        upstream = [];
        reply = () => Response.json(cancelled);
        authMe = () => Response.json({}, { status: 401 });
        window.history.replaceState({}, '', '/game/pep');
        document.cookie = 'sam_access=cookie-access; path=/';
        vi.stubEnv('SERVER_REGISTRY_JSON', JSON.stringify(publicServers));
        vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
            const raw = String(input);
            if (raw.endsWith('/servers')) return Response.json(publicServers);
            if (raw === '/api/auth/me') return authMe();
            if (raw.startsWith('/api/game/')) {
                const req = new NextRequest(new Request(new URL(raw, window.location.origin), init));
                const path = req.nextUrl.pathname.slice('/api/game/'.length).split('/');
                return routes[req.method as keyof typeof routes](req, { params: Promise.resolve({ path }) });
            }
            const url = new URL(raw);
            if (url.hostname !== 'spep-game-api' && url.hostname !== 'sother-game-api') {
                throw new Error(`Unexpected network target ${url.hostname}`);
            }
            if (url.pathname === '/api/front-info') return Response.json({
                general: { hasGeneral: true, generalId: 7 }, global: { serverId: url.hostname.slice(1, -'-game-api'.length) },
            });
            upstream.push({ url: raw, init: init ?? {} });
            return reply();
        }));
    });
    afterEach(() => {
        vi.unstubAllGlobals(); vi.unstubAllEnvs();
        document.cookie = 'sam_server=; path=/; max-age=0';
        document.cookie = 'sam_access=; path=/; max-age=0';
        window.history.replaceState({}, '', '/');
    });

    async function enterTab(server: string) {
        vi.stubEnv('SERVER_ID', server);
        const entry = await gameEntry(new NextRequest(`${window.location.origin}/game/${server}`));
        const cookie = entry.cookies.get('sam_server');
        expect(cookie?.value).toBe(server);
        document.cookie = `sam_server=${cookie?.value}; path=/`;
    }

    /** The first tab reads its actor on pep, then another tab moves the shared selector cookie to other. */
    async function originalTabActor(): Promise<number> {
        await enterTab('pep');
        const front = await api.frontInfo();
        expect(front.global.serverId).toBe('pep');
        await enterTab('other');
        expect(document.cookie).toContain('sam_server=other');
        if (front.general.generalId === null) throw new Error('Expected the owned general');
        return front.general.generalId;
    }

    function cancel(actor: number, init: { body?: string; headers?: Record<string, string> } = {}) {
        return fetchGame(`/api/reserved-commands?generalId=${actor}&turnIdx=${slot}&revision=${revision}`, {
            method: 'DELETE',
            ...init,
            headers: { 'Idempotency-Key': cancelKey, ...init.headers },
        });
    }

    const pepCancelUrl = `http://spep-game-api:8081/api/reserved-commands?generalId=7&turnIdx=${slot}&revision=${revision}`;

    it('forwards the exact bodyless DELETE with the same key and cookie Bearer to the original tab server', async () => {
        const actor = await originalTabActor();
        const response = await cancel(actor, {
            headers: { Authorization: 'Bearer forged-by-page', 'X-Debug': 'drop-me' },
        });

        expect(response.status).toBe(200);
        expect(await response.json()).toEqual(cancelled);
        expect(upstream).toHaveLength(1);
        const [{ url, init }] = upstream;
        expect(url).toBe(pepCancelUrl);
        expect(new URL(url).searchParams.has('server')).toBe(false);
        expect(init.method).toBe('DELETE');
        expect(init.headers).toEqual({ Authorization: 'Bearer cookie-access', 'Idempotency-Key': cancelKey });
        expect('body' in init).toBe(false);
    });

    it('resends the same key, target and original server once after a refresh that moved the selector cookie', async () => {
        const actor = await originalTabActor();
        let first = true;
        reply = () => {
            if (!first) return Response.json(cancelled);
            first = false;
            return Response.json({ error: { code: 'AUTH_REQUIRED', message: '로그인이 필요합니다.' } }, { status: 401 });
        };
        authMe = () => {
            document.cookie = 'sam_access=refreshed-access; path=/';
            document.cookie = 'sam_server=other; path=/';
            return Response.json({ user: { id: 1 } });
        };

        const response = await cancel(actor);

        expect(response.status).toBe(200);
        expect(upstream.map((u) => u.url)).toEqual([pepCancelUrl, pepCancelUrl]);
        expect(upstream.map((u) => u.init.headers)).toEqual([
            { Authorization: 'Bearer cookie-access', 'Idempotency-Key': cancelKey },
            { Authorization: 'Bearer refreshed-access', 'Idempotency-Key': cancelKey },
        ]);
        expect(upstream.every((u) => !('body' in u.init))).toBe(true);
    });

    it.each([
        ['controller BLOCKED', 409, JSON.stringify({ status: 'BLOCKED', code: 'WORLD_EXECUTING', accepted: false, receiptRecorded: false, retryable: true })],
        ['controller BLOCKED revision', 409, JSON.stringify({ status: 'BLOCKED', code: 'REVISION_MISMATCH', accepted: false, receiptRecorded: false, retryable: false })],
        ['admission filter', 403, JSON.stringify({ error: { code: 'SERVER_NOT_PUBLIC', message: '공개 전' } })],
        ['admission filter', 503, JSON.stringify({ error: { code: 'SERVER_ADMISSION_UNAVAILABLE', message: '확인 불가' } })],
        ['auth filter without refresh', 401, JSON.stringify({ error: { code: 'AUTH_REQUIRED', message: '로그인이 필요합니다.' } })],
        ['bodyless foreign actor', 403, ''],
    ])('passes %s status %i and body through unchanged', async (_name, status, body) => {
        const actor = await originalTabActor();
        reply = () => new Response(body === '' ? null : body, {
            status, headers: body === '' ? {} : { 'Content-Type': 'application/json' },
        });

        const response = await cancel(actor);

        expect(response.status).toBe(status);
        expect(await response.text()).toBe(body);
        expect(upstream).toHaveLength(1);
        expect(upstream[0].url).toBe(pepCancelUrl);
    });

    it('keeps existing transport for a body-bearing cancel', async () => {
        const actor = await originalTabActor();
        await cancel(actor, { body: '{"note":1}', headers: { 'Content-Type': 'application/json' } });

        expect(upstream[0].init).toEqual({
            method: 'DELETE',
            headers: { Authorization: 'Bearer cookie-access', 'Idempotency-Key': cancelKey, 'Content-Type': 'application/json' },
            cache: 'no-store',
            body: '{"note":1}',
        });
    });

    it.each([
        ['GET', '/api/reserved-commands?generalId=7'],
        ['PATCH', '/api/reserved-commands?generalId=7'],
        ['POST', '/api/command/action.selfTrain?generalId=7&turnIdx=0'],
        ['DELETE', '/api/reserved-commands/4?generalId=7'],
        ['DELETE', '/api/admin/game-settings'],
    ])('does not forward the key on %s %s and keeps its body semantics', async (method, path) => {
        await enterTab('pep');
        const response = await fetchGame(path, {
            method,
            headers: { 'Idempotency-Key': cancelKey, Authorization: 'Bearer forged-by-page' },
        });

        expect(response.status).toBe(200);
        expect(upstream).toHaveLength(1);
        const { url, init } = upstream[0];
        expect(url).toBe(`http://spep-game-api:8081${path}`);
        expect(init.headers).toEqual({ Authorization: 'Bearer cookie-access' });
        if (method === 'GET') expect('body' in init).toBe(false);
        else expect(init.body).toBe('');
    });
});
