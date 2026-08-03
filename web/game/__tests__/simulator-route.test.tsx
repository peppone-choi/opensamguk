import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { describe, expect, it, vi } from 'vitest';
import SimulatorPage from '@/app/game/simulator/page';

const mocks = vi.hoisted(() => ({
    generalsList: vi.fn(),
    simulateBattle: vi.fn(),
}));

vi.mock('@/components/Shell', () => ({
    default: ({ children }: { children: ReactNode }) => <div>{children}</div>,
}));

vi.mock('@/components/GameCard', () => ({
    default: ({ children }: { children: ReactNode }) => <section>{children}</section>,
}));

vi.mock('@/lib/api', () => ({
    api: {
        generalsList: mocks.generalsList,
        simulateBattle: mocks.simulateBattle,
    },
}));

describe('SimulatorPage', () => {
    it('shows all five stats in attacker and defender options', async () => {
        mocks.generalsList.mockResolvedValue([
            {
                generalId: 1,
                name: '조조',
                leadership: 95,
                strength: 80,
                intel: 90,
                politics: 91,
                charm: 76,
                crew: 300,
                nationName: '위',
                cityName: '허창',
            },
        ]);

        render(<SimulatorPage />);
        fireEvent.click(screen.getByRole('button', { name: '장수 목록 불러오기' }));

        await waitFor(() => {
            expect(screen.getAllByRole('option', { name: '조조 (통95 무80 지90 정치91 매력76)' })).toHaveLength(2);
        });
    });
});
