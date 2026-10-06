// @vitest-environment node
// 공개 서버 목록(C8 #1357 `GET /servers`, 계약판 「K5 → C8 소비 답」 · D112) 소비 — 원천을 모르면 [] 도 PUBLIC 도 지어내지 않는다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { publicServerListOf, readPublicServers } from '@/lib/serverPublication';
import { GET as serversRoute } from '@/app/api/servers/route';

const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
const PUBLIC = [
    { id: 'uni', name: '통일 서버', generation: 3, gameUrl: '/game/uni' },
    { id: 'zero', name: '영기', generation: 0, gameUrl: '/game/zero' },
    { id: 'new', name: '새 서버', generation: null, gameUrl: '/game/new' },
];

beforeEach(() => {
    // env 서버 표(구성원 표)에 pep 이 있어도 공개 목록은 그것으로 대신하지 않는다
    vi.stubEnv('SERVER_REGISTRY_JSON', '[{"id":"pep","name":"pep","generation":1}]');
});
afterEach(() => {
    vi.unstubAllGlobals();
    vi.unstubAllEnvs();
});

describe('공개 목록 모양', () => {
    it('그대로 받는다 — generation 0 은 0, null 은 null', () => {
        expect(publicServerListOf(PUBLIC)).toEqual({ kind: 'known', servers: PUBLIC });
        expect(publicServerListOf([])).toEqual({ kind: 'known', servers: [] });
    });
    it.each<[string, unknown]>([
        ['배열 아님', { servers: PUBLIC }],
        ['id 형식 틀림', [{ ...PUBLIC[0], id: 'UNI-1' }]],
        ['예약 id', [{ ...PUBLIC[0], id: 'admin' }]],
        ['generation 소수', [{ ...PUBLIC[0], generation: 1.5 }]],
        ['generation 빠짐', [{ id: 'uni', name: 'u', gameUrl: '/game/uni' }]],
        ['gameUrl 빠짐', [{ id: 'uni', name: 'u', generation: 1 }]],
        ['같은 id 둘', [PUBLIC[0], PUBLIC[0]]],
    ])('한 항목이라도 틀리면 UNKNOWN — %s', (_, body) => {
        expect(publicServerListOf(body)).toEqual({ kind: 'unknown' });
    });
});

describe('원천 읽기', () => {
    it('gateway-api /servers 를 매번 새로(no-store) 묻는다', async () => {
        const fetcher = vi.fn(async () => json(200, PUBLIC));
        vi.stubGlobal('fetch', fetcher);
        expect(await readPublicServers()).toEqual({ kind: 'known', servers: PUBLIC });
        const [url, init] = fetcher.mock.calls[0] as unknown as [string, RequestInit];
        expect(url).toMatch(/\/servers$/);
        expect(init.cache).toBe('no-store');
    });
    it.each<[string, () => Promise<Response>]>([
        ['503 SERVER_LIST_UNAVAILABLE', async () => json(503, { error: { code: 'SERVER_LIST_UNAVAILABLE' } })],
        ['500', async () => json(500, {})],
        ['연결 실패', async () => { throw new TypeError('fetch failed'); }],
        ['JSON 아님', async () => new Response('<html>', { status: 200 })],
    ])('%s → UNKNOWN(env 표로 대신하지 않는다)', async (_, respond) => {
        vi.stubGlobal('fetch', vi.fn(respond));
        expect(await readPublicServers()).toEqual({ kind: 'unknown' });
    });
});

describe('/api/servers', () => {
    it('알면 200 {servers} · no-store', async () => {
        vi.stubGlobal('fetch', vi.fn(async () => json(200, PUBLIC)));
        const response = await serversRoute();
        expect(response.status).toBe(200);
        expect(response.headers.get('cache-control')).toBe('no-store');
        expect(await response.json()).toEqual({ servers: PUBLIC });
    });
    it('확인된 0행은 200 {servers: []} — 「열린 서버 없음」', async () => {
        vi.stubGlobal('fetch', vi.fn(async () => json(200, [])));
        const response = await serversRoute();
        expect(response.status).toBe(200);
        expect(await response.json()).toEqual({ servers: [] });
    });
    it('원천을 모르면 503 SERVER_LIST_UNAVAILABLE · no-store — 빈 목록도 env 표도 아니다', async () => {
        vi.stubGlobal('fetch', vi.fn(async () => json(503, { error: { code: 'SERVER_LIST_UNAVAILABLE' } })));
        const response = await serversRoute();
        expect(response.status).toBe(503);
        expect(response.headers.get('cache-control')).toBe('no-store');
        expect(await response.json()).toEqual({ error: { code: 'SERVER_LIST_UNAVAILABLE' } });
    });
});
