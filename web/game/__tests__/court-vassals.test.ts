// 봉신 저장 조건 읽기(C5 #1373) — 서버 시험의 고정 응답 11개(app/game-api/src/test/resources/court/vassal)를 그대로 읽어
// 클라이언트 검증 · 보기 모델이 서버와 같은 뜻으로 읽는지 본다(표류 검사). 모르는 모양은 성공으로 치지 않는다.
import { readFileSync, readdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({ fetchGame: vi.fn<(path: string, init?: RequestInit) => Promise<Response>>() }));
vi.mock('@/lib/api', () => ({ fetchGame: mocks.fetchGame }));

import { readCourtVassals } from '@/lib/api/court-vassals';
import { monthlyTributeChip, receiptStatus, vassalsView } from '@/lib/court-vassals-view';

const DIR = resolve(__dirname, '../../../app/game-api/src/test/resources/court/vassal');
const fixture = (name: string) => JSON.parse(readFileSync(resolve(DIR, name), 'utf8')) as Record<string, unknown>;
const respond = (body: unknown, status = 200) => Promise.resolve(new Response(JSON.stringify(body), { status }));

async function read(body: unknown, status = 200) {
    mocks.fetchGame.mockImplementationOnce(() => respond(body, status));
    return readCourtVassals(7);
}

beforeEach(() => mocks.fetchGame.mockReset());

describe('readCourtVassals — 서버 고정 응답 11개를 모두 받는다', () => {
    const names = readdirSync(DIR).filter((n) => n.endsWith('.json')).sort();
    it('고정 응답이 11개 있다(서버 시험이 바뀌면 여기도 본다)', () => {
        expect(names).toHaveLength(11);
    });
    it.each(names)('%s', async (name) => {
        const r = await read(fixture(name));
        expect(r.ok).toBe(true);
    });
    it('주소 · no-store', async () => {
        await read(fixture('valid-stored-empty.json'));
        expect(mocks.fetchGame).toHaveBeenCalledWith('/api/court/vassals?generalId=7', expect.objectContaining({ cache: 'no-store' }));
    });
});

describe('readCourtVassals — 계약 밖 모양 · 상태는 실패(fail closed)', () => {
    it('401 · 403 · 500 은 실패, 본문을 읽지 않는다', async () => {
        for (const status of [401, 403, 500]) expect(await read({ error: { code: 'X', message: 'x' } }, status)).toEqual({ ok: false, httpStatus: status });
    });
    it('빠진 키를 null 로 채우지 않는다 — vassalName · isHuman · endedTurn 키가 없으면 실패', async () => {
        for (const key of ['vassalName', 'isHuman', 'endedTurn']) {
            const body = fixture('stored-terms-partial-paid.json');
            delete (body.contracts as Record<string, unknown>[])[0][key];
            expect((await read(body)).ok).toBe(false);
        }
    });
    it('PARTIAL 은 contractsStatus READY 와만, UNAVAILABLE 은 계약 없이만', async () => {
        expect((await read({ ...fixture('not-seeded.json'), status: 'PARTIAL' })).ok).toBe(false);
        const withContract = { ...fixture('unavailable.json'), contracts: fixture('stored-terms-partial-paid.json').contracts };
        expect((await read(withContract)).ok).toBe(false);
    });
    it('사람 여부: READY 인데 null, UNAVAILABLE 인데 값이면 실패 · 모르는 자치 · 외교권 값도 실패', async () => {
        const cases: Array<(c: Record<string, unknown>) => void> = [
            (c) => { c.isHuman = null; },
            (c) => { c.isHumanStatus = 'UNAVAILABLE'; },
            (c) => { c.autonomy = ['MINT_COINS']; },
            (c) => { c.diplomacyRight = 'EMPIRE'; },
        ];
        for (const mutate of cases) {
            const body = fixture('stored-terms-partial-paid.json');
            mutate((body.contracts as Record<string, unknown>[])[0]);
            expect((await read(body)).ok).toBe(false);
        }
    });
});

describe('보기 모델 — 서버 고정 응답의 뜻 그대로', () => {
    const view = async (name: string) => {
        const r = await read(fixture(name));
        if (!r.ok) throw new Error('read failed');
        return { vassals: r.vassals, view: vassalsView(r.vassals) };
    };
    it('계약 0개 확정은 READY 빈 목록뿐 — NOT_SEEDED · UNAVAILABLE 은 「없음」이 아니다', async () => {
        expect((await view('valid-stored-empty.json')).view.kind).toBe('empty');
        expect((await view('not-seeded.json')).view.kind).toBe('not-seeded');
        expect((await view('unavailable.json')).view.kind).toBe('unavailable');
    });
    it('끝난 기록은 따로 묶는다', async () => {
        const v = (await view('ended-record-calendar-unavailable.json')).view;
        expect(v).toMatchObject({ kind: 'contracts', current: [], ended: [expect.objectContaining({ endedTurn: 860 })] });
    });
    it('이번 달 칩은 서버 상태 그대로 — 완납 · 미납 · 청구 없음 · 아직', async () => {
        const chip = async (name: string) => monthlyTributeChip((await view(name)).vassals.contracts[0].monthlyTribute.status)?.label;
        expect(await chip('stored-terms-partial-paid.json')).toBe('이번 달 완납');
        expect(await chip('unpaid-one-resource.json')).toBe('이번 달 미납');
        expect(await chip('zero-due-not-contract-no-obligation.json')).toBe('이번 달 청구 없음');
        expect(await chip('no-monthly-receipt.json')).toBe('이번 달 아직');
        expect(monthlyTributeChip('UNAVAILABLE')).toBeNull();
    });
    it('지난 달 상태(receiptStatus)는 서버의 이번 달 판정과 같은 규칙이다 — 영수증이 있는 고정 응답마다 대조', async () => {
        for (const name of ['stored-terms-partial-paid.json', 'unpaid-one-resource.json', 'zero-due-not-contract-no-obligation.json']) {
            const c = (await view(name)).vassals.contracts[0];
            expect(c.monthlyTribute.receipt).not.toBeNull();
            expect(receiptStatus(c.monthlyTribute.receipt!)).toBe(c.monthlyTribute.status);
        }
    });
});
