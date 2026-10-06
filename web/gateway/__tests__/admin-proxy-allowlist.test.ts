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

import * as route from '@/app/api/proxy/[...path]/route';

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
const ctx = (path: string[]) => ({ params: Promise.resolve({ path }) });
const req = (method: string, body?: string) =>
  new NextRequest('http://gateway.example.test/api/proxy/x', { method, ...(body === undefined ? {} : { body }) });

describe('운영 콘솔 프록시 — 경로 허용 목록 · 응답 정리', () => {
  beforeEach(() => {
    accessCookie = 'access-token';
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ ok: true })));
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('admin/ 아래 경로는 보낸다(양성 대조)', async () => {
    const res = await route.GET(req('GET'), ctx(['admin', 'servers']));
    expect(res.status).toBe(200);
    expect(fetch).toHaveBeenCalledWith('http://gateway-api.test/admin/servers', expect.objectContaining({ method: 'GET' }));
  });

  it.each([
    [['auth', 'me']],
    [['internal', 'servers']],
    [['actuator', 'health']],
    [['account', 'nickname']],
    [['notices']],
    [['admin']],
  ])('admin/ 아래가 아닌 경로 %j 는 404 이고 위로 보내지 않는다', async (path) => {
    const res = await route.GET(req('GET'), ctx(path));
    expect(res.status).toBe(404);
    expect(fetch).not.toHaveBeenCalled();
  });

  it.each([
    [['admin', '..', 'auth', 'me']],
    [['admin', '.', 'servers']],
    [['admin', '', 'servers']],
    [['admin', 'servers/../../auth']],
    [['admin', 'servers\\x']],
    [['admin', 'servers\tx']],
  ])('경로 조각 %j 는 404 이고 위로 보내지 않는다', async (path) => {
    const res = await route.GET(req('GET'), ctx(path));
    expect(res.status).toBe(404);
    expect(fetch).not.toHaveBeenCalled();
  });

  it('경로 조각은 다시 인코딩해 보낸다', async () => {
    await route.GET(req('GET'), ctx(['admin', 'servers', '통일 서버']));
    expect(fetch).toHaveBeenCalledWith(`http://gateway-api.test/admin/servers/${encodeURIComponent('통일 서버')}`, expect.anything());
  });

  it('응답 JSON 에서 토큰 필드를 지우고, 캐시하지 않게 한다', async () => {
    vi.mocked(fetch).mockResolvedValue(json({ ok: true, accessToken: 'a', nested: { refreshToken: 'r', keep: 1 }, list: [{ refreshToken: 'z', id: 2 }] }));
    const res = await route.GET(req('GET'), ctx(['admin', 'users']));
    expect(res.headers.get('cache-control')).toBe('no-store');
    await expect(res.json()).resolves.toEqual({ ok: true, nested: { keep: 1 }, list: [{ id: 2 }] });
  });

  it('JSON 이 아닌 응답도 캐시하지 않게 한다', async () => {
    vi.mocked(fetch).mockResolvedValue(new Response('plain', { status: 200, headers: { 'Content-Type': 'text/plain' } }));
    const res = await route.GET(req('GET'), ctx(['admin', 'version']));
    expect(res.headers.get('cache-control')).toBe('no-store');
    await expect(res.text()).resolves.toBe('plain');
  });

  it('PUT 을 보낸다(공지 수정)', async () => {
    expect(typeof (route as Record<string, unknown>).PUT).toBe('function');
    const put = (route as unknown as { PUT: typeof route.POST }).PUT;
    const res = await put(req('PUT', JSON.stringify({ title: 't' })), ctx(['admin', 'notices', '5']));
    expect(res.status).toBe(200);
    expect(fetch).toHaveBeenCalledWith('http://gateway-api.test/admin/notices/5', expect.objectContaining({ method: 'PUT', body: '{"title":"t"}' }));
  });

  it('로그인 쿠키가 없으면 401(허용 경로도)', async () => {
    accessCookie = undefined;
    const res = await route.GET(req('GET'), ctx(['admin', 'servers']));
    expect(res.status).toBe(401);
    expect(fetch).not.toHaveBeenCalled();
  });
});
