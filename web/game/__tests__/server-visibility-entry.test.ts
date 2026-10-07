import { NextRequest } from 'next/server';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { middleware } from '../middleware';
import { serverVisibility } from '../lib/server-visibility';
beforeEach(() => vi.stubEnv('SERVER_ID', 'pep'));
afterEach(() => { vi.unstubAllGlobals(); vi.unstubAllEnvs(); });
it.each(['/game/pep', '/game/pep/court', '/game/join?server=pep', '/game/pep/hwiha'])('private HTML %s is 404 before rewriting, cookies or redirects', async (path) => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('[]')));
    const response = await middleware(new NextRequest('http://test'+path));
    expect(response.status).toBe(404);
    expect(response.cookies.get('sam_server')).toBeUndefined();
    expect(response.headers.get('location')).toBeNull();
    expect(response.headers.get('cache-control')).toBe('no-store');
});
it('public entry rewrites to this server and upstream failure returns 503', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('[{"id":"pep"}]')));
    expect((await middleware(new NextRequest('http://test/game/pep'))).headers.get('x-middleware-rewrite')).toContain('/game?server=pep');
    vi.mocked(fetch).mockRejectedValueOnce(new Error('down'));
    expect((await middleware(new NextRequest('http://test/game/pep'))).status).toBe(503);
});
it('malformed list is unknown, never public', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('[{"id":"pep"},null]')));
    expect(await serverVisibility('pep')).toBe('unknown');
});
