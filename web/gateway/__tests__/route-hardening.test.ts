// @vitest-environment node
// 게이트웨이 route 정리 — 게임 프록시 경로 허용 목록 · 초상 원본 응답 형식 · 본문 크기 선검사.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';

const registryMocks = vi.hoisted(() => ({
  getServers: vi.fn(),
  isValidEmptyServerRegistry: vi.fn(),
  resolveGameApiOrigin: vi.fn(),
}));
let access: string | undefined = 'access-token';

vi.mock('next/headers', () => ({
  cookies: async () => ({ get: (name: string) => (name === 'sam_access' && access ? { value: access } : undefined) }),
}));
vi.mock('@/lib/serverRegistry', () => registryMocks);
vi.mock('@/lib/serverPublication', () => ({
  // 공개 목록(C8)은 레지스트리 흉내에서 만든다 — 비었고 「유효한 빈 표」가 아니면 원천 불명(UNKNOWN)
  readPublicServers: async () => {
    const servers = registryMocks.getServers() as { id: string; name: string; generation?: number }[];
    if (servers.length === 0 && !registryMocks.isValidEmptyServerRegistry()) return { kind: 'unknown' };
    return { kind: 'known', servers: servers.map((s) => ({ id: s.id, name: s.name, generation: s.generation ?? null, gameUrl: `/game/${s.id}` })) };
  },
}));

import * as game from '@/app/api/game/[...path]/route';
import * as board from '@/app/api/board/[...path]/route';
import * as proxy from '@/app/api/proxy/[...path]/route';
import { POST as uploadIcon } from '@/app/api/account/profile-icon/route';
import { GET as source } from '@/app/api/account/profile-icon/source/route';
import { GET as crops } from '@/app/api/account/profile-icon/crops/route';

const ctx = (path: string[]) => ({ params: Promise.resolve({ path }) });
const req = (method: string, headers: Record<string, string> = {}, body?: string) =>
  new NextRequest('http://gateway.example.test/api/x', { method, headers, ...(body === undefined ? {} : { body }) });
const ok = () => new Response(JSON.stringify({ ok: true }), { status: 200, headers: { 'Content-Type': 'application/json' } });

beforeEach(() => {
  access = 'access-token';
  registryMocks.getServers.mockReturnValue([{ id: 'pep', name: 'Pep', gameApiUrl: 'http://pep-game-api' }]);
  registryMocks.isValidEmptyServerRegistry.mockReturnValue(false);
  registryMocks.resolveGameApiOrigin.mockImplementation((id: string) => (id === 'pep' ? 'http://pep-game-api' : undefined));
  vi.stubGlobal('fetch', vi.fn().mockImplementation(async () => ok()));
});
afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe('게임 프록시 경로 허용 목록', () => {
  it('api/ 아래 경로는 보낸다(양성 대조)', async () => {
    const res = await game.GET(req('GET'), ctx(['api', 'general', 'me']));
    expect(res.status).toBe(200);
    expect(fetch).toHaveBeenCalledWith('http://pep-game-api/api/general/me', expect.anything());
  });

  it.each([
    [['actuator', 'health']],
    [['internal', 'users']],
    [['api', 'internal', 'profile-icon-sync']],
    [['api', 'actuator', 'health']],
    [['admin', 'servers']],
    [['api']],
    [['sse', 'other']],
    [['api', '..', 'actuator']],
    [['api', '.', 'general']],
    [['api', '', 'general']],
    [['api', 'general/../../actuator']],
    [['api', 'general\\x']],
  ])('경로 %j 는 404 이고 위로 보내지 않는다', async (path) => {
    const res = await game.GET(req('GET'), ctx(path));
    expect(res.status).toBe(404);
    expect(fetch).not.toHaveBeenCalled();
  });
});

describe('본문 크기 선검사', () => {
  const big = { 'content-length': String(2 * 1024 * 1024), 'content-type': 'application/json' };

  it('작은 JSON 본문은 지난다(양성 대조)', async () => {
    const res = await game.POST(req('POST', { 'content-length': '2', 'content-type': 'application/json' }, '{}'), ctx(['api', 'command', 'x']));
    expect(res.status).toBe(200);
  });

  it.each([
    ['게임', () => game.POST(req('POST', big, '{}'), ctx(['api', 'command', 'x']))],
    ['게시판', () => board.POST(req('POST', big, '{}'), ctx(['posts']))],
    ['운영 콘솔', () => proxy.POST(req('POST', big, '{}'), ctx(['admin', 'notices']))],
  ])('%s 프록시: content-length 가 상한을 넘으면 413 이고 위로 보내지 않는다', async (_name, call) => {
    const res = await call();
    expect(res.status).toBe(413);
    expect(fetch).not.toHaveBeenCalled();
  });

  it('초상 올리기: content-length 가 상한(원본 8MB + 여유)을 넘으면 413', async () => {
    const res = await uploadIcon(new Request('http://gateway.example.test/api/account/profile-icon', {
      method: 'POST',
      headers: { 'content-length': String(9 * 1024 * 1024), 'content-type': 'multipart/form-data; boundary=x' },
      body: '--x--',
    }));
    expect(res.status).toBe(413);
    expect(fetch).not.toHaveBeenCalled();
  });
});

describe('초상 원본 · 자르기 응답 형식', () => {
  it('원본은 이미지 형식만 내보내고, 내려받기 · sandbox 로 붙인다(양성 대조)', async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(new Uint8Array([1, 2, 3]), { headers: { 'Content-Type': 'image/webp' } }));
    const res = await source();
    expect(res.status).toBe(200);
    expect(res.headers.get('content-type')).toBe('image/webp');
    expect(res.headers.get('content-disposition')).toBe('attachment');
    expect(res.headers.get('content-security-policy')).toBe('sandbox');
  });

  it.each([['text/html'], ['image/svg+xml'], ['application/octet-stream'], ['']])('원본 Content-Type %j 는 내보내지 않는다(502)', async (type) => {
    vi.mocked(fetch).mockResolvedValue(new Response('<html></html>', { headers: type ? { 'Content-Type': type } : {} }));
    const res = await source();
    expect(res.status).toBe(502);
    expect(await res.text()).not.toContain('<html>');
  });

  it('자르기 정보는 JSON 만 내보낸다', async () => {
    vi.mocked(fetch).mockResolvedValue(new Response('{"hero":{}}', { headers: { 'Content-Type': 'application/json' } }));
    expect((await crops()).status).toBe(200);
    vi.mocked(fetch).mockResolvedValue(new Response('<html></html>', { headers: { 'Content-Type': 'text/html' } }));
    expect((await crops()).status).toBe(502);
  });
});
