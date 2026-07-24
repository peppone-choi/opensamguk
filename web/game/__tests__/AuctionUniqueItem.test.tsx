import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import AuctionUniqueItem from '@/components/auction/AuctionUniqueItem';

const mocks = vi.hoisted(() => ({
    auctionsUnique: vi.fn(),
    auctionUniqueDetail: vi.fn(),
    auctionBid: vi.fn(),
    submitCommandAndAwaitResult: vi.fn(),
}));

vi.mock('@/lib/api', () => ({
    api: {
        auctionsUnique: mocks.auctionsUnique,
        auctionUniqueDetail: mocks.auctionUniqueDetail,
        commands: { auctionBid: mocks.auctionBid },
    },
}));

vi.mock('@/lib/commandSubmit', () => ({
    submitCommandAndAwaitResult: mocks.submitCommandAndAwaitResult,
}));

class EventSourceStub {
    onerror: (() => void) | null = null;
    addEventListener(): void {}
    close(): void {}
}

Object.defineProperty(globalThis, 'EventSource', {
    value: EventSourceStub,
    configurable: true,
});

describe('AuctionUniqueItem viewer context', () => {
    beforeEach(() => {
        mocks.auctionsUnique.mockReset();
        mocks.auctionUniqueDetail.mockReset();
        mocks.auctionBid.mockReset();
        mocks.submitCommandAndAwaitResult.mockReset();
        mocks.auctionsUnique.mockResolvedValue({
            result: true,
            obfuscatedName: '가나다42',
            list: [{
                id: 20,
                finished: false,
                title: '전설의 말',
                target: '적토마',
                isCallerHost: true,
                hostName: '가나다42',
                closeDate: '2026-06-10 09:00:00',
                remainCloseDateExtensionCnt: 2,
                availableLatestBidCloseDate: null,
                highestBid: {
                    generalName: '가나다42',
                    amount: 300,
                    isCallerHighestBidder: true,
                    date: '2026-06-05 21:00:00',
                },
            }],
        });
        mocks.auctionUniqueDetail.mockResolvedValue({
            result: true,
            obfuscatedName: '가나다42',
            remainPoint: 3210,
            auction: {
                id: 20,
                finished: false,
                title: '전설의 말',
                target: '적토마',
                isCallerHost: true,
                hostName: '가나다42',
                closeDate: '2026-06-10 09:00:00',
                remainCloseDateExtensionCnt: 2,
                availableLatestBidCloseDate: null,
            },
            bidList: [{
                generalName: '가나다42',
                amount: 300,
                isCallerHighestBidder: true,
                date: '2026-06-05 21:00:00',
            }],
        });
        mocks.auctionBid.mockResolvedValue({ status: 'AVAILABLE', requestId: 'unique-bid-1' });
        mocks.submitCommandAndAwaitResult.mockImplementation(async (submit: () => Promise<unknown>) => {
            await submit();
            return {
                status: 'applied',
                result: { status: 'RESOLVED', requestId: 'unique-bid-1', ok: true, type: 'auctionBid', result: {} },
            };
        });
    });

    it('renders the authenticated pseudonym, remaining points, and self bid state', async () => {
        render(<AuctionUniqueItem generalId={42} onToast={vi.fn()} />);

        await waitFor(() => expect(screen.getByText(/잔여: 3,210포인트/)).toBeInTheDocument());
        expect(screen.getByText(/잔여: 3,210포인트/)).toBeInTheDocument();
        expect(screen.getAllByText('전설의 말').length).toBeGreaterThan(0);
        expect(screen.getByRole('spinbutton')).toHaveAttribute('max', '3210');
        expect(screen.getAllByText('가나다42').length).toBeGreaterThan(1);
    });

    it('submits a bid through the applied command result path', async () => {
        window.confirm = vi.fn(() => true);
        const onToast = vi.fn();
        render(<AuctionUniqueItem generalId={42} onToast={onToast} />);

        await waitFor(() => expect(screen.getByText(/잔여: 3,210포인트/)).toBeInTheDocument());
        fireEvent.click(screen.getByRole('button', { name: '입찰' }));

        await waitFor(() => expect(mocks.auctionBid).toHaveBeenCalledWith({ auctionId: 20, amount: 303 }, 42));
        await waitFor(() => expect(onToast).toHaveBeenCalledWith('입찰이 완료되었습니다.'));
    });
});
