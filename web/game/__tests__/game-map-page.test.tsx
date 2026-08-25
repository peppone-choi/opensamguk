import { render, screen } from '@testing-library/react';
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
    default: () => <output data-testid="shared-map-viewer">shared map viewer</output>,
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

    it.each(['che', 'han'])('renders %s through the shared map viewer', async (mapName) => {
        mocks.gameConst.mockResolvedValue({ mapName });
        render(<GameMapPage />);
        expect(await screen.findByTestId('shared-map-viewer')).toBeInTheDocument();
        expect(screen.getByText('도시를 클릭하면 해당 도시 정보를 볼 수 있습니다.')).toBeInTheDocument();
    });
});
