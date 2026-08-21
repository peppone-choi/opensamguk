import { fireEvent, render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import GameMapPage from '@/app/game/map/page';

const mocks = vi.hoisted(() => ({
    gameConst: vi.fn(),
    worldLog: vi.fn(),
}));

vi.mock('@/components/Shell', () => ({
    default: ({ children }: { children: ReactNode }) => <>{children}</>,
}));

vi.mock('@/components/GameCard', () => ({
    default: ({ children }: { children: ReactNode }) => <section>{children}</section>,
}));

vi.mock('@/components/game/MapViewer', () => ({
    default: () => <output data-testid="legacy-map">legacy map</output>,
}));

vi.mock('@/components/game/HanMapCanvas', () => ({
    default: ({ onMissing }: { onMissing?: () => void }) => (
        <button data-testid="han-map" type="button" onClick={onMissing}>han map</button>
    ),
}));

vi.mock('@/hooks/useTurnRefresh', () => ({ useTurnRefresh: vi.fn() }));

vi.mock('@/lib/api', () => ({
    api: { gameConst: mocks.gameConst, worldLog: mocks.worldLog },
}));

describe('GameMapPage map selection', () => {
    beforeEach(() => {
        mocks.gameConst.mockReset();
        mocks.worldLog.mockReset();
        mocks.worldLog.mockResolvedValue({ entries: [] });
    });

    it('renders the legacy map for an explicitly selected che world', async () => {
        mocks.gameConst.mockResolvedValue({ mapName: 'che' });

        render(<GameMapPage />);

        expect(await screen.findByTestId('legacy-map')).toBeInTheDocument();
        expect(screen.queryByTestId('han-map')).not.toBeInTheDocument();
    });

    it('keeps a han world fail-visible when its terrain cannot load', async () => {
        mocks.gameConst.mockResolvedValue({ mapName: 'han' });

        render(<GameMapPage />);

        fireEvent.click(await screen.findByTestId('han-map'));
        expect(await screen.findByText('후한 군현 지도 데이터를 불러올 수 없습니다.')).toBeInTheDocument();
        expect(screen.queryByTestId('legacy-map')).not.toBeInTheDocument();
    });
});
