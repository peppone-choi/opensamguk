import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import Admin1Page from '@/app/game/admin1/page';

const apiMocks = vi.hoisted(() => ({
    gameSettings: vi.fn(),
    patchGameSettings: vi.fn(),
}));

vi.mock('@/components/Shell', () => ({
    default: ({ children }: { children: ReactNode }) => <div>{children}</div>,
}));

vi.mock('@/components/GameCard', () => ({
    default: ({ children }: { children: ReactNode }) => <section>{children}</section>,
}));

vi.mock('@/lib/api', () => ({
    api: {
        admin: {
            gameSettings: apiMocks.gameSettings,
            patchGameSettings: apiMocks.patchGameSettings,
        },
    },
}));

const settings = {
    msg: '공지',
    logWritable: false,
    scenarioCode: '1010',
    year: 200,
    month: 1,
    starttime: '0200-01-01T00:00:00Z',
    startyear: 184,
    maxgeneral: 500,
    maxnation: 50,
    turntime: '0200-01-01T01:00:00Z',
    turnterm: 5,
    turnOptions: [1, 2, 3, 5, 10],
    blockedWrites: [],
};

describe('Admin1Page live settings editor', () => {
    beforeEach(() => {
        apiMocks.gameSettings.mockReset().mockResolvedValue(settings);
        apiMocks.patchGameSettings.mockReset().mockResolvedValue({
            result: true,
            updated: ['maxgeneral'],
            restartRequired: false,
        });
    });

    it('patches editable values and reloads server state', async () => {
        render(<Admin1Page />);

        const maximumGeneral = await screen.findByRole('spinbutton', { name: '최대 장수' });
        fireEvent.change(maximumGeneral, { target: { value: '650' } });
        fireEvent.click(screen.getByRole('button', { name: '변경2' }));

        await waitFor(() => expect(apiMocks.patchGameSettings).toHaveBeenCalledWith({ maxgeneral: 650 }));
        expect(apiMocks.gameSettings).toHaveBeenCalledTimes(2);
        expect(await screen.findByText('저장했습니다.')).toBeInTheDocument();
    });
});
