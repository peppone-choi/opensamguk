// @vitest-environment node
import { afterAll, afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';

const registryMocks = vi.hoisted(() => ({
  getServers: vi.fn(),
  isValidEmptyServerRegistry: vi.fn(),
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
const originalServerId = process.env.SERVER_ID;

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
    delete process.env.SERVER_ID;
    registryMocks.getServers.mockReturnValue([{ id: 'pep', name: 'Pep', gameApiUrl: 'http://pep-game-api' }]);
    registryMocks.isValidEmptyServerRegistry.mockReturnValue(false);
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
    if (originalServerId === undefined) delete process.env.SERVER_ID;
    else process.env.SERVER_ID = originalServerId;
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
    process.env.SERVER_ID = 'pep';
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

  it('uses the compatibility origin for an explicit configured single-server selection', async () => {
    process.env.SERVER_ID = 's1';
    registryMocks.getServers.mockReturnValue([]);
    registryMocks.isValidEmptyServerRegistry.mockReturnValue(true);
    registryMocks.resolveGameApiOrigin.mockReturnValue(undefined);
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{"ok":true}', {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })));

    const response = await GET(request('/api/game/front-info?server=s1'), context(['front-info']));

    expect(response.status).toBe(200);
    expect(fetch).toHaveBeenCalledWith('http://default-game-api/front-info', {
      method: 'GET',
      headers: {},
      cache: 'no-store',
    });
  });

  it.each([
    ['s2', 's1'],
    ['s1', 'S1'],
  ])('fails closed when %s does not exactly match a validated configured server ID %s', async (selectedId, configuredId) => {
    process.env.SERVER_ID = configuredId;
    registryMocks.getServers.mockReturnValue([]);
    registryMocks.isValidEmptyServerRegistry.mockReturnValue(true);
    registryMocks.resolveGameApiOrigin.mockReturnValue(undefined);

    const response = await GET(request(`/api/game/front-info?server=${selectedId}`), context(['front-info']));

    expect(response.status).toBe(503);
    expect(fetch).not.toHaveBeenCalled();
  });

  it('does not use the compatibility origin when the registry is not valid and empty', async () => {
    process.env.SERVER_ID = 's1';
    registryMocks.getServers.mockReturnValue([]);
    registryMocks.isValidEmptyServerRegistry.mockReturnValue(false);
    registryMocks.resolveGameApiOrigin.mockReturnValue(undefined);
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{"ok":true}', {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })));

    const response = await GET(request('/api/game/front-info?server=s1'), context(['front-info']));

    expect(response.status).toBe(503);
    expect(fetch).not.toHaveBeenCalled();
  });

  it('does not use the default compatibility origin when the registry is not valid and empty', async () => {
    registryMocks.getServers.mockReturnValue([]);
    registryMocks.isValidEmptyServerRegistry.mockReturnValue(false);
    registryMocks.resolveGameApiOrigin.mockReturnValue(undefined);

    const response = await GET(request('/api/game/front-info'), context(['front-info']));

    expect(response.status).toBe(503);
    expect(fetch).not.toHaveBeenCalled();
  });

  it('uses the default origin only when there is no explicit selection', async () => {
    registryMocks.getServers.mockReturnValue([]);
    registryMocks.isValidEmptyServerRegistry.mockReturnValue(true);
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

describe('game API proxy SSE (/api/game/sse/turn) — #514 401 passthrough', () => {
  beforeEach(() => {
    cookieValues = { sam_access: 'expired-access' };
    process.env.GAME_API_ORIGIN = 'http://default-game-api';
    delete process.env.SERVER_ID;
    registryMocks.getServers.mockReturnValue([]);
    registryMocks.isValidEmptyServerRegistry.mockReturnValue(true);
    registryMocks.resolveGameApiOrigin.mockReturnValue(undefined);
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('returns upstream 401 plainly instead of opening a text/event-stream (200 + {})', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ error: 'expired' }), {
          status: 401,
          headers: { 'Content-Type': 'application/json' },
        }),
      ),
    );

    const response = await GET(request('/api/game/sse/turn'), context(['sse', 'turn']));

    expect(response.status).toBe(401);
    expect(response.headers.get('content-type')).not.toContain('text/event-stream');
    expect(await response.json()).toEqual({ error: 'expired' });
  });

  it('passes an ok upstream through as a live event-stream', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(sseOkResponse(['event: turnCompleted\ndata: {}\n\n'])),
    );

    const response = await GET(request('/api/game/sse/turn'), context(['sse', 'turn']));

    expect(response.status).toBe(200);
    expect(response.headers.get('content-type')).toContain('text/event-stream');
    const text = await readAll(response.body);
    expect(text).toContain(': proxy-connected');
    expect(text).toContain('event: turnCompleted');
  });
});
