// @vitest-environment node
import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('next/headers', () => ({
  cookies: async () => ({
    get: (name: string) => (name === 'sam_access' ? { value: 'access' } : undefined),
    set: vi.fn(),
    delete: vi.fn(),
  }),
}));

import { POST as changePassword } from '@/app/api/account/password/route';
import { DELETE as deleteAccount } from '@/app/api/account/route';

// gateway-api 는 ApiError{message,status} 를 준다 — 화면이 읽는 {error} 로 옮겨야 서버 문장이 보인다(설계서 §2.5 A12).
function upstream(status: number, body: string): void {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(body, { status, headers: { 'Content-Type': 'application/json' } })));
}

function request(method: string, body: unknown): Request {
  return new Request('http://localhost:3000/api/account', { method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
}

describe('account routes surface the server sentence', () => {
  beforeEach(() => vi.clearAllMocks());

  it('maps a refused password change {message} to {error} and keeps the status', async () => {
    upstream(400, JSON.stringify({ message: '현재 비밀번호가 맞지 않습니다.', status: 400 }));
    const res = await changePassword(request('POST', { currentPassword: 'x', newPassword: 'newpass1' }));
    expect(res.status).toBe(400);
    expect(await res.json()).toEqual({ error: '현재 비밀번호가 맞지 않습니다.' });
  });

  it('falls back to a plain sentence when the upstream body is not JSON', async () => {
    upstream(500, '<html>oops</html>');
    const res = await changePassword(request('POST', { currentPassword: 'x', newPassword: 'newpass1' }));
    expect(res.status).toBe(500);
    expect(await res.json()).toEqual({ error: '비밀번호를 바꾸지 못했습니다.' });
  });

  it('passes a successful password change through', async () => {
    upstream(200, '{}');
    const res = await changePassword(request('POST', { currentPassword: 'x', newPassword: 'newpass1' }));
    expect(res.status).toBe(200);
  });

  it('maps a refused account deletion {message} to {error}', async () => {
    upstream(401, JSON.stringify({ message: '비밀번호가 올바르지 않습니다.', status: 401 }));
    const res = await deleteAccount(request('DELETE', { currentPassword: 'wrong' }));
    expect(res.status).toBe(401);
    expect(await res.json()).toEqual({ error: '비밀번호가 올바르지 않습니다.' });
  });

  it('clears both auth cookies when the account is deleted — the screen relies on this instead of logout()', async () => {
    upstream(200, '{}');
    const res = await deleteAccount(request('DELETE', { currentPassword: 'oldpass' }));
    expect(res.status).toBe(200);
    expect(res.cookies.get('sam_access')).toMatchObject({ value: '', maxAge: 0 });
    expect(res.cookies.get('sam_refresh')).toMatchObject({ value: '', maxAge: 0 });
  });
});
