import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { submitCommandAndAwaitResult } from '@/lib/commandSubmit';
import { __resetCommandSettledListeners, deliverCommandSettled } from '@/lib/commandResultEvents';

const mocks = vi.hoisted(() => ({
    commandResult: vi.fn(),
    pollCommandResultResponse: vi.fn(),
}));

vi.mock('@/lib/api', async () => {
    const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
    return {
        ...actual,
        api: { commandResult: mocks.commandResult },
        pollCommandResultResponse: mocks.pollCommandResultResponse,
    };
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

/**
 * 신호는 구독이 붙은 **뒤에** 와야 한다(pub/sub은 replay가 없다). 제출 호출이 await 한 단계를
 * 지나야 구독이 생기므로, 대기열을 몇 번 비운 뒤 발행한다.
 */
async function settleAfterSubscribe() {
    for (let i = 0; i < 5; i += 1) await Promise.resolve();
    deliverCommandSettled();
}

describe('submitCommandAndAwaitResult — push 신호 vs 폴링', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    afterEach(() => {
        __resetCommandSettledListeners();
    });

    it('신호가 먼저 오면 폴링 간격을 기다리지 않고 정본을 한 번 읽는다', async () => {
        mocks.pollCommandResultResponse.mockImplementation(neverResolvingPoll);
        mocks.commandResult.mockResolvedValue(resolved(true));

        const pending = submitCommandAndAwaitResult(async () => queued());
        await settleAfterSubscribe();
        const outcome = await pending;

        expect(outcome.status).toBe('applied');
        expect(mocks.commandResult).toHaveBeenCalledWith('req-1');
    });

    /**
     * 이 티켓의 핵심 계약: 신호는 "결과가 준비됐다"만 뜻한다. 신호가 왔다고 성공으로 접으면
     * 거절된 명령이 성공 토스트로 보인다(OPENSAM-13/135의 202=성공 위조와 같은 사고).
     */
    it('신호가 와도 판정은 정본이 한다 — 정본이 거절이면 거절이다', async () => {
        mocks.pollCommandResultResponse.mockImplementation(neverResolvingPoll);
        mocks.commandResult.mockResolvedValue(resolved(false, '금이 부족합니다.'));

        const pending = submitCommandAndAwaitResult(async () => queued());
        await settleAfterSubscribe();

        await expect(pending).resolves.toMatchObject({ status: 'rejected', reason: '금이 부족합니다.' });
    });

    it('신호가 없어도 폴링이 그대로 결론을 낸다', async () => {
        mocks.pollCommandResultResponse.mockResolvedValue(resolved(false, '금이 부족합니다.'));

        const outcome = await submitCommandAndAwaitResult(async () => queued());

        expect(outcome).toMatchObject({ status: 'rejected', reason: '금이 부족합니다.' });
        // 신호가 없으면 즉시 읽기도 하지 않는다 — 폴링이 이미 자기 읽기를 한다.
        expect(mocks.commandResult).not.toHaveBeenCalled();
    });

    /**
     * 신호에는 식별자가 없으므로 남의 명령이 만든 신호에도 깨어난다. 그때 정본이 아직 PENDING이면
     * 그것을 결론으로 삼아선 안 된다 — 성공을 '처리 지연'으로 위조하게 된다.
     */
    it('남의 신호에 깨어나 정본이 PENDING이면 헛읽기로 끝나고 폴링을 기다린다', async () => {
        mocks.commandResult.mockResolvedValue({ status: 'PENDING', requestId: 'req-1' });
        let releasePoll: (value: unknown) => void = () => {};
        mocks.pollCommandResultResponse.mockImplementation(() => new Promise(resolve => {
            releasePoll = resolve;
        }));

        const pending = submitCommandAndAwaitResult(async () => queued());
        await settleAfterSubscribe();
        await settleAfterSubscribe();
        releasePoll(resolved(true));

        await expect(pending).resolves.toMatchObject({ status: 'applied' });
        expect(mocks.commandResult).toHaveBeenCalledTimes(2);
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
        mocks.commandResult.mockResolvedValue(resolved(true));

        const pending = submitCommandAndAwaitResult(async () => queued());
        await settleAfterSubscribe();
        await pending;

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
        mocks.commandResult.mockResolvedValue({ status: 'PENDING', requestId: 'req-1' });

        const pending = submitCommandAndAwaitResult(async () => queued());
        await settleAfterSubscribe();

        await expect(pending).resolves.toMatchObject({ status: 'applied' });
        expect(aborted).toBe(false);
    });

    /** 해제가 안 되면 제출할 때마다 리스너가 쌓여, 신호 한 번에 죽은 명령의 요청이 그만큼 나간다. */
    it('기다림이 끝나면 신호 구독을 해제한다', async () => {
        mocks.pollCommandResultResponse.mockResolvedValue(resolved(true));

        await submitCommandAndAwaitResult(async () => queued());
        mocks.commandResult.mockClear();
        deliverCommandSettled();
        await Promise.resolve();

        expect(mocks.commandResult).not.toHaveBeenCalled();
    });
});
