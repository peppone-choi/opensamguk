import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import GeneralBasicCard from '@/components/game/GeneralBasicCard';
import type { FrontGeneralInfo, FrontNationInfo } from '@/lib/types';

const general: FrontGeneralInfo = {
    hasGeneral: true,
    generalId: 10,
    name: '순욱',
    nationId: 1,
    officerLevel: 5,
    permission: 2,
    showSecret: true,
    leadership: 70,
    strength: 40,
    intel: 80,
    politics: 55,
    charm: 45,
    injury: 0,
    gold: 12345,
    rice: 67890,
    crew: 300,
    cityId: 5,
    experience: 1200,
    explevel: 3,
    dedication: 450,
    leadershipExp: 15,
    strengthExp: 2,
    intelExp: 29,
    politicsExp: 8,
    charmExp: 13,
    leadershipBonus: 7,
    strengthBonus: -2,
    intelBonus: 0,
    politicsBonus: 3,
    charmBonus: -1,
    warnum: 5,
    killnum: 3,
    deathnum: 2,
    firenum: 1,
    killcrew: 120,
    deathcrew: 80,
    belong: 4,
};

const nation: FrontNationInfo = {
    id: 1,
    name: '위',
    color: '#003399',
    level: 7,
    gold: 0,
    rice: 0,
    tech: 0,
    capitalCityId: 5,
};

describe('GeneralBasicCard', () => {
    it('renders stat and level as six key-value pairs and keeps extra info collapsed', () => {
        const { container } = render(<GeneralBasicCard general={general} nation={nation} />);

        expect(screen.getByText('+7')).toBeInTheDocument();
        expect(screen.getByText('-2')).toBeInTheDocument();
        expect(screen.getByText('+3')).toBeInTheDocument();
        expect(screen.getByText('-1')).toBeInTheDocument();
        expect(screen.getByText('12,345')).toBeInTheDocument();
        expect(screen.getByText('67,890')).toBeInTheDocument();
        expect(screen.getByText('추가정보').closest('details')).not.toHaveAttribute('open');
        expect(container.querySelectorAll('.general-basic-card .basic-card-head').length).toBeGreaterThanOrEqual(6);
        expect(screen.getByText('통솔')).toHaveClass('basic-card-head');
        expect(screen.getByText('무력')).toHaveClass('basic-card-head');
        expect(screen.getByText('지력')).toHaveClass('basic-card-head');
        expect(screen.getByText('정치')).toHaveClass('basic-card-head');
        expect(screen.getByText('매력')).toHaveClass('basic-card-head');
        expect(screen.getByText('Lv')).toHaveClass('basic-card-head');
        expect(container.querySelectorAll('.sammo-bar').length).toBeGreaterThanOrEqual(6);
    });
});
