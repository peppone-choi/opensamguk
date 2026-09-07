import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import HistoryPage from '@/app/game/history/page';

const mocks = vi.hoisted(() => ({
    history: vi.fn(),
    mapPreview: vi.fn(),
}));

vi.mock('@/components/Shell', () => ({
    default: ({ children }: { children: ReactNode }) => <>{children}</>,
}));

vi.mock('@/components/GameCard', () => ({
    default: ({ children }: { children: ReactNode }) => <section>{children}</section>,
}));

vi.mock('@/components/game/MapViewer', () => ({
    default: ({ mapData, disallowClick }: { mapData?: { year?: number; month?: number; cities?: { level: number; nationId: number }[]; nations?: { name: string }[] } | null; disallowClick?: boolean }) => (
        <output data-testid="history-map">
            {mapData == null
                ? 'missing'
                : `${mapData.year}/${mapData.month}:${mapData.cities?.[0]?.level}:${mapData.cities?.[0]?.nationId}:${mapData.nations?.[0]?.name}:${String(disallowClick)}`}
        </output>
    ),
}));

vi.mock('@/lib/api', () => ({
    api: { history: mocks.history, mapPreview: mocks.mapPreview },
}));

vi.mock('@/lib/utilGame', () => ({
    formatLog: (value: string) => value,
}));

const currentRecord = {
    serverId: 'scenario_1010',
    year: 190,
    month: 7,
    map: { year: 190, month: 7, cityList: [[1, 5, 2, 1, 1, 1]], nationList: [[1, '촉', '#2e7d32', 1]] },
    nations: [
        { nation: 1, name: '촉', color: '#2e7d32', power: 500, gennum: 2, cities: ['성도'] },
    ],
    globalHistory: ['<C>●</>190년 7월: 현재 정세'],
    globalAction: ['<Y>관우</>의 현재 동향'],
    hash: 'current',
};

const archiveRecord = {
    ...currentRecord,
    year: 190,
    month: 6,
    map: { year: 190, month: 6, cityList: [[1, 3, 4, 2, 1, 0]], nationList: [[2, '위', '#c62828', 1]] },
    globalHistory: ['<C>●</>190년 6월: 보관 정세'],
    globalAction: ['<Y>장비</>의 보관 동향'],
    hash: 'archive',
};

function response(record: typeof currentRecord) {
    return {
        result: true,
        firstYearMonth: 2285,
        lastYearMonth: 2286,
        currentYearMonth: 2286,
        serverId: 'scenario_1010',
        mapName: 'scenario_1010',
        record,
    };
}

function recordRows(): (string | null)[] {
    return Array.from(document.querySelectorAll('.record-row')).map((row) => row.textContent);
}

describe('HistoryPage', () => {
    beforeEach(() => {
        mocks.history.mockReset();
        mocks.mapPreview.mockReset();
        mocks.history.mockImplementation((yearMonth?: number) =>
            Promise.resolve(yearMonth === 2285 ? response(archiveRecord) : response(currentRecord)),
        );
        mocks.mapPreview.mockResolvedValue({
            serverName: 'scenario_1010',
            year: 190,
            month: 7,
            mapCode: 'che',
            width: 700,
            height: 500,
            cities: [{ id: 1, name: '성도', level: 99, nationId: 99, x: 100, y: 200, state: 99, supply: false, isCapital: false }],
            nations: [],
        });
    });

    it('renders the selected current or archived snapshot instead of a live map', async () => {
        render(<HistoryPage />);

        expect(await screen.findByTestId('history-map')).toHaveTextContent('190/7:5:1:촉:true');
        expect(screen.getByText(/190년 7월: 현재 정세/)).toBeInTheDocument();
        // <Y>관우</> 토큰이 팔레트 span 으로 갈라지므로 행 textContent 로 본다.
        expect(recordRows()).toContain('관우의 현재 동향');

        fireEvent.change(screen.getByRole('combobox'), { target: { value: '2285' } });

        await waitFor(() => expect(mocks.history).toHaveBeenCalledWith(2285));
        await waitFor(() => expect(screen.getByText(/190년 6월: 보관 정세/)).toBeInTheDocument());
        expect(recordRows()).toContain('장비의 보관 동향');
        expect(screen.getByTestId('history-map')).toHaveTextContent('190/6:3:2:위:true');
    });
});
