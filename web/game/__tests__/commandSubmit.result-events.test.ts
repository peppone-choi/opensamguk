import { beforeEach, describe, expect, it, vi } from 'vitest';

import { submitCommandAndAwaitResult } from '@/lib/commandSubmit';

const mocks = vi.hoisted(() => ({
    commandResult: vi.fn(),
    pollCommandResultResponse: vi.fn(),
    waitForCommandSettled: vi.fn(),
}));

vi.mock('@/lib/api', async () => {
    const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
    return {
        ...actual,
        api: { commandResult: mocks.commandResult },
        pollCommandResultResponse: mocks.pollCommandResultResponse,
    };
});

vi.mock('@/lib/commandResultEvents', async () => {
    const actual = await vi.importActual<typeof import('@/lib/commandResultEvents')>('@/lib/commandResultEvents');
    return { ...actual, waitForCommandSettled: mocks.waitForCommandSettled };
});

const queued = () => ({ status: 'AVAILABLE' as const, requestId: 'req-1' });
const resolved = (ok: boolean, reason?: string) => ({
    status: 'RESOLVED' as const,
    requestId: 'req-1',
    type: 'executionApplied',
    ok,
    reason,
    result: { type: 'executionApplied', ok, commandKind: 'IMMEDIATE', reason },
});

/** 폴링이 끝내 결론을 내지 못하는 케이스를 표현한다 — 신호 경로만 남는 상황. */
const neverResolvingPoll = () => new Promise(() => {});

describe('submitCommandAndAwaitResult — push 신호 vs 폴링', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it('신호가 먼저 오면 폴링 간격을 기다리지 않고 정본을 한 번 읽는다', async () => {
        mocks.pollCommandResultResponse.mockImplementation(neverResolvingPoll);
        mocks.waitForCommandSettled.mockResolvedValue(true);
        mocks.commandResult.mockResolvedValue(resolved(true));

        const outcome = await submitCommandAndAwaitResult(async () => queued());

        expect(outcome.status).toBe('applied');
        expect(mocks.commandResult).toHaveBeenCalledWith('req-1');
    });

    /**
     * 이 티켓의 핵심 계약: 신호는 "결과가 준비됐다"만 뜻한다. 신호가 왔다고 성공으로 접으면
     * 거절된 명령이 성공 토스트로 보인다(OPENSAM-13/135의 202=성공 위조와 같은 사고).
     */
    it('신호가 와도 판정은 정본이 한다 — 정본이 거절이면 거절이다', async () => {
        mocks.pollCommandResultResponse.mockImplementation(neverResolvingPoll);
        mocks.waitForCommandSettled.mockResolvedValue(true);
        mocks.commandResult.mockResolvedValue(resolved(false, '금이 부족합니다.'));

        const outcome = await submitCommandAndAwaitResult(async () => queued());

        expect(outcome).toMatchObject({ status: 'rejected', reason: '금이 부족합니다.' });
    });

    it('신호가 없어도 폴링이 그대로 결론을 낸다', async () => {
        mocks.pollCommandResultResponse.mockResolvedValue(resolved(false, '금이 부족합니다.'));
        mocks.waitForCommandSettled.mockResolvedValue(false);

        const outcome = await submitCommandAndAwaitResult(async () => queued());

        expect(outcome).toMatchObject({ status: 'rejected', reason: '금이 부족합니다.' });
        // 신호가 없으면 즉시 읽기도 하지 않는다 — 폴링이 이미 자기 읽기를 한다.
        expect(mocks.commandResult).not.toHaveBeenCalled();
    });

    /** 신호가 정본보다 빠를 수 있다. 그때 PENDING을 결론으로 삼으면 성공을 '처리 지연'으로 위조한다. */
    it('신호 직후 정본이 아직 PENDING이면 폴링 결과를 기다린다', async () => {
        mocks.waitForCommandSettled.mockResolvedValue(true);
        mocks.commandResult.mockResolvedValue({ status: 'PENDING', requestId: 'req-1' });
        mocks.pollCommandResultResponse.mockResolvedValue(resolved(true));

        const outcome = await submitCommandAndAwaitResult(async () => queued());

        expect(outcome.status).toBe('applied');
    });

    /** 결론이 난 뒤에도 폴링이 계속 돌면 지연만 줄고 요청 수는 그대로다. */
    it('신호로 결론이 나면 남은 폴링을 끊는다', async () => {
        let aborted: boolean | undefined;
        mocks.pollCommandResultResponse.mockImplementation((_id: string, signal?: AbortSignal) => {
            signal?.addEventListener('abort', () => {
                aborted = true;
            });
            return new Promise(() => {});
        });
        mocks.waitForCommandSettled.mockResolvedValue(true);
        mocks.commandResult.mockResolvedValue(resolved(true));

        await submitCommandAndAwaitResult(async () => queued());

        expect(aborted).toBe(true);
    });

    /** 정본이 PENDING이면 폴링이 유일한 결론 경로다 — 여기서 끊으면 결과를 잃는다. */
    it('정본이 아직 PENDING이면 폴링을 끊지 않는다', async () => {
        let aborted = false;
        mocks.pollCommandResultResponse.mockImplementation((_id: string, signal?: AbortSignal) => {
            signal?.addEventListener('abort', () => {
                aborted = true;
            });
            return Promise.resolve(resolved(true));
        });
        mocks.waitForCommandSettled.mockResolvedValue(true);
        mocks.commandResult.mockResolvedValue({ status: 'PENDING', requestId: 'req-1' });

        const outcome = await submitCommandAndAwaitResult(async () => queued());

        expect(outcome.status).toBe('applied');
        expect(aborted).toBe(false);
    });
});
