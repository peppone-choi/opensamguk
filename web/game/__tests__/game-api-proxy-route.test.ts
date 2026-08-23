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

import { GET, POST, PATCH } from '@/app/api/game/[...path]/route';

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

function sseOkResponse(chunks: string[]): Response {
  const encoder = new TextEncoder();
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(encoder.encode(chunk));
      controller.close();
    },
  });
  return new Response(body, {
    status: 200,
    headers: { 'Content-Type': 'text/event-stream;charset=UTF-8' },
  });
}

async function readAll(body: ReadableStream<Uint8Array> | null): Promise<string> {
  if (!body) return '';
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let out = '';
  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    out += decoder.decode(value);
  }
  return out;
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

  // #516 review F1 — this route lacked a PATCH export while its production twin
  // (web/gateway) always had one; a live caller (admin1's game-settings save button)
  // got a dev-only 405 as a result. Regression: PATCH must actually forward.
  it('forwards PATCH (e.g. admin game-settings save)', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ ok: true }));

    const response = await PATCH(
      request('/api/game/api/admin/game-settings', {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ values: { maxgeneral: 650 } }),
      }),
      context(['api', 'admin', 'game-settings']),
    );

    expect(response.status).toBe(200);
    expect(fetchMock).toHaveBeenCalledWith('http://default-game-api/api/admin/game-settings', {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      cache: 'no-store',
      duplex: 'half',
      body: JSON.stringify({ values: { maxgeneral: 650 } }),
    });
  });

  it('plainly passes through a 401 from an expired access — no server-side refresh (sam_refresh never reaches this route, see cookie-refresh-path-scope.test.ts)', async () => {
    cookieValues = { sam_access: 'expired-access', sam_refresh: 'refresh-ok' };
    fetchMock.mockResolvedValue(jsonResponse({ error: 'expired' }, 401));

    const response = await POST(
      request('/api/game/select-pool/claim', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: '장수' }),
      }),
      context(['select-pool', 'claim']),
    );

    expect(response.status).toBe(401);
    expect(await response.json()).toEqual({ error: 'expired' });
    // 재시도 없음 — game-api 호출 딱 1회.
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});

describe('game API proxy SSE (/api/game/sse/turn) — #514 401 passthrough', () => {
  beforeEach(() => {
    cookieValues = { sam_access: 'expired-access' };
    registryMocks.resolveGameApiUrl.mockImplementation(() => 'http://default-game-api');
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
  });

  it('returns upstream 401 plainly instead of opening a text/event-stream (200 + {})', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ error: 'expired' }, 401));

    const response = await GET(request('/api/game/sse/turn'), context(['sse', 'turn']));

    expect(response.status).toBe(401);
    expect(response.headers.get('content-type')).not.toContain('text/event-stream');
    expect(await response.json()).toEqual({ error: 'expired' });
  });

  it('passes an ok upstream through as a live event-stream', async () => {
    fetchMock.mockResolvedValue(sseOkResponse(['event: turnCompleted\ndata: {}\n\n']));

    const response = await GET(request('/api/game/sse/turn'), context(['sse', 'turn']));

    expect(response.status).toBe(200);
    expect(response.headers.get('content-type')).toContain('text/event-stream');
    const text = await readAll(response.body);
    expect(text).toContain(': proxy-connected');
    expect(text).toContain('event: turnCompleted');
  });
});
