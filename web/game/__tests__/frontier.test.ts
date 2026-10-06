// 주변 세계 읽기(K8-09) — 서버 시험의 고정 응답 3종(app/game-api/src/test/resources/frontier, #1407)을 그대로 읽어 같은 뜻으로 읽는지 본다.
import { readFileSync, readdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({ fetchGame: vi.fn<(path: string, init?: RequestInit) => Promise<Response>>() }));
vi.mock('@/lib/api', () => ({ fetchGame: mocks.fetchGame }));

import { parseFrontier, readFrontier } from '@/lib/api/frontier';

const DIR = resolve(__dirname, '../../../app/game-api/src/test/resources/frontier');
type Rec = Record<string, unknown>;
const server = (name: string) => JSON.parse(readFileSync(resolve(DIR, name), 'utf8')) as Rec;
const READY_EMPTY = { status: 'READY', reason: null, now: { year: 201, month: 4, phase: 3 }, actors: [] };

async function read(body: unknown, status = 200) {
    mocks.fetchGame.mockImplementationOnce(() => Promise.resolve(new Response(JSON.stringify(body), { status })));
    return readFrontier(7);
}

beforeEach(() => mocks.fetchGame.mockReset());

describe('readFrontier — 서버 고정 응답', () => {
    it('고정 응답이 3개 있다(서버 시험이 바뀌면 여기도 본다)', () => {
        expect(readdirSync(DIR).filter((n) => n.endsWith('.json')).sort()).toEqual(['boundary.json', 'not-seeded.json', 'unavailable.json']);
    });
    it('원천 없음 · 재야 · 월드 불명을 받는다, 주소 · no-store', async () => {
        expect(await read(server('not-seeded.json'))).toEqual({ ok: true, frontier: { status: 'NOT_SEEDED', reason: 'CONTACTS_NOT_SEEDED', actors: null } });
        expect(await read(server('boundary.json'))).toEqual({ ok: true, frontier: { status: 'UNAVAILABLE', reason: 'NO_NATION', actors: null } });
        expect(await read(server('unavailable.json'))).toEqual({ ok: true, frontier: { status: 'UNAVAILABLE', reason: 'WORLD_UNAVAILABLE', actors: null } });
        expect(mocks.fetchGame).toHaveBeenCalledWith('/api/frontier?generalId=7', expect.objectContaining({ cache: 'no-store' }));
    });
    it('READY [] 는 확인된 무접촉', async () => {
        expect(await read(READY_EMPTY)).toEqual({ ok: true, frontier: { status: 'READY', reason: null, actors: [] } });
    });
    it('401 · 403 · 404 · 500 은 실패로 돌린다(404 를 서버 대기로 그리는 것은 훅)', async () => {
        for (const status of [401, 403, 404, 500]) expect(await read({ error: { code: 'X', message: 'x' } }, status)).toEqual({ ok: false, httpStatus: status });
    });
});

describe('parseFrontier — 계약 밖 모양은 실패(fail closed)', () => {
    it('키가 빠지거나 모르는 상태 · 이유면 실패', () => {
        for (const key of ['reason', 'now', 'actors']) {
            const body = server('not-seeded.json');
            delete body[key];
            expect(parseFrontier(body), key).toBeNull();
        }
        expect(parseFrontier({ ...server('not-seeded.json'), status: 'PARTIAL' })).toBeNull();
        expect(parseFrontier({ ...server('unavailable.json'), reason: 'NEW_REASON' })).toBeNull();
    });
    it('원천 없음 · 셈 못 함은 이유가 있고 actors 는 null — READY 는 이유 없이 배열', () => {
        expect(parseFrontier({ ...server('not-seeded.json'), actors: [] })).toBeNull();
        expect(parseFrontier({ ...server('not-seeded.json'), reason: 'NO_NATION' })).toBeNull();
        expect(parseFrontier({ ...server('unavailable.json'), reason: null })).toBeNull();
        expect(parseFrontier({ ...READY_EMPTY, reason: 'NO_NATION' })).toBeNull();
        expect(parseFrontier({ ...READY_EMPTY, actors: null })).toBeNull();
    });
    it('연월순은 월드 불명(WORLD_UNAVAILABLE)일 때만 null — READY · 원천 없음 · 재야에 null 이 오거나 월드 불명에 값이 오면 실패', () => {
        expect(parseFrontier({ ...READY_EMPTY, now: null })).toBeNull();
        expect(parseFrontier({ ...server('not-seeded.json'), now: null })).toBeNull();
        expect(parseFrontier({ ...server('boundary.json'), now: null })).toBeNull();
        expect(parseFrontier({ ...server('unavailable.json'), now: { year: 201, month: 4, phase: 3 } })).toBeNull();
        expect(parseFrontier(server('unavailable.json'))).not.toBeNull();
    });
    it('행 화면을 짓기 전에는 행이 오면 실패 — 사료 후보를 접촉으로 그리지 않는다', () => {
        expect(parseFrontier({ ...READY_EMPTY, actors: [{ actorId: 'external:wuhuan', name: '오환', relation: 'HOSTILE', borderCountyIds: [1] }] })).toBeNull();
    });
});
