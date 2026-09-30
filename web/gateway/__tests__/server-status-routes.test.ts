// @vitest-environment node
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';

const registry = vi.hoisted(() => ({ resolveGameApiOrigin: vi.fn() }));
vi.mock('@/lib/serverRegistry', () => registry);

import { GET as events } from '@/app/api/server-events/[id]/route';
import { GET as imperial } from '@/app/api/server-imperial/[id]/route';

const ctx = (id: string) => ({ params: Promise.resolve({ id }) });

beforeEach(() => {
    registry.resolveGameApiOrigin.mockImplementation((id: string) => (id === 'pep' ? 'http://spep-game-api:8081' : undefined));
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ events: [], nextCursor: null }), { status: 200, headers: { 'Content-Type': 'application/json' } })));
});

describe('천하 정세 프록시 /api/server-events/{id}', () => {
    it('그 서버 game-api 의 공개 피드로 넘기고, 개수는 1–30 으로 자른다', async () => {
        await events(new NextRequest('http://gw.test/api/server-events/pep?limit=5'), ctx('pep'));
        expect(vi.mocked(fetch).mock.calls[0][0]).toBe('http://spep-game-api:8081/api/world-events?limit=5');
        await events(new NextRequest('http://gw.test/api/server-events/pep?limit=999'), ctx('pep'));
        expect(vi.mocked(fetch).mock.calls[1][0]).toBe('http://spep-game-api:8081/api/world-events?limit=30');
        await events(new NextRequest('http://gw.test/api/server-events/pep?limit=abc'), ctx('pep'));
        expect(vi.mocked(fetch).mock.calls[2][0]).toBe('http://spep-game-api:8081/api/world-events?limit=5');
    });
    it('모르는 서버 404, 연결 실패 502', async () => {
        expect((await events(new NextRequest('http://gw.test/api/server-events/nope'), ctx('nope'))).status).toBe(404);
        vi.mocked(fetch).mockRejectedValueOnce(new Error('down'));
        expect((await events(new NextRequest('http://gw.test/api/server-events/pep'), ctx('pep'))).status).toBe(502);
    });
});

describe('황제 소재 프록시 /api/server-imperial/{id}', () => {
    it('상태 코드(409 STATE_UNAVAILABLE 포함)를 그대로 넘긴다', async () => {
        vi.mocked(fetch).mockResolvedValueOnce(new Response(JSON.stringify({ status: 'STATE_UNAVAILABLE', badges: [] }), { status: 409 }));
        const response = await imperial(new Request('http://gw.test/api/server-imperial/pep'), ctx('pep'));
        expect(vi.mocked(fetch).mock.calls[0][0]).toBe('http://spep-game-api:8081/api/imperial/presence');
        expect(response.status).toBe(409);
        expect((await imperial(new Request('http://gw.test/api/server-imperial/nope'), ctx('nope'))).status).toBe(404);
    });
});

describe('공개 경로 — 익명 호출 · 캐시', () => {
    it('쿠키 · 인증 헤더를 넘기지 않고, 성공만 30초 캐시한다', async () => {
        const req = new NextRequest('http://gw.test/api/server-events/pep', { headers: { cookie: 'sam_access=secret', authorization: 'Bearer secret' } });
        const ok = await events(req, ctx('pep'));
        const init = vi.mocked(fetch).mock.calls[0][1] as RequestInit | undefined;
        expect(init?.headers).toBeUndefined();
        expect(init?.credentials).toBeUndefined();
        expect(ok.headers.get('cache-control')).toBe('public, max-age=30');
        vi.mocked(fetch).mockResolvedValueOnce(new Response('{}', { status: 503 }));
        const bad = await events(new NextRequest('http://gw.test/api/server-events/pep'), ctx('pep'));
        expect(bad.headers.get('cache-control')).toBe('no-store');
    });
});

describe('공개 필드 허용 목록 — 적색 fixture', () => {
    const upstream = (body: unknown, status = 200) => vi.mocked(fetch).mockResolvedValueOnce(new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }));

    it('WORLD 가 아닌 사건 · 모르는 종류 · 허용되지 않은 역할 · 사실 · 모르는 칸은 버린다', async () => {
        upstream({
            events: [
                { id: 1, kind: 'county.ownerChanged', section: 'WORLD', occurredAt: { year: 200, month: 3, phase: 2, ordinal: 0 },
                    refs: { CITY: 12, FROM_NATION: 2, TO_NATION: 1, ACTOR: 77, CORPS: 9 }, facts: { MONEY: 5000 }, secret: 'x' },
                { id: 2, kind: 'dispatch.received', section: 'COURT', occurredAt: { year: 200, month: 3, phase: 2, ordinal: 1 }, refs: { ISSUER: 3 }, facts: {} },
                { id: 3, kind: 'renown.event', section: 'PERSONAL', occurredAt: { year: 200, month: 3, phase: 2, ordinal: 2 }, refs: { ACTOR: 7 }, facts: {} },
                { id: 4, kind: 'battle.opened', section: 'WORLD', occurredAt: { year: 200, month: 3, phase: 2, ordinal: 3 }, refs: { CITY: 12 }, facts: {} },
                { id: 5, kind: 'yuedan.announced', section: 'WORLD', occurredAt: { year: 200, month: 3, phase: 1 }, refs: {}, facts: {} },
            ],
            nextCursor: 'opaque-cursor',
            worldId: 990002,
        });
        const response = await events(new NextRequest('http://gw.test/api/server-events/pep'), ctx('pep'));
        expect(response.status).toBe(200);
        expect(await response.json()).toEqual({
            events: [{ id: 1, kind: 'county.ownerChanged', section: 'WORLD', occurredAt: { year: 200, month: 3, phase: 2, ordinal: 0 },
                refs: { CITY: 12, FROM_NATION: 2, TO_NATION: 1 }, facts: {} }],
        });
    });

    it('형식이 틀린 응답 · upstream 오류는 흘리지 않고 502(캐시 없음)', async () => {
        upstream({ rows: [] });
        const bad = await events(new NextRequest('http://gw.test/api/server-events/pep'), ctx('pep'));
        expect(bad.status).toBe(502);
        expect(bad.headers.get('cache-control')).toBe('no-store');
        vi.mocked(fetch).mockResolvedValueOnce(new Response('<html>stack trace</html>', { status: 500 }));
        const down = await events(new NextRequest('http://gw.test/api/server-events/pep'), ctx('pep'));
        expect(down.status).toBe(502);
        expect(await down.text()).not.toContain('stack trace');
    });

    it('황제 소재는 계통 코드 · 이름 · 城만 — 장수 id · 노드 id · 조정 城은 내보내지 않는다', async () => {
        upstream({ status: 'READY', badges: [{ lineCode: 'HAN', lineName: '한', emperorGeneralId: 42, emperorNodeKind: 'LAND_PROVINCE', emperorNodeId: 'p-7', emperorCityId: 12, courtCityId: 12, extra: 1 }] });
        const response = await imperial(new Request('http://gw.test/api/server-imperial/pep'), ctx('pep'));
        expect(await response.json()).toEqual({ status: 'READY', badges: [{ lineCode: 'HAN', lineName: '한', emperorCityId: 12 }] });
        upstream({ status: 'NOT_SEEDED', badges: [{ lineCode: 'LEAK', lineName: 'x', emperorCityId: 1 }] });
        expect(await (await imperial(new Request('http://gw.test/api/server-imperial/pep'), ctx('pep'))).json()).toEqual({ status: 'NOT_SEEDED', badges: [] });
        upstream({ status: 'WHATEVER' });
        expect((await imperial(new Request('http://gw.test/api/server-imperial/pep'), ctx('pep'))).status).toBe(502);
    });

    it('등록되지 않은 서버 id 는 upstream 을 부르지 않고 404', async () => {
        for (const id of ['nope', 'pep.evil.example', '../pep', 'http://169.254.169.254']) {
            expect((await events(new NextRequest(`http://gw.test/api/server-events/${encodeURIComponent(id)}`), ctx(id))).status).toBe(404);
            expect((await imperial(new Request(`http://gw.test/api/server-imperial/${encodeURIComponent(id)}`), ctx(id))).status).toBe(404);
        }
        expect(fetch).not.toHaveBeenCalled();
    });
});
