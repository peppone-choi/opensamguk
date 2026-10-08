import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, fetchGame } from '../lib/api';

const args = { stat: 'leadership', requestId: '00000000-0000-4000-8000-000000000336' };

describe('game client keeps the visible tab server across shared cookie changes', () => {
    beforeEach(() => {
        window.history.replaceState({}, '', '/game/pep');
        document.cookie = 'sam_server=other; path=/';
        vi.stubGlobal('fetch', vi.fn(async () => Response.json({ result: true })));
    });
    afterEach(() => {
        vi.unstubAllGlobals();
        document.cookie = 'sam_server=; path=/; max-age=0';
        window.history.replaceState({}, '', '/');
    });

    it.each(['front', 'options', 'command', 'result'] as const)('pins %s to the tab URL instead of the other tab cookie', async (kind) => {
        if (kind === 'front') await api.frontInfo();
        if (kind === 'options') await api.personalOptions('action.selfTrain', 7);
        if (kind === 'command') await api.command('action.selfTrain', args, 7, 0);
        if (kind === 'result') await api.commandResult(args.requestId);
        const url = new URL(String(vi.mocked(fetch).mock.calls[0][0]), window.location.origin);
        expect(url.searchParams.get('server')).toBe('pep');
        if (kind === 'command') {
            expect(url.searchParams.get('generalId')).toBe('7');
            expect(vi.mocked(fetch).mock.calls[0][1]?.body).toBe(JSON.stringify(args));
        }
    });

    it('keeps a query-only entry scoped to its URL server', async () => {
        window.history.replaceState({}, '', '/game?server=pep');
        await fetchGame('/api/front-info');
        expect(String(vi.mocked(fetch).mock.calls[0][0])).toBe('/api/game/api/front-info?server=pep');
    });

    it('prefers the visible path server over a conflicting page query', async () => {
        window.history.replaceState({}, '', '/game/pep?server=other');
        await fetchGame('/api/front-info');
        expect(String(vi.mocked(fetch).mock.calls[0][0])).toBe('/api/game/api/front-info?server=pep');
    });

    it('preserves an API caller explicitly selecting a server', async () => {
        await fetchGame('/api/front-info?server=other&x=1');
        expect(vi.mocked(fetch).mock.calls[0][0]).toBe('/api/game/api/front-info?server=other&x=1');
    });

    it.each(['/game/court', '/game/admin', '/'])('preserves cookie-only selection for %s', async (page) => {
        window.history.replaceState({}, '', page);
        await fetchGame('/api/front-info');
        expect(vi.mocked(fetch).mock.calls[0][0]).toBe('/api/game/api/front-info');
    });

    it('does not silently substitute the cookie for an unknown explicit URL server', async () => {
        window.history.replaceState({}, '', '/game?server=stale');
        await fetchGame('/api/front-info');
        expect(String(vi.mocked(fetch).mock.calls[0][0])).toBe('/api/game/api/front-info?server=stale');
    });

    it('reuses the original scoped URL and body after authentication refresh', async () => {
        const calls: [string, RequestInit | undefined][] = [];
        vi.stubGlobal('fetch', vi.fn(async (input, init) => {
            calls.push([String(input), init]);
            if (String(input) === '/api/auth/me') {
                document.cookie = 'sam_server=other; path=/';
                window.history.replaceState({}, '', '/game/other');
                return Response.json({ user: { id: 1 } });
            }
            return Response.json({}, { status: calls.length === 1 ? 401 : 200 });
        }));
        await api.command('action.selfTrain', args, 7, 0);
        expect(new URL(calls[0][0], window.location.origin).searchParams.get('server')).toBe('pep');
        expect(calls[1][0]).toBe('/api/auth/me');
        expect(calls[2]).toEqual(calls[0]);
        expect(calls[2][1]?.body).toBe(JSON.stringify(args));
    });

    it('keeps server-side and other non-browser callers compatible', async () => {
        vi.stubGlobal('window', undefined);
        await fetchGame('/api/front-info');
        expect(vi.mocked(fetch).mock.calls[0][0]).toBe('/api/game/api/front-info');
    });
});
