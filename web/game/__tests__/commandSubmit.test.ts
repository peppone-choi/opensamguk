import { beforeEach, describe, expect, it, vi } from 'vitest';
import { submitCommandAndAwaitResult } from '@/lib/commandSubmit';

const mocks = vi.hoisted(() => ({
    pollCommandResultResponse: vi.fn(),
}));

vi.mock('@/lib/api', async importOriginal => {
    const actual = await importOriginal<typeof import('@/lib/api')>();
    return {
        ...actual,
        pollCommandResultResponse: mocks.pollCommandResultResponse,
    };
});

describe('submitCommandAndAwaitResult', () => {
    beforeEach(() => {
        mocks.pollCommandResultResponse.mockReset();
    });

    it('returns applied after a resolved ok command result', async () => {
        mocks.pollCommandResultResponse.mockResolvedValueOnce({
            status: 'RESOLVED',
            requestId: 'req-1',
            ok: true,
            type: 'sendMessage',
            result: { messageId: 10 },
        });

        const result = await submitCommandAndAwaitResult(async () => ({ status: 'AVAILABLE', requestId: 'req-1' }));

        expect(mocks.pollCommandResultResponse).toHaveBeenCalledWith('req-1');
        expect(result).toMatchObject({
            status: 'applied',
            result: { ok: true, requestId: 'req-1', result: { messageId: 10 } },
        });
    });

    it('returns the resolved engine reason verbatim on rejection', async () => {
        mocks.pollCommandResultResponse.mockResolvedValueOnce({
            status: 'RESOLVED',
            requestId: 'req-2',
            ok: false,
            type: 'auctionBid',
            reason: '유산 포인트가 부족합니다.',
            result: {},
        });

        const result = await submitCommandAndAwaitResult(async () => ({ status: 'AVAILABLE', requestId: 'req-2' }));

        expect(result).toMatchObject({
            status: 'rejected',
            reason: '유산 포인트가 부족합니다.',
        });
    });

    it('reports a reservation admission as reserved instead of applied', async () => {
        mocks.pollCommandResultResponse.mockResolvedValueOnce({
            status: 'RESOLVED',
            requestId: 'req-admission',
            ok: true,
            type: 'reservationAccepted',
            result: { commandKind: 'RESERVED_TURN' },
        });

        const result = await submitCommandAndAwaitResult(async () => ({ status: 'AVAILABLE', requestId: 'req-admission' }));

        expect(result).toEqual({
            status: 'reserved',
            reason: '명령이 예약되었습니다.',
            result: {
                status: 'RESOLVED',
                requestId: 'req-admission',
                ok: true,
                type: 'reservationAccepted',
                result: { commandKind: 'RESERVED_TURN' },
            },
        });
    });

    it('reports a queue mutation as reserved instead of applied', async () => {
        mocks.pollCommandResultResponse.mockResolvedValueOnce({
            status: 'RESOLVED',
            requestId: 'req-queue',
            ok: true,
            type: 'queueMutation',
            result: { commandKind: 'QUEUE_MUTATION' },
        });

        const result = await submitCommandAndAwaitResult(async () => ({ status: 'AVAILABLE', requestId: 'req-queue' }));

        expect(result).toMatchObject({ status: 'reserved', reason: '명령이 예약되었습니다.' });
    });

    it('keeps the reservation admission phase after the polling window', async () => {
        mocks.pollCommandResultResponse.mockResolvedValueOnce({
            status: 'PENDING',
            requestId: 'req-pending-admission',
            phase: 'reservationAccepted',
        });

        const result = await submitCommandAndAwaitResult(async () => ({ status: 'AVAILABLE', requestId: 'req-pending-admission' }));

        expect(result).toMatchObject({ status: 'reserved', reason: '명령이 예약되었습니다.', phase: 'reservationAccepted' });
    });

    it('returns pending when the command result times out', async () => {
        mocks.pollCommandResultResponse.mockResolvedValueOnce(null);

        const result = await submitCommandAndAwaitResult(async () => ({ status: 'AVAILABLE', requestId: 'req-3' }));

        expect(result).toEqual({ status: 'pending', reason: '처리 지연' });
    });
});
