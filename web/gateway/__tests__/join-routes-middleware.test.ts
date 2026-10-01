// @vitest-environment node
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';

vi.mock('@/lib/server-api', () => ({ GATEWAY_API_URL: 'http://gateway-api.test' }));

import { POST as register } from '@/app/api/auth/register/route';
import { middleware } from '@/middleware';

const post = (body: unknown) => new NextRequest('http://gw.test/api/auth/register', { method: 'POST', body: JSON.stringify(body), headers: { 'Content-Type': 'application/json' } });

describe('가입 경로', () => {
    beforeEach(() => vi.stubGlobal('fetch', vi.fn()));

    it('가입 서버에 못 닿으면 502 와 쉬운 말(전에는 처리가 없어 500)', async () => {
        vi.mocked(fetch).mockRejectedValueOnce(new Error('ECONNREFUSED'));
        const response = await register(post({ username: 'hahoudon', password: 'secret1', nickname: '원양' }));
        expect(response.status).toBe(502);
        expect(await response.json()).toEqual({ error: '가입 서버에 연결할 수 없습니다.' });
    });

    it('서버 거절 문장은 받은 그대로 넘긴다', async () => {
        vi.mocked(fetch).mockResolvedValueOnce(new Response(JSON.stringify({ message: '이미 사용 중인 별명입니다' }), { status: 409 }));
        const response = await register(post({ username: 'hahoudon', password: 'secret1', nickname: '원양' }));
        expect(response.status).toBe(409);
        expect(await response.json()).toEqual({ error: '이미 사용 중인 별명입니다' });
    });
});

describe('미들웨어 — 보호 경로 · 손님 전용', () => {
    const at = (path: string, session = false) => middleware(new NextRequest(`http://gw.test${path}`, session ? { headers: { cookie: 'sam_access=x' } } : undefined));

    it('로그인한 사람이 가입 · 로그인에 오면 로비로', () => {
        expect(at('/join', true).headers.get('location')).toBe('http://gw.test/lobby');
        expect(at('/login', true).headers.get('location')).toBe('http://gw.test/lobby');
        expect(at('/join').headers.get('location')).toBeNull();
    });

    it('계정은 서버 렌더 전에 막는다(전에는 클라이언트만 막았다) · 옛 /entrance 는 보호 목록에서 뺐다', () => {
        expect(at('/account').headers.get('location')).toBe('http://gw.test/login?next=%2Faccount');
        expect(at('/lobby').headers.get('location')).toBe('http://gw.test/login?next=%2Flobby');
        expect(at('/entrance').headers.get('location')).toBeNull();
    });
});
