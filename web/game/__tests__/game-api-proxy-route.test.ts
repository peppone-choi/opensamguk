import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';

const registryMocks = vi.hoisted(() => ({
  resolveGameApiUrl: vi.fn(),
}));

let cookieValues: Record<string, string | undefined> = {};
let fetchMock: ReturnType<typeof vi.fn>;

vi.mock('next/headers', () => ({
  cookies: async () => ({
    get: (name: string) => {
      const value = cookieValues[name];
      return value === undefined ? undefined : { value };
    },
  }),
}));

vi.mock('@/lib/serverRegistry', () => registryMocks);

import { GET, POST } from '@/app/api/game/[...path]/route';

function request(path: string, init?: ConstructorParameters<typeof NextRequest>[1]): NextRequest {
  return new NextRequest(`http://game.example.test${path}`, init);
}

function context(path: string[]) {
  return { params: Promise.resolve({ path }) };
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

describe('game API proxy server selection', () => {
  beforeEach(() => {
    cookieValues = {};
    registryMocks.resolveGameApiUrl.mockImplementation((serverId: string | undefined) => {
      if (serverId === undefined) return 'http://default-game-api';
      return serverId === 'pep' ? 'http://pep-game-api' : undefined;
    });
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
  });

  it.each([
    ['/api/game/front-info?server=stale', { sam_server: 'pep' }, 'stale'],
    ['/api/game/front-info?server=A1', { sam_server: 'pep' }, 'A1'],
    ['/api/game/front-info', { sam_server: 'stale' }, 'stale'],
    ['/api/game/front-info', { sam_server: 'A1' }, 'A1'],
  ])('fails GET closed for an explicit unknown or noncanonical selection', async (path, cookies, serverId) => {
    cookieValues = cookies;

    const response = await GET(request(path), context(['front-info']));

    expect(response.status).toBe(503);
    expect(registryMocks.resolveGameApiUrl).toHaveBeenCalledWith(serverId);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('gives a canonical query selection precedence over the cookie and removes it upstream', async () => {
    cookieValues = { sam_server: 'stale', sam_access: 'access-token' };
    fetchMock.mockResolvedValue(jsonResponse({ ok: true }));

    const response = await GET(
      request('/api/game/front-info?server=pep&view=full'),
      context(['front-info']),
    );

    expect(response.status).toBe(200);
    expect(registryMocks.resolveGameApiUrl).toHaveBeenCalledWith('pep');
    expect(fetchMock).toHaveBeenCalledWith('http://pep-game-api/front-info?view=full', {
      method: 'GET',
      headers: { Authorization: 'Bearer access-token' },
      cache: 'no-store',
      duplex: 'half',
    });
  });

  it('uses the default game API only when no selector is present', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ ok: true }));

    const response = await GET(request('/api/game/front-info?view=full'), context(['front-info']));

    expect(response.status).toBe(200);
    expect(registryMocks.resolveGameApiUrl).toHaveBeenCalledWith(undefined);
    expect(fetchMock).toHaveBeenCalledWith('http://default-game-api/front-info?view=full', {
      method: 'GET',
      headers: {},
      cache: 'no-store',
      duplex: 'half',
    });
  });

  it('fails POST closed before fetching when the explicit query selection is unknown', async () => {
    cookieValues = { sam_server: 'pep' };

    const response = await POST(
      request('/api/game/api/command/test?server=stale', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ turnIdx: 0 }),
      }),
      context(['api', 'command', 'test']),
    );

    expect(response.status).toBe(503);
    expect(registryMocks.resolveGameApiUrl).toHaveBeenCalledWith('stale');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('forwards a canonical POST without the server selector', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ ok: true }));

    const response = await POST(
      request('/api/game/api/command/test?server=pep&turnIdx=0', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ value: 1 }),
      }),
      context(['api', 'command', 'test']),
    );

    expect(response.status).toBe(200);
    expect(fetchMock).toHaveBeenCalledWith('http://pep-game-api/api/command/test?turnIdx=0', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      cache: 'no-store',
      duplex: 'half',
      body: JSON.stringify({ value: 1 }),
    });
  });

  it('on a 401 from an expired access, refreshes once, resends the buffered body, and sets renewed cookies', async () => {
    cookieValues = { sam_access: 'expired-access', sam_refresh: 'refresh-ok' };
    const user = { id: 1, username: 'u', email: 'u@test', nickname: 'U', role: 'USER' };

    fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
      if (url === 'http://default-game-api/select-pool/claim') {
        if (init?.headers && (init.headers as Record<string, string>).Authorization === 'Bearer expired-access') {
          return jsonResponse({ error: 'expired' }, 401);
        }
        expect((init?.headers as Record<string, string>).Authorization).toBe('Bearer new-access');
        expect(init?.body).toBe(JSON.stringify({ name: '장수' }));
        return jsonResponse({ ok: true }, 200);
      }
      if (url === 'http://localhost:8080/auth/refresh') {
        expect(JSON.parse(init?.body as string)).toEqual({ refreshToken: 'refresh-ok' });
        return jsonResponse({ accessToken: 'new-access', refreshToken: 'new-refresh', user });
      }
      throw new Error(`unexpected fetch ${url}`);
    });

    const response = await POST(
      request('/api/game/select-pool/claim', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: '장수' }),
      }),
      context(['select-pool', 'claim']),
    );
    const body = await response.json();
    const setCookie = response.headers.getSetCookie();

    expect(response.status).toBe(200);
    expect(body).toEqual({ ok: true });
    expect(setCookie.some((v) => v.startsWith('sam_access=new-access;'))).toBe(true);
    expect(setCookie.some((v) => v.startsWith('sam_refresh=new-refresh;'))).toBe(true);
    // game-api 최초 시도 + refresh + game-api 재시도 = 3회, 재시도는 딱 1번뿐
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it('does not retry a second time and returns the original 401 when refresh itself fails', async () => {
    cookieValues = { sam_access: 'expired-access', sam_refresh: 'bad-refresh' };

    fetchMock.mockImplementation(async (url: string) => {
      if (url === 'http://default-game-api/select-pool/claim') {
        return jsonResponse({ error: 'expired' }, 401);
      }
      if (url === 'http://localhost:8080/auth/refresh') {
        return jsonResponse({ error: 'invalid refresh' }, 401);
      }
      throw new Error(`unexpected fetch ${url}`);
    });

    const response = await POST(
      request('/api/game/select-pool/claim', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: '장수' }),
      }),
      context(['select-pool', 'claim']),
    );

    expect(response.status).toBe(401);
    // game-api 최초 시도 + refresh 시도 = 2회, refresh 실패 후 game-api 재시도는 없음
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });
});
