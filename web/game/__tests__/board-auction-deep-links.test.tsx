import { render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import AuctionPage from '@/app/game/auction/page';
import BoardPage from '@/app/game/board/page';

const routeState = vi.hoisted(() => ({ query: '' }));
const apiMocks = vi.hoisted(() => ({
    board: vi.fn(),
    frontInfo: vi.fn(),
}));

vi.mock('next/navigation', () => ({
    useSearchParams: () => new URLSearchParams(routeState.query),
}));

vi.mock('@/components/Shell', () => ({
    default: ({ children }: { children: ReactNode }) => <>{children}</>,
}));

vi.mock('@/components/GameCard', () => ({
    default: ({ children }: { children: ReactNode }) => <section>{children}</section>,
}));

vi.mock('@/components/StatusBadge', () => ({
    default: ({ children }: { children: ReactNode }) => <span>{children}</span>,
}));

vi.mock('@/components/CommandModal', () => ({
    default: () => null,
}));

vi.mock('@/components/auction/AuctionResource', () => ({
    default: ({ generalId }: { generalId: number | null }) => (
        <div data-testid="resource-auction">{generalId}</div>
    ),
}));

vi.mock('@/components/auction/AuctionUniqueItem', () => ({
    default: ({ generalId }: { generalId: number | null }) => (
        <div data-testid="unique-auction">{generalId}</div>
    ),
}));

vi.mock('@/hooks/useFrontInfo', () => ({
    useFrontInfo: () => ({
        frontInfo: { general: { generalId: 42 } },
    }),
}));

vi.mock('@/lib/api', () => ({
    api: apiMocks,
}));

class EventSourceStub {
    onerror: (() => void) | null = null;

    addEventListener(): void {}

    close(): void {}
}

describe('Board and auction deep links', () => {
    beforeEach(() => {
        routeState.query = '';
        apiMocks.board.mockReset().mockResolvedValue({ result: true, articles: [] });
        apiMocks.frontInfo.mockReset().mockResolvedValue({ general: { generalId: 0 } });
        vi.stubGlobal('EventSource', EventSourceStub);
    });

    it('loads the secret board on the first render for ?secret=1', async () => {
        routeState.query = 'secret=1';

        render(<BoardPage />);

        await screen.findByRole('heading', { name: '기밀실' });
        await waitFor(() => expect(apiMocks.board).toHaveBeenCalledWith(true));
        expect(screen.getByRole('button', { name: '기밀실' })).toHaveAttribute('aria-pressed', 'true');
    });

    it('keeps the public board as the queryless default', async () => {
        render(<BoardPage />);

        await screen.findByRole('heading', { name: '회의실' });
        await waitFor(() => expect(apiMocks.board).toHaveBeenCalledWith(false));
        expect(screen.getByRole('button', { name: '회의실' })).toHaveAttribute('aria-pressed', 'true');
    });

    it('renders the unique auction on the first render for ?type=unique', async () => {
        routeState.query = 'type=unique';

        render(<AuctionPage />);

        expect(await screen.findByTestId('unique-auction')).toHaveTextContent('42');
        expect(screen.getByRole('heading', { name: '유니크 경매장' })).toBeInTheDocument();
        expect(screen.getByRole('button', { name: '유니크' })).toHaveAttribute('aria-pressed', 'true');
    });

    it('keeps the resource auction as the queryless default', async () => {
        render(<AuctionPage />);

        expect(await screen.findByTestId('resource-auction')).toHaveTextContent('42');
        expect(screen.getByRole('heading', { name: '경매장' })).toBeInTheDocument();
        expect(screen.getByRole('button', { name: '금/쌀' })).toHaveAttribute('aria-pressed', 'true');
    });
});
