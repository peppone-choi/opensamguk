// W0-1 와이어 레이어 — 명령 인테이크 결과 표면화(P0-04/P0-06 근원) 잠금 테스트.
//
// 계약(game-api CommandController/InstantActionController):
//  - 202 {status:'AVAILABLE', requestId, turnIdx} → "큐잉됨"(성공 확정 아님 — 엔진 비동기 deny 가능).
//  - 200 {status:'BLOCKED'|'UNKNOWN', reason}     → precheck deny. resolve로 표면화(예외 아님) —
//    페이지가 reason을 legacy 문자열 그대로 토스트할 수 있어야 한다.
//  - 4xx/5xx                                       → reject. 본문에 실제 사유(reason/error)가 있으면
//    그대로 메시지에 싣는다(날조 없음 — BE가 보낸 문자열만).
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { api, isIntakeDenied, isIntakeQueued } from '@/lib/api';

function mockFetchOnce(status: number, body: unknown) {
    const res = {
        ok: status >= 200 && status < 300,
        status,
        statusText: status === 200 ? 'OK' : status === 202 ? 'Accepted' : 'Bad Request',
        json: () => Promise.resolve(body),
    } as Response;
    (globalThis.fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(res);
}

beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn());
});
afterEach(() => {
    vi.unstubAllGlobals();
});

describe('인테이크 결과 표면화 (api.command / api.commands.*)', () => {
    it('200 BLOCKED는 reject가 아니라 status/reason으로 resolve된다 (성공 위장 금지)', async () => {
        mockFetchOnce(200, { status: 'BLOCKED', reason: '자금이 부족합니다.', constraintName: 'ReqGold' });
        const out = await api.command('auctionBid', { auctionId: 1, amount: 100 }, 7);
        expect(out.status).toBe('BLOCKED');
        expect(isIntakeDenied(out)).toBe(true);
        expect(isIntakeQueued(out)).toBe(false);
        if (isIntakeDenied(out)) {
            expect(out.reason).toBe('자금이 부족합니다.');
        }
    });

    it('200 UNKNOWN도 denied로 판별된다', async () => {
        mockFetchOnce(200, { status: 'UNKNOWN', reason: '명령을 확인할 수 없습니다.' });
        const out = await api.commands.placeBet({ bettingId: 3, bettingType: [1], amount: 10 }, 7);
        expect(isIntakeDenied(out)).toBe(true);
    });

    it('202 AVAILABLE은 queued로 판별된다 — 단, 성공 확정이 아니라 큐잉이다', async () => {
        mockFetchOnce(202, { status: 'AVAILABLE', requestId: 'req-1', turnIdx: 0 });
        const out = await api.commands.auctionBid({ auctionId: 1, amount: 100 }, 7);
        expect(isIntakeQueued(out)).toBe(true);
        expect(isIntakeDenied(out)).toBe(false);
        if (isIntakeQueued(out)) {
            expect(out.requestId).toBe('req-1');
        }
    });

    it('4xx는 reject — BE 본문의 실제 사유(error)를 그대로 싣는다', async () => {
        mockFetchOnce(409, { error: '아직 배선되지 않은 즉시 액션입니다.', code: 'ResetStat' });
        await expect(api.instantAction('ResetStat', 7, { leadership: 70 })).rejects.toThrow(
            '아직 배선되지 않은 즉시 액션입니다.',
        );
    });

    it('본문 없는 4xx는 상태줄로 reject된다 (사유 날조 없음)', async () => {
        const res = {
            ok: false,
            status: 400,
            statusText: 'Bad Request',
            json: () => Promise.reject(new Error('no body')),
        } as unknown as Response;
        (globalThis.fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(res);
        await expect(api.command('BuyHiddenBuff', {}, 7)).rejects.toThrow('400: Bad Request');
    });

    it('generalId/turnIdx가 쿼리로 항상 실린다 (P0-50 — generalId 누락 400 재발 방지)', async () => {
        mockFetchOnce(202, { status: 'AVAILABLE', requestId: 'r', turnIdx: 2 });
        await api.command('BuyHiddenBuff', { buffKey: 'warAvoidRatio' }, 42, 2);
        const fetchMock = globalThis.fetch as ReturnType<typeof vi.fn>;
        const url = fetchMock.mock.calls[0][0] as string;
        expect(url).toBe('/api/game/api/command/BuyHiddenBuff?generalId=42&turnIdx=2');
    });
});

describe('큐 조작 헬퍼 (push/repeat/bulk × general/nation)', () => {
    it('nationPush는 /api/command/nation/push에 {amount}를 싣는다', async () => {
        mockFetchOnce(202, { status: 'AVAILABLE' });
        await api.commandQueue.nationPush(7, 3);
        const fetchMock = globalThis.fetch as ReturnType<typeof vi.fn>;
        const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
        expect(url).toBe('/api/game/api/command/nation/push?generalId=7');
        expect(JSON.parse(init.body as string)).toEqual({ amount: 3 });
    });

    it('nationRepeat deny(200 BLOCKED)는 reason과 함께 resolve된다', async () => {
        mockFetchOnce(200, { status: 'BLOCKED', reason: '수뇌부가 아닙니다.' });
        const out = await api.commandQueue.nationRepeat(7, 2);
        expect(isIntakeDenied(out)).toBe(true);
        if (isIntakeDenied(out)) expect(out.reason).toBe('수뇌부가 아닙니다.');
    });

    it('bulk 202는 briefList를 동봉한 queued로 resolve된다', async () => {
        mockFetchOnce(202, { status: 'AVAILABLE', result: true, briefList: ['농지 개간', '상업 투자'], reason: '' });
        const out = await api.commandQueue.bulk(7, [
            { action: 'che_농지개간', turnList: [0] },
            { action: 'che_상업투자', turnList: [1] },
        ]);
        expect(isIntakeQueued(out)).toBe(true);
        if (isIntakeQueued(out)) expect(out.briefList).toEqual(['농지 개간', '상업 투자']);
    });
});
