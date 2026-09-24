import { render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
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

describe('Board deep links', () => {
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
        expect(screen.getByRole('tab', { name: /^기밀실/ })).toHaveAttribute('aria-selected', 'true');
    });

    it('keeps the public board as the queryless default', async () => {
        render(<BoardPage />);

        await screen.findByRole('heading', { name: '회의실' });
        await waitFor(() => expect(apiMocks.board).toHaveBeenCalledWith(false));
        expect(screen.getByRole('tab', { name: /^회의실/ })).toHaveAttribute('aria-selected', 'true');
    });

});
