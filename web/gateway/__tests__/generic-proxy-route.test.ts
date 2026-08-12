import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';

let accessCookie: string | undefined;

vi.mock('next/headers', () => ({
  cookies: async () => ({
    get: (name: string) => (name === 'sam_access' && accessCookie ? { value: accessCookie } : undefined),
  }),
}));

vi.mock('@/lib/server-api', () => ({
  GATEWAY_API_URL: 'http://gateway-api.test',
  GATEWAY_UPSTREAM_TIMEOUT_MS: 10_000,
  isGatewayTimeout: (error: unknown) => error instanceof Error && error.name === 'TimeoutError',
}));

import { DELETE } from '@/app/api/proxy/[...path]/route';

describe('generic gateway proxy', () => {
  beforeEach(() => {
    accessCookie = 'access-token';
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 204 })));
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('forwards an empty upstream 204 without constructing an invalid response body', async () => {
    const response = await DELETE(
      new NextRequest('http://gateway.example.test/api/proxy/admin/servers/8', { method: 'DELETE' }),
      { params: Promise.resolve({ path: ['admin', 'servers', '8'] }) },
    );

    expect(response.status).toBe(204);
    await expect(response.text()).resolves.toBe('');
    expect(fetch).toHaveBeenCalledWith('http://gateway-api.test/admin/servers/8', expect.objectContaining({
      method: 'DELETE',
      headers: {
        Authorization: 'Bearer access-token',
        'Content-Type': 'application/json',
      },
      cache: 'no-store',
      body: '',
      signal: expect.any(AbortSignal),
    }));
  });

  it('maps an upstream connection failure to a gateway 502 response', async () => {
    vi.mocked(fetch).mockRejectedValue(new Error('connection refused'));

    const response = await DELETE(
      new NextRequest('http://gateway.example.test/api/proxy/admin/servers/8', { method: 'DELETE' }),
      { params: Promise.resolve({ path: ['admin', 'servers', '8'] }) },
    );

    expect(response.status).toBe(502);
    await expect(response.json()).resolves.toEqual({ error: '게이트웨이에 연결할 수 없습니다.' });
  });

  it('maps an upstream timeout to a gateway 504 response', async () => {
    const timeout = new Error('timed out');
    timeout.name = 'TimeoutError';
    vi.mocked(fetch).mockRejectedValue(timeout);

    const response = await DELETE(
      new NextRequest('http://gateway.example.test/api/proxy/admin/servers/8', { method: 'DELETE' }),
      { params: Promise.resolve({ path: ['admin', 'servers', '8'] }) },
    );

    expect(response.status).toBe(504);
    await expect(response.json()).resolves.toEqual({ error: '게이트웨이 응답 시간이 초과되었습니다.' });
  });
});
