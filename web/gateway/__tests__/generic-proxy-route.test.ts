import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';

let accessCookie: string | undefined;

vi.mock('next/headers', () => ({
  cookies: async () => ({
    get: (name: string) => (name === 'sam_access' && accessCookie ? { value: accessCookie } : undefined),
  }),
}));

vi.mock('@/lib/server-api', () => ({ GATEWAY_API_URL: 'http://gateway-api.test' }));

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
    expect(fetch).toHaveBeenCalledWith('http://gateway-api.test/admin/servers/8', {
      method: 'DELETE',
      headers: {
        Authorization: 'Bearer access-token',
        'Content-Type': 'application/json',
      },
      cache: 'no-store',
      body: '',
    });
  });
});
