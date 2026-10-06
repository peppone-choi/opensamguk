// 참모 제안 읽기(K8-06) — 서버 시험의 고정 응답 2종(app/game-api/src/test/resources/retinue/proposals, #1408)을 그대로 읽어 같은 뜻으로 읽는지 본다.
import { readFileSync, readdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({ fetchGame: vi.fn<(path: string, init?: RequestInit) => Promise<Response>>() }));
vi.mock('@/lib/api', () => ({ fetchGame: mocks.fetchGame }));

import { parseRetinueProposals, readRetinueProposals } from '@/lib/api/retinue-proposals';

const DIR = resolve(__dirname, '../../../app/game-api/src/test/resources/retinue/proposals');
type Rec = Record<string, unknown>;
const server = (name: string) => JSON.parse(readFileSync(resolve(DIR, name), 'utf8')) as Rec;
const READY_EMPTY = { status: 'READY', reason: null, now: { year: 201, month: 4, phase: 3 }, proposals: [] };

async function read(body: unknown, status = 200) {
    mocks.fetchGame.mockImplementationOnce(() => Promise.resolve(new Response(JSON.stringify(body), { status })));
    return readRetinueProposals(7);
}

beforeEach(() => mocks.fetchGame.mockReset());

describe('readRetinueProposals — 서버 고정 응답', () => {
    it('고정 응답이 2개 있다(서버 시험이 바뀌면 여기도 본다)', () => {
        expect(readdirSync(DIR).filter((n) => n.endsWith('.json')).sort()).toEqual(['not-seeded.json', 'unavailable.json']);
    });
    it('producer 없음 · 월드 불명을 받는다, 주소 · no-store', async () => {
        expect(await read(server('not-seeded.json'))).toEqual({ ok: true, proposals: { status: 'NOT_SEEDED', reason: 'PROPOSALS_NOT_SEEDED', proposals: null } });
        expect(await read(server('unavailable.json'))).toEqual({ ok: true, proposals: { status: 'UNAVAILABLE', reason: 'WORLD_UNAVAILABLE', proposals: null } });
        expect(mocks.fetchGame).toHaveBeenCalledWith('/api/retinue/proposals?generalId=7', expect.objectContaining({ cache: 'no-store' }));
    });
    it('READY [] 는 이번 순 제안 없음', async () => {
        expect(await read(READY_EMPTY)).toEqual({ ok: true, proposals: { status: 'READY', reason: null, proposals: [] } });
    });
    it('401 · 403 · 404 · 500 은 실패로 돌린다(404 를 서버 대기로 그리는 것은 훅)', async () => {
        for (const status of [401, 403, 404, 500]) expect(await read({ error: { code: 'X', message: 'x' } }, status)).toEqual({ ok: false, httpStatus: status });
    });
});

describe('parseRetinueProposals — 계약 밖 모양은 실패(fail closed)', () => {
    it('키가 빠지거나 모르는 상태 · 이유 · 연월순이면 실패', () => {
        for (const key of ['reason', 'now', 'proposals']) {
            const body = server('not-seeded.json');
            delete body[key];
            expect(parseRetinueProposals(body), key).toBeNull();
        }
        expect(parseRetinueProposals({ ...server('not-seeded.json'), status: 'PARTIAL' })).toBeNull();
        expect(parseRetinueProposals({ ...server('unavailable.json'), reason: 'NO_NATION' })).toBeNull();
        expect(parseRetinueProposals({ ...READY_EMPTY, now: { year: 201, month: 4, phase: 4 } })).toBeNull();
    });
    it('producer 없음 · 셈 못 함은 이유가 있고 proposals 는 null — READY 는 이유 없이 배열', () => {
        expect(parseRetinueProposals({ ...server('not-seeded.json'), proposals: [] })).toBeNull();
        expect(parseRetinueProposals({ ...server('not-seeded.json'), reason: 'WORLD_UNAVAILABLE' })).toBeNull();
        expect(parseRetinueProposals({ ...server('unavailable.json'), reason: 'PROPOSALS_NOT_SEEDED' })).toBeNull();
        expect(parseRetinueProposals({ ...server('unavailable.json'), reason: null })).toBeNull();
        expect(parseRetinueProposals({ ...READY_EMPTY, reason: 'WORLD_UNAVAILABLE' })).toBeNull();
        expect(parseRetinueProposals({ ...READY_EMPTY, proposals: null })).toBeNull();
    });
    it('연월순은 월드 불명(WORLD_UNAVAILABLE)일 때만 null — READY · producer 없음에 null 이 오거나 월드 불명에 값이 오면 실패', () => {
        expect(parseRetinueProposals({ ...READY_EMPTY, now: null })).toBeNull();
        expect(parseRetinueProposals({ ...server('not-seeded.json'), now: null })).toBeNull();
        expect(parseRetinueProposals({ ...server('unavailable.json'), now: { year: 201, month: 4, phase: 3 } })).toBeNull();
        expect(parseRetinueProposals(server('unavailable.json'))).not.toBeNull();
    });
    it('행 화면을 짓기 전에는 행이 오면 실패 — enum 이 정해지지 않은 제안을 짐작해 그리지 않는다', () => {
        expect(parseRetinueProposals({ ...READY_EMPTY, proposals: [{ proposalId: 'p1', retainerName: '순욱', proposalType: 'X', confidence: null }] })).toBeNull();
    });
});
