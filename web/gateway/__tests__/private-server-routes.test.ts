// @vitest-environment node
import { afterEach, describe, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
const source = vi.hoisted(() => ({ readPublicServers: vi.fn() }));
vi.mock('@/lib/serverPublication', () => source);
vi.mock('@/lib/serverRegistry', () => ({ resolveGameApiOrigin: vi.fn(() => 'http://spep-game-api:8081') }));
vi.mock('next/headers', () => ({ cookies: vi.fn(async () => ({ get: vi.fn() })) }));
import { GET as map } from '@/app/api/server-map/[id]/route';
import { GET as basic } from '@/app/api/server-basic-info/[id]/route';
import { GET as events } from '@/app/api/server-events/[id]/route';
import { GET as imperial } from '@/app/api/server-imperial/[id]/route';
const context = { params: Promise.resolve({ id: 'pep' }) };
afterEach(() => vi.unstubAllGlobals());

describe('private server public fanout', () => {
    it.each([map, basic, events, imperial])('hidden server is 404 without reading game data', async (route) => {
        source.readPublicServers.mockResolvedValue({ kind: 'known', servers: [] });
        const fetch = vi.fn(); vi.stubGlobal('fetch', fetch);
        const response = await route(new NextRequest('http://test/api/server-map/pep'), context);
        expect(response.status).toBe(404);
        expect(response.headers.get('cache-control')).toBe('no-store');
        expect(fetch).not.toHaveBeenCalled();
    });
    it.each([map, basic, events, imperial])('unknown source is 503 without fallback', async (route) => {
        source.readPublicServers.mockResolvedValue({ kind: 'unknown' });
        const fetch = vi.fn(); vi.stubGlobal('fetch', fetch);
        expect((await route(new NextRequest('http://test/api/server-map/pep'), context)).status).toBe(503);
        expect(fetch).not.toHaveBeenCalled();
    });
    it('closing after a public request takes effect on the next request', async () => {
        source.readPublicServers.mockResolvedValueOnce({ kind: 'known', servers: [{ id: 'pep' }] })
            .mockResolvedValueOnce({ kind: 'known', servers: [] });
        const fetch = vi.fn(async () => new Response('{}')); vi.stubGlobal('fetch', fetch);
        expect((await map(new NextRequest('http://test/api/server-map/pep'), context)).status).toBe(200);
        expect((await map(new NextRequest('http://test/api/server-map/pep'), context)).status).toBe(404);
        expect(fetch).toHaveBeenCalledTimes(1);
    });
});
