// @vitest-environment node
// 공개 경로의 게이트웨이 쪽 기본 동작. 본문 허용 목록 · 상태 짝 · 전송 규칙은 C8 fixture 78건(public-feeds-c8.test.ts)이 본다.
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';

const registry = vi.hoisted(() => ({ resolveGameApiOrigin: vi.fn() }));
vi.mock('@/lib/serverRegistry', () => registry);

import { GET as events } from '@/app/api/server-events/[id]/route';

const ctx = (id: string) => ({ params: Promise.resolve({ id }) });
const ok = () => new Response(JSON.stringify({ events: [], nextCursor: null }), { status: 200, headers: { 'content-type': 'application/json' } });

beforeEach(() => {
    registry.resolveGameApiOrigin.mockImplementation((id: string) => (id === 'pep' ? 'http://spep-game-api:8081' : undefined));
    vi.stubGlobal('fetch', vi.fn(async () => ok()));
});

describe('천하 정세 경로 /api/server-events/{id}', () => {
    it('그 서버 game-api 의 공개 피드로 넘기고, 개수는 1–30 으로 자른다(기본 5)', async () => {
        await events(new NextRequest('http://gw.test/api/server-events/pep?limit=5'), ctx('pep'));
        await events(new NextRequest('http://gw.test/api/server-events/pep?limit=999'), ctx('pep'));
        await events(new NextRequest('http://gw.test/api/server-events/pep?limit=abc'), ctx('pep'));
        await events(new NextRequest('http://gw.test/api/server-events/pep?limit=0'), ctx('pep'));
        expect(vi.mocked(fetch).mock.calls.map(([url]) => url)).toEqual([
            'http://spep-game-api:8081/api/world-events?limit=5',
            'http://spep-game-api:8081/api/world-events?limit=30',
            'http://spep-game-api:8081/api/world-events?limit=5',
            'http://spep-game-api:8081/api/world-events?limit=1',
        ]);
    });

    it('정상 응답만 30초 공개 캐시, 연결 실패는 502 no-store', async () => {
        expect((await events(new NextRequest('http://gw.test/api/server-events/pep'), ctx('pep'))).headers.get('cache-control')).toBe('public, max-age=30');
        vi.mocked(fetch).mockRejectedValueOnce(new Error('down'));
        const down = await events(new NextRequest('http://gw.test/api/server-events/pep'), ctx('pep'));
        expect(down.status).toBe(502);
        expect(down.headers.get('cache-control')).toBe('no-store');
    });
});

describe('응답 형식은 정확히 application/json 만(C8 #1098 P3)', () => {
    const typed = (contentType: string) => new Response(JSON.stringify({ events: [], nextCursor: null }), { status: 200, headers: { 'content-type': contentType } });

    it.each(['application/jsonp', 'application/json-seq', 'application/jsonx; charset=utf-8', 'text/json', 'application/javascript'])('%s 는 502 no-store', async (contentType) => {
        vi.mocked(fetch).mockResolvedValueOnce(typed(contentType));
        const response = await events(new NextRequest('http://gw.test/api/server-events/pep'), ctx('pep'));
        expect(response.status).toBe(502);
        expect(response.headers.get('cache-control')).toBe('no-store');
    });

    it.each(['application/json', 'application/json; charset=utf-8', 'Application/JSON;charset=UTF-8'])('%s 는 받는다', async (contentType) => {
        vi.mocked(fetch).mockResolvedValueOnce(typed(contentType));
        const response = await events(new NextRequest('http://gw.test/api/server-events/pep'), ctx('pep'));
        expect(response.status).toBe(200);
    });
});
