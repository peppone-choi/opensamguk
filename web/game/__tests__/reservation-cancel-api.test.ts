// Cancellation transport: bodyless DELETE, original revision and intent UUID, pinned tab server, and one guarded same-account 401 retry.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, fetchGame } from '@/lib/api';
import { readCancelResultHttp, readReservedRing, sendReservationCancel } from '@/lib/api/reservation-cancel';
import { farm, json, onServerPath, resetTab, rev } from './fixtures/reservation-cancel';

const wire = { actor: 1, turnIdx: 3, revision: rev(3), requestId: rev(700) };
const allow = () => true;

let calls: { url: string; init?: RequestInit }[];
let respond: (url: string, init?: RequestInit) => Response | Promise<Response>;
beforeEach(() => {
  onServerPath('pep');
  calls = [];
  respond = () => json({}, 404);
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    calls.push({ url: String(input), init });
    return respond(String(input), init);
  }));
});
afterEach(() => {
  vi.unstubAllGlobals();
  resetTab();
  document.cookie = 'sam_server=; max-age=0; path=/';
});

const gameCalls = () => calls.filter(c => c.url.startsWith('/api/game/'));

describe('DELETE', () => {
  it('sends the frozen slot · ORIGINAL revision · intent UUID to the original tab server with no body', async () => {
    document.cookie = 'sam_server=other; path=/';
    respond = () => json({}, 200);
    await sendReservationCancel(wire, 'pep', allow);
    const [call] = gameCalls();
    const url = new URL(call.url, 'http://localhost');
    expect(url.pathname).toBe('/api/game/api/reserved-commands');
    expect(Object.fromEntries(url.searchParams)).toEqual({ generalId: '1', turnIdx: '3', revision: rev(3), server: 'pep' });
    expect(call.init?.method).toBe('DELETE');
    expect(new Headers(call.init?.headers).get('Idempotency-Key')).toBe(rev(700));
    expect(new Headers(call.init?.headers).get('Content-Type')).toBeNull();
    expect(call.init?.body).toBeUndefined();
  });

  it('returns status and body as sent; a non-JSON or empty body is undefined, a thrown fetch is network loss', async () => {
    respond = () => new Response('Bad Gateway', { status: 502 });
    expect(await sendReservationCancel(wire, 'pep', allow)).toEqual({ kind: 'response', status: 502, body: undefined });
    respond = () => new Response(null, { status: 403 });
    expect(await sendReservationCancel(wire, 'pep', allow)).toEqual({ kind: 'response', status: 403, body: undefined });
    respond = () => { throw new TypeError('Failed to fetch'); };
    expect(await sendReservationCancel(wire, 'pep', allow)).toEqual({ kind: 'network' });
  });
});

describe('reads', () => {
  it('reservation read names the original server explicitly and parses strictly', async () => {
    respond = () => json({ result: true, generalId: 1, slots: [farm(0)] });
    const read = await readReservedRing(1, 'pep', allow);
    expect(read).toEqual({ kind: 'ok', ring: { result: true, generalId: 1, slots: [farm(0)] } });
    expect(Object.fromEntries(new URL(gameCalls()[0].url, 'http://localhost').searchParams)).toEqual({ generalId: '1', server: 'pep' });
  });

  it.each([
    ['missing revision', json({ result: true, generalId: 1, slots: [{ turnIdx: 0, action: 'action.farm', brief: '', arg: {} }] }), 200],
    ['foreign actor', json({ result: true, generalId: 2, slots: [] }), 200],
    ['bodyless 403', new Response(null, { status: 403 }), 403],
    ['503', json({ error: { code: 'SERVER_ADMISSION_UNAVAILABLE', message: '' } }, 503), 503],
  ])('%s is a read error with its status, never an empty ring', async (_, response, status) => {
    respond = () => response;
    expect(await readReservedRing(1, 'pep', allow)).toEqual({ kind: 'error', status });
  });

  it('result GET asks for the intent UUID on the original server', async () => {
    respond = () => json({ status: 'PENDING', requestId: rev(700) });
    expect(await readCancelResultHttp(wire, 'pep', allow)).toEqual({ kind: 'response', status: 200, body: { status: 'PENDING', requestId: rev(700) } });
    const url = new URL(gameCalls()[0].url, 'http://localhost');
    expect(url.pathname).toBe(`/api/game/api/command/result/${rev(700)}`);
    expect(url.searchParams.get('server')).toBe('pep');
  });
});

describe('401 refresh guard', () => {
  const expiredThenOk = (user: unknown) => {
    let game = 0;
    respond = (url) => {
      if (url === '/api/auth/me') return json({ user });
      game += 1;
      return game === 1 ? json({ error: { code: 'AUTH_REQUIRED', message: '' } }, 401) : json({ ok: true });
    };
  };

  it('same account: the SAME DELETE (target · key · no body) is sent once more', async () => {
    expiredThenOk({ id: 1 });
    const guard = vi.fn((user: unknown) => (user as { id?: number } | null)?.id === 1);
    const http = await sendReservationCancel(wire, 'pep', guard);
    expect(http).toMatchObject({ kind: 'response', status: 200 });
    const deletes = gameCalls();
    expect(deletes).toHaveLength(2);
    expect(deletes[1].url).toBe(deletes[0].url);
    expect(new Headers(deletes[1].init?.headers).get('Idempotency-Key')).toBe(rev(700));
    expect(deletes[1].init?.body).toBeUndefined();
    expect(guard).toHaveBeenCalledWith({ id: 1 });
  });

  it.each([
    ['another account', { id: 2 }],
    ['malformed refresh', null],
    ['no id', { username: 'qa' }],
  ])('%s: no resend, the original 401 comes back', async (_, user) => {
    expiredThenOk(user);
    const http = await sendReservationCancel(wire, 'pep', (u) => (u as { id?: number } | null)?.id === 1);
    expect(http).toMatchObject({ kind: 'response', status: 401 });
    expect(gameCalls()).toHaveLength(1);
  });

  it('a closed scope (guard false) never resends even for the same account', async () => {
    expiredThenOk({ id: 1 });
    await sendReservationCancel(wire, 'pep', () => false);
    expect(gameCalls()).toHaveLength(1);
  });

  it('ordinary callers keep the old behaviour: refresh, then one resend without reading the user', async () => {
    expiredThenOk({ id: 99 });
    await expect(api.post('/api/select-pool/claim', { name: '장수' })).resolves.toEqual({ ok: true });
    expect(gameCalls()).toHaveLength(2);
    const plain = await fetchGame('/api/front-info');
    expect(plain.status).toBe(200);
  });
});
