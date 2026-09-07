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
    // 작전실 카드 리디자인(ADR-LITE-049 · 03 아트보드): 마크업은 `.basic-card*` → `.war-card*` 로 바뀌었고
    // 능력치는 라벨/값 행이 아니라 5열 막대다. 표시하는 값(보정치·자금·군량·접힌 추가정보)은 그대로다.
    it('renders the five stat meters with bonuses and keeps extra info collapsed', () => {
        const { container } = render(<GeneralBasicCard general={general} nation={nation} />);

        expect(screen.getByText('+7')).toBeInTheDocument();
        expect(screen.getByText('-2')).toBeInTheDocument();
        expect(screen.getByText('+3')).toBeInTheDocument();
        expect(screen.getByText('-1')).toBeInTheDocument();
        expect(screen.getByText('12,345')).toBeInTheDocument();
        expect(screen.getByText('67,890')).toBeInTheDocument();
        expect(screen.getByText('추가정보').closest('details')).not.toHaveAttribute('open');

        for (const label of ['통솔', '무력', '지력', '정치', '매력']) {
            const meter = screen.getByRole('meter', { name: label });
            expect(meter).toHaveClass('war-card__stat');
            expect(screen.getByText(label)).toHaveClass('war-card__k');
        }
        expect(container.querySelectorAll('.war-card__stat').length).toBe(5);
        // 능력 경험(*_exp)이 있는 다섯 항목은 얇은 두 번째 막대를 함께 그린다.
        expect(container.querySelectorAll('.war-card__stat-exp').length).toBe(5);
        // Lv·경험은 이름 줄 우측으로 이동했다(라벨 행이 아니다).
        expect(container.querySelector('.war-card__lv')?.textContent).toContain('Lv');
        expect(container.querySelector('.war-card__lvbar')).not.toBeNull();
    });

    it('renders 관직 · 소속 · 호칭 · 부상 as chips', () => {
        const { container } = render(<GeneralBasicCard general={general} nation={nation} />);
        const chips = Array.from(container.querySelectorAll('.war-card__chip')).map((c) => c.textContent);
        expect(chips.some((t) => t?.startsWith('소속'))).toBe(true);
        expect(chips).toContain('부상 건강');
    });

    // 부상일 때 라벨이 사라지면 그 칩이 무엇인지 알 수 없다 — 라벨은 상태와 무관하게 남아야 한다.
    it('keeps the 부상 label when the general is actually injured', () => {
        const { container } = render(<GeneralBasicCard general={{ ...general, injury: 35 }} nation={nation} />);
        const chips = Array.from(container.querySelectorAll('.war-card__chip')).map((c) => c.textContent);
        expect(chips).toContain('부상 중상');
    });

    // 능력 막대의 분모는 엔진 상한(255)이다. 100 을 넘는 장수가 꽉 찬 막대로 뭉개지면 안 된다.
    it('scales stat meters against the engine cap, not 100', () => {
        const { container } = render(<GeneralBasicCard general={{ ...general, leadership: 151, strength: 100 }} nation={nation} />);
        const lead = screen.getByRole('meter', { name: '통솔' });
        expect(lead).toHaveAttribute('aria-valuemax', '255');
        expect(lead).toHaveAttribute('aria-valuenow', '151');
        const widths = Array.from(container.querySelectorAll('.war-card__stat-bar > i')).map((i) => (i as HTMLElement).style.width);
        expect(widths[0]).not.toBe(widths[1]);
    });
});
