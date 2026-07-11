import { render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import MyCitiesPage from '@/app/game/my-cities/page';
import MyNationPage from '@/app/game/my-nation/page';

const apiMocks = vi.hoisted(() => ({
    myCities: vi.fn(),
    myNationDetail: vi.fn(),
}));

vi.mock('@/components/Shell', () => ({
    default: ({ children }: { children: ReactNode }) => <div>{children}</div>,
}));

vi.mock('@/components/GameCard', () => ({
    default: ({ children }: { children: ReactNode }) => <section>{children}</section>,
}));

vi.mock('@/lib/api', () => ({ api: apiMocks }));

const nation = {
    result: true,
    hasNation: true,
    nationId: 1,
    name: '위',
    color: '#333333',
    population: 30000,
    populationMax: 100000,
    crew: 100,
    crewMax: 1000,
    power: 10,
    gold: 1000,
    rice: 2000,
    cityCount: 1,
    generalCount: 1,
    tech: 5,
    levelText: '왕',
    level: 1,
    cities: [{ cityId: 1, name: '업', isCapital: true }],
    taxRate: 20,
    bill: 100,
    goldIncome: 55,
    warIncome: 0,
    riceIncome: 83,
    farmIncome: 22,
    outcome: 50,
    goldBudget: 1005,
    goldBudgetDiff: 5,
    riceBudget: 2055,
    riceBudgetDiff: 55,
};

const cities = {
    result: true,
    nationId: 1,
    cities: [{
        cityId: 1,
        name: '업',
        level: 5,
        levelText: '중',
        region: 1,
        regionText: '하북',
        isCapital: true,
        population: 30000,
        populationMax: 100000,
        agriculture: 1500,
        agricultureMax: 2000,
        commerce: 1000,
        commerceMax: 2000,
        security: 1000,
        securityMax: 2000,
        defense: 1000,
        defenseMax: 2000,
        wall: 1000,
        wallMax: 2000,
        trust: 100,
        trade: 100,
        governorName: null,
        governorNpc: 0,
        strategistName: null,
        strategistNpc: 0,
        secretaryName: null,
        secretaryNpc: 0,
        generals: [],
        goldIncome: 55,
        riceIncome: 83,
        farmIncome: 22,
    }],
};

describe('finance read routes', () => {
    beforeEach(() => {
        apiMocks.myCities.mockResolvedValue(cities);
        apiMocks.myNationDetail.mockResolvedValue(nation);
    });

    it('renders national income, expense, and budget values', async () => {
        render(<MyNationPage />);

        await waitFor(() => expect(screen.getByText('세력 정보')).toBeInTheDocument());

        expect(screen.getByText('+55 / 0')).toBeInTheDocument();
        expect(screen.getByText('+83 / +22')).toBeInTheDocument();
        expect(screen.getByText('+55 / -50')).toBeInTheDocument();
        expect(screen.getByText('1,005 (+5)')).toBeInTheDocument();
        expect(screen.getByText('2,055 (+55)')).toBeInTheDocument();
    });

    it('renders per-city finance values', async () => {
        render(<MyCitiesPage />);

        await waitFor(() => expect(screen.getByText('세력 도시')).toBeInTheDocument());

        expect(screen.getByText('자금 수입')).toBeInTheDocument();
        expect(screen.getByText('55')).toBeInTheDocument();
        expect(screen.getByText('83')).toBeInTheDocument();
        expect(screen.getByText('22')).toBeInTheDocument();
    });
});
