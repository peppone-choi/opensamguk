import { render, screen, waitFor, within } from '@testing-library/react';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import GameMapPage from '@/app/game/map/page';

const mocks = vi.hoisted(() => ({
    gameConst: vi.fn(),
    worldLog: vi.fn(),
    diplomacyConflict: vi.fn(),
    troops: vi.fn(),
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
    api: {
        gameConst: mocks.gameConst,
        worldLog: mocks.worldLog,
        diplomacyConflict: mocks.diplomacyConflict,
        troops: mocks.troops,
    },
}));

describe('GameMapPage map selection', () => {
    beforeEach(() => {
        mocks.gameConst.mockReset();
        mocks.worldLog.mockReset();
        mocks.worldLog.mockResolvedValue({ entries: [] });
        mocks.diplomacyConflict.mockReset();
        mocks.troops.mockReset();
        mocks.diplomacyConflict.mockResolvedValue({ result: true, nations: [], conflict: [], diplomacyList: {}, myNationID: 0 });
        mocks.troops.mockResolvedValue({ result: true, troops: [], myGeneralId: 0, permission: 0 });
    });

    it.each(['che', 'han'])('renders %s through the shared map viewer', async (mapName) => {
        mocks.gameConst.mockResolvedValue({ mapName });
        render(<GameMapPage />);
        expect(await screen.findByTestId('shared-map-viewer')).toBeInTheDocument();
        expect(screen.getByText('도시를 클릭하면 해당 도시 정보를 볼 수 있습니다.')).toBeInTheDocument();
    });

    it('fills the right rail from 중원정보 relations and the troop list without fabricating rows', async () => {
        mocks.gameConst.mockResolvedValue({ mapName: 'han' });
        mocks.diplomacyConflict.mockResolvedValue({
            result: true,
            nations: [
                { nation: 1, name: '조조', color: '#3f6fb5', type: 'che_패도', level: 3, capital: 1, gennum: 33, cities: ['허창', '완'], power: 96000 },
                { nation: 2, name: '동탁', color: '#7a4fa8', type: 'che_패도', level: 4, capital: 2, gennum: 28, cities: ['낙양'], power: 141000 },
                { nation: 3, name: '손견', color: '#c9573f', type: 'che_패도', level: 2, capital: 3, gennum: 16, cities: ['장사'], power: 44000 },
                { nation: 4, name: '유비', color: '#4f9a5a', type: 'che_왕도', level: 1, capital: 4, gennum: 19, cities: ['평원'], power: 52000 },
            ],
            conflict: [],
            diplomacyList: { 1: { 2: 0, 3: 7, 4: 2 } },
            myNationID: 1,
        });
        mocks.troops.mockResolvedValue({
            result: true,
            troops: [{ troopLeader: 7, name: '선봉대', nation: 1, leaderName: '하후돈', leaderCityName: '허창', leaderNpc: 0, turnTime: '', reservedCommandBrief: [], members: [], memberCount: 3 }],
            myGeneralId: 7,
            permission: 1,
        });
        render(<GameMapPage />);
        const rail = await screen.findByRole('complementary', { name: '지도 정보' });
        await waitFor(() => expect(within(rail).getByText('내 소속')).toBeInTheDocument());
        expect(within(rail).getByText('교전')).toBeInTheDocument();
        expect(within(rail).getByText('불가침')).toBeInTheDocument();
        expect(within(rail).getByText('통상')).toBeInTheDocument();
        expect(within(rail).getByText('4국')).toBeInTheDocument();
        const rows = within(rail).getAllByRole('row');
        expect(rows[1]).toHaveTextContent('조조');
        expect(rows[1]).toHaveTextContent('2');
        expect(rows[1]).toHaveTextContent('96,000');
        expect(within(rail).getByText('선봉대')).toBeInTheDocument();
        expect(within(rail).getByText('3명')).toBeInTheDocument();
        expect(within(rail).getByText('기록이 없습니다.')).toBeInTheDocument();
        expect(within(rail).queryByText('경로 미리보기')).not.toBeInTheDocument();
    });

    it('shows a reason instead of rows when a rail source fails', async () => {
        mocks.gameConst.mockResolvedValue({ mapName: 'han' });
        mocks.diplomacyConflict.mockRejectedValue(new Error('503'));
        render(<GameMapPage />);
        expect(await screen.findByText('세력 현황을 불러올 수 없습니다.')).toBeInTheDocument();
        expect(screen.getByText('편성된 부대가 없습니다.')).toBeInTheDocument();
    });
});
