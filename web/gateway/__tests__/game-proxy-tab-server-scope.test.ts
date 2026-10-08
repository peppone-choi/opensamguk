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
import { GET, POST } from '../app/api/game/[...path]/route';

// Load the real other app at runtime: its own @/ type aliases belong to game,
// while this test's static imports are checked under gateway's tsconfig.
const { api } = await vi.importActual<{ api: {
    frontInfo(): Promise<{ general: { generalId: number | null }; global: { serverId: string } }>;
    command(code: string, args: unknown, generalId: number, turnIdx: number): Promise<{ status: string }>;
} }>('../../game/lib/api');
const { middleware: gameEntry } = await vi.importActual<{
    middleware(req: NextRequest): Promise<NextResponse>;
}>('../../game/middleware');

const requestId = '00000000-0000-4000-8000-000000000336';
const args = { stat: 'leadership', requestId };
const publicServers = [{ id: 'pep', name: 'Pep' }, { id: 'other', name: 'Other' }];

describe('tab entry → game client → sole BFF server boundary', () => {
    let targets: string[];
    let reservations: Record<string, typeof args | { stat: string }>;
    let otherGeneralId: number;

    beforeEach(() => {
        targets = [];
        reservations = { pep: { stat: 'strength' }, other: { stat: 'strength' } };
        otherGeneralId = 7;
        window.history.replaceState({}, '', '/game/pep');
        vi.stubEnv('SERVER_REGISTRY_JSON', JSON.stringify(publicServers));
        vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
            const raw = String(input);
            if (raw.endsWith('/servers')) return Response.json(publicServers);
            if (raw.startsWith('/api/game/')) {
                const req = new NextRequest(new Request(new URL(raw, window.location.origin), init));
                const path = req.nextUrl.pathname.slice('/api/game/'.length).split('/');
                const ctx = { params: Promise.resolve({ path }) };
                return init?.method === 'POST' ? POST(req, ctx) : GET(req, ctx);
            }
            const url = new URL(raw);
            const server = url.hostname === 'spep-game-api' ? 'pep' : url.hostname === 'sother-game-api' ? 'other' : null;
            if (!server) throw new Error(`Unexpected network target ${url.hostname}`);
            targets.push(raw);
            const owned = server === 'pep' ? 7 : otherGeneralId;
            if (url.pathname === '/api/front-info') return Response.json({
                general: { hasGeneral: true, generalId: owned }, global: { serverId: server },
            });
            if (url.pathname === '/api/command/action.selfTrain' && init?.method === 'POST') {
                // Same owner check as CommandController: each fixture world has this
                // account's currently owned general. No real user/DB is used here.
                if (Number(url.searchParams.get('generalId')) !== owned) return Response.json({}, { status: 403 });
                reservations[server] = JSON.parse(String(init.body)) as typeof args;
                return Response.json({ result: true, status: 'PENDING', phase: 'reservationAccepted', requestId });
            }
            throw new Error(`Unexpected product API path ${url.pathname}`);
        }));
    });
    afterEach(() => {
        vi.unstubAllGlobals(); vi.unstubAllEnvs();
        document.cookie = 'sam_server=; path=/; max-age=0';
        window.history.replaceState({}, '', '/');
    });

    async function enterTab(server: string) {
        // The gateway sends each path to its configured game instance; emulate the
        // instance config and browser applying that response's non-secret selector.
        vi.stubEnv('SERVER_ID', server);
        const entry = await gameEntry(new NextRequest(`${window.location.origin}/game/${server}`));
        expect(entry.headers.get('x-middleware-rewrite')).toContain(`/game?server=${server}`);
        const cookie = entry.cookies.get('sam_server');
        expect(cookie?.value).toBe(server);
        document.cookie = `sam_server=${cookie?.value}; path=/`;
    }

    async function originalTabGeneral() {
        await enterTab('pep');
        const front = await api.frontInfo();
        expect(front.global.serverId).toBe('pep');
        expect(front.general.generalId).toBe(7);
        await enterTab('other');
        expect(window.location.pathname).toBe('/game/pep');
        expect(document.cookie).toContain('sam_server=other');
        if (front.general.generalId === null) throw new Error('Expected the owned general from the first tab');
        return front.general.generalId;
    }

    it('preserves the other world reservation when both worlds own the same local general ID', async () => {
        const generalId = await originalTabGeneral();
        const result = await api.command('action.selfTrain', args, generalId, 0);
        expect(result.status).toBe('PENDING');
        expect(reservations).toEqual({ pep: args, other: { stat: 'strength' } });
        expect(targets.at(-1)).toBe('http://spep-game-api:8081/api/command/action.selfTrain?generalId=7&turnIdx=0');
    });

    it('does not send the first tab input to a world with a different owned general', async () => {
        otherGeneralId = 9;
        const generalId = await originalTabGeneral();
        const result = await api.command('action.selfTrain', args, generalId, 0);
        expect(result.status).toBe('PENDING');
        expect(reservations).toEqual({ pep: args, other: { stat: 'strength' } });
    });

    it('keeps explicit BFF server selection ahead of the shared cookie', async () => {
        await enterTab('other');
        const response = await GET(new NextRequest(`${window.location.origin}/api/game/api/front-info?server=pep`),
            { params: Promise.resolve({ path: ['api', 'front-info'] }) });
        expect((await response.json()).global.serverId).toBe('pep');
    });

    it('preserves BFF cookie-only selection for serverless callers', async () => {
        await enterTab('other');
        const response = await GET(new NextRequest(`${window.location.origin}/api/game/api/front-info`),
            { params: Promise.resolve({ path: ['api', 'front-info'] }) });
        expect((await response.json()).global.serverId).toBe('other');
    });
});
