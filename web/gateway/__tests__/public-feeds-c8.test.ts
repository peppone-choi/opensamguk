// @vitest-environment node
// C8 이 준비한 게이트웨이 공개 경로 fixture 78건(__tests__/fixtures/c8-public-feeds.json, 원본
// meta reports/opensamguk/evidence/2026-10-01-c8-anonymous-public-read/gateway-red-fixtures.json, 서버 main 60ba8df60)을
// 실제 경로 처리기(/api/server-events · /api/server-imperial)에 그대로 흘린다. 합성 자료다 — 네트워크 · DB 없음.
// `allowedOutcomes` 가 있는 경우는 그중 하나와 맞으면 된다(drop-only 투영 또는 통째 502, C0 수용).
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';

const ORIGIN = 'http://spep-game-api:8081';
const registry = vi.hoisted(() => ({ resolveGameApiOrigin: vi.fn() }));
vi.mock('@/lib/serverRegistry', () => registry);

import { GET as events } from '@/app/api/server-events/[id]/route';
import { GET as imperial } from '@/app/api/server-imperial/[id]/route';

type Outcome = { status?: number; cacheControl?: string; body?: unknown; safeConstantErrorOnly?: boolean };
type Case = {
    id: string;
    route: 'server-events' | 'server-imperial';
    upstream?: { status: number; contentType?: string; body?: unknown; bodyGenerator?: { bytes: number; fill: string }; failure?: 'timeout' | 'stream-error'; location?: string; requestedLimit?: number };
    incoming?: { query?: Record<string, string>; cookie?: string; authorization?: string; ids?: string[] };
    expected: Outcome & {
        allowedOutcomes?: Outcome[]; mustNotContain?: string[]; noRawExtras?: boolean; outputEventKeys?: string[]; preserveRefs?: Record<string, unknown>;
        facts?: Record<string, unknown>; outputBadgeKeys?: string[]; emperorCityId?: number | null; fetchCredentials?: string; fetchRedirect?: string;
        fetchHeadersMustExclude?: string[]; upstreamQueryKeys?: string[]; fetchCalls?: number;
    };
};

const FIXTURE = JSON.parse(readFileSync(join(__dirname, 'fixtures', 'c8-public-feeds.json'), 'utf8')) as { cases: Case[] };
const SENTINEL = 'SYNTHETIC_PRIVATE_SENTINEL';
const ctx = (id: string) => ({ params: Promise.resolve({ id }) });

function upstreamResponse(up: NonNullable<Case['upstream']>): Response {
    const headers: Record<string, string> = { 'content-type': up.contentType ?? 'application/json' };
    if (up.location) headers.location = up.location;
    if (up.failure === 'stream-error') {
        const body = new ReadableStream<Uint8Array>({ pull(controller) { controller.error(new Error(`read failed ${SENTINEL}`)); } });
        return new Response(body, { status: up.status, headers });
    }
    const text = up.bodyGenerator ? up.bodyGenerator.fill.repeat(up.bodyGenerator.bytes) : JSON.stringify(up.body ?? {});
    return new Response(text, { status: up.status, headers });
}

async function call(route: Case['route'], id = 'pep', query = '', headers?: Record<string, string>) {
    const url = `http://gw.test/api/${route}/${encodeURIComponent(id)}${query}`;
    const response = route === 'server-events' ? await events(new NextRequest(url, { headers }), ctx(id)) : await imperial(new Request(url, { headers }), ctx(id));
    const text = await response.text();
    let json: unknown = null;
    try { json = JSON.parse(text); } catch { /* 비 JSON 이면 null */ }
    return { status: response.status, cacheControl: response.headers.get('cache-control'), text, json };
}

const isConstantError = (json: unknown) =>
    typeof json === 'object' && json !== null && Object.keys(json).length === 1 && typeof (json as { error?: unknown }).error === 'string';

function matches(out: Awaited<ReturnType<typeof call>>, want: Outcome): boolean {
    if (want.status !== undefined && out.status !== want.status) return false;
    if (want.cacheControl !== undefined && out.cacheControl !== want.cacheControl) return false;
    if (want.body !== undefined && JSON.stringify(out.json) !== JSON.stringify(want.body)) return false;
    if (want.safeConstantErrorOnly && !isConstantError(out.json)) return false;
    return true;
}

beforeEach(() => {
    registry.resolveGameApiOrigin.mockImplementation((id: string) => (id === 'pep' ? ORIGIN : undefined));
    vi.stubGlobal('fetch', vi.fn());
});

describe('C8 공개 경로 fixture — 본문 · 상태 · 캐시', () => {
    const cases = FIXTURE.cases.filter((c) => c.upstream);
    it('fixture 가 비지 않았다(0건이 「검사가 안 돌았다」가 되지 않게)', () => {
        expect(cases.length).toBeGreaterThanOrEqual(70);
    });

    it.each(cases.map((c) => [c.id, c] as const))('%s', async (_id, c) => {
        const up = c.upstream!;
        vi.mocked(fetch).mockImplementation(async () => {
            if (up.failure === 'timeout') throw new DOMException('The operation was aborted due to timeout', 'TimeoutError');
            return upstreamResponse(up);
        });
        const out = await call(c.route, 'pep', up.requestedLimit ? `?limit=${up.requestedLimit}` : '');
        const e = c.expected;

        // 어느 경우든 비공개 표지 · upstream 오류 문장이 밖으로 나가지 않는다.
        expect(out.text).not.toContain(SENTINEL);
        for (const word of e.mustNotContain ?? []) expect(out.text).not.toContain(word);

        if (e.allowedOutcomes) {
            expect(e.allowedOutcomes.some((want) => matches(out, want)), `${c.id}: ${out.status} ${out.cacheControl} ${out.text.slice(0, 200)}`).toBe(true);
        } else {
            expect(matches(out, e), `${c.id}: ${out.status} ${out.cacheControl} ${out.text.slice(0, 200)}`).toBe(true);
        }
        if (out.status === 200 && out.json && typeof out.json === 'object') {
            const body = out.json as { events?: Record<string, unknown>[]; badges?: Record<string, unknown>[]; status?: string };
            if (c.route === 'server-events') {
                expect(Object.keys(body)).toEqual(['events']);
                for (const event of body.events ?? []) expect(Object.keys(event).sort()).toEqual(['facts', 'id', 'kind', 'occurredAt', 'refs', 'section']);
            } else {
                expect(Object.keys(body).sort()).toEqual(['badges', 'status']);
                for (const badge of body.badges ?? []) expect(Object.keys(badge).sort()).toEqual(['emperorCityId', 'lineCode', 'lineName']);
            }
            if (e.outputEventKeys) for (const event of body.events ?? []) expect(Object.keys(event).sort()).toEqual([...e.outputEventKeys].sort());
            if (e.preserveRefs) expect(body.events?.[0]?.refs).toEqual(e.preserveRefs);
            if (e.facts) expect(body.events?.[0]?.facts).toEqual(e.facts);
            if (e.outputBadgeKeys) for (const badge of body.badges ?? []) expect(Object.keys(badge).sort()).toEqual([...e.outputBadgeKeys].sort());
            if ('emperorCityId' in e) expect(body.badges?.[0]?.emperorCityId).toBe(e.emperorCityId);
        }
    });
});

describe('C8 공개 경로 fixture — 전송(익명 · 리다이렉트 · 쿼리) · 등록부', () => {
    const transport = FIXTURE.cases.filter((c) => c.incoming?.query || c.incoming?.cookie);
    const unregistered = FIXTURE.cases.filter((c) => c.incoming?.ids);

    it('전송 · 등록부 fixture 가 둘씩 있다', () => {
        expect(transport).toHaveLength(2);
        expect(unregistered).toHaveLength(2);
    });

    it.each(transport.map((c) => [c.id, c] as const))('%s', async (_id, c) => {
        vi.mocked(fetch).mockResolvedValue(c.route === 'server-events'
            ? new Response(JSON.stringify({ events: [], nextCursor: null }), { status: 200, headers: { 'content-type': 'application/json' } })
            : new Response(JSON.stringify({ status: 'NOT_SEEDED', badges: [] }), { status: 200, headers: { 'content-type': 'application/json' } }));
        const query = `?${new URLSearchParams(c.incoming!.query).toString()}`;
        await call(c.route, 'pep', query, { cookie: c.incoming!.cookie!, authorization: c.incoming!.authorization! });
        expect(fetch).toHaveBeenCalledTimes(1);
        const [url, init] = vi.mocked(fetch).mock.calls[0] as [string, RequestInit];
        const target = new URL(url);
        expect(target.origin).toBe(ORIGIN);
        expect([...target.searchParams.keys()].sort()).toEqual([...c.expected.upstreamQueryKeys!].sort());
        expect(init.credentials).toBe(c.expected.fetchCredentials);
        expect(['error', 'manual']).toContain(init.redirect);
        const sent = new Headers(init.headers);
        for (const name of c.expected.fetchHeadersMustExclude ?? []) expect(sent.has(name)).toBe(false);
        expect(url).not.toContain(SENTINEL);
    });

    it.each(unregistered.map((c) => [c.id, c] as const))('%s', async (_id, c) => {
        for (const id of c.incoming!.ids!) {
            const out = await call(c.route, id);
            expect(out.status).toBe(c.expected.status);
            expect(out.cacheControl).toBe(c.expected.cacheControl);
        }
        expect(fetch).toHaveBeenCalledTimes(c.expected.fetchCalls ?? 0);
    });
});
