// @vitest-environment node
import { beforeEach, describe, expect, it, vi } from 'vitest';

let accessCookie: string | undefined;
vi.mock('next/headers', () => ({
  cookies: async () => ({
    get: (name: string) => (name === 'sam_access' && accessCookie ? { value: accessCookie } : undefined),
  }),
}));

import { POST } from '@/app/api/account/nickname/route';

describe('nickname route', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    accessCookie = 'old-access';
  });

  it('replaces both httpOnly auth cookies from the successful upstream response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      accessToken: 'new-access',
      refreshToken: 'new-refresh',
      user: { id: 1, username: 'tester', email: null, nickname: '새별명', role: 'USER', picture: null, imageServer: 0 },
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })));

    const response = await POST(new Request('http://localhost:3000/api/account/nickname', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ nickname: '  새별명  ' }),
    }));

    expect(fetch).toHaveBeenCalledWith('http://localhost:8080/auth/account/nickname', expect.objectContaining({
      method: 'POST',
      headers: { Authorization: 'Bearer old-access', 'Content-Type': 'application/json' },
      body: JSON.stringify({ nickname: '  새별명  ' }),
    }));
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({
      user: { id: 1, username: 'tester', email: null, nickname: '새별명', role: 'USER', picture: null, imageServer: 0 },
    });
    const cookies = response.headers.getSetCookie();
    expect(cookies.some((cookie) => cookie.startsWith('sam_access=new-access;') && cookie.includes('HttpOnly'))).toBe(true);
    expect(cookies.some((cookie) => cookie.startsWith('sam_refresh=new-refresh;') && cookie.includes('HttpOnly'))).toBe(true);
  });

  it('preserves a duplicate nickname conflict and its Korean message', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      message: '이미 사용 중인 닉네임입니다.',
      status: 409,
    }), { status: 409, headers: { 'Content-Type': 'application/json' } })));

    const response = await POST(new Request('http://localhost:3000/api/account/nickname', {
      method: 'POST',
      body: JSON.stringify({ nickname: '중복별명' }),
    }));

    expect(response.status).toBe(409);
    expect(await response.json()).toEqual({ error: '이미 사용 중인 닉네임입니다.' });
  });
});
