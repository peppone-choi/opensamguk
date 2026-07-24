import { beforeEach, describe, expect, it, vi } from 'vitest';
import { submitCommandAndAwaitResult } from '@/lib/commandSubmit';

const mocks = vi.hoisted(() => ({
    pollCommandResult: vi.fn(),
}));

vi.mock('@/lib/api', async importOriginal => {
    const actual = await importOriginal<typeof import('@/lib/api')>();
    return {
        ...actual,
        pollCommandResult: mocks.pollCommandResult,
    };
});

describe('submitCommandAndAwaitResult', () => {
    beforeEach(() => {
        mocks.pollCommandResult.mockReset();
    });

    it('returns applied after a resolved ok command result', async () => {
        mocks.pollCommandResult.mockResolvedValueOnce({
            status: 'RESOLVED',
            requestId: 'req-1',
            ok: true,
            type: 'sendMessage',
            result: { messageId: 10 },
        });

        const result = await submitCommandAndAwaitResult(async () => ({ status: 'AVAILABLE', requestId: 'req-1' }));

        expect(mocks.pollCommandResult).toHaveBeenCalledWith('req-1');
        expect(result).toMatchObject({
            status: 'applied',
            result: { ok: true, requestId: 'req-1', result: { messageId: 10 } },
        });
    });

    it('returns the resolved engine reason verbatim on rejection', async () => {
        mocks.pollCommandResult.mockResolvedValueOnce({
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

    it('returns pending when the command result times out', async () => {
        mocks.pollCommandResult.mockResolvedValueOnce(null);

        const result = await submitCommandAndAwaitResult(async () => ({ status: 'AVAILABLE', requestId: 'req-3' }));

        expect(result).toEqual({ status: 'pending', reason: '처리 지연' });
    });
});
