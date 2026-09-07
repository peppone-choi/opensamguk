import { render, screen, within } from '@testing-library/react';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import CityPage from '@/app/game/city/page';
import type { CityDetailResponse } from '@/types/game';

const mocks = vi.hoisted(() => ({
    city: vi.fn(),
    push: vi.fn(),
    id: '5',
}));

vi.mock('next/navigation', () => ({
    useRouter: () => ({ push: mocks.push }),
    useSearchParams: () => new URLSearchParams(`id=${mocks.id}`),
}));
vi.mock('@/components/Shell', () => ({
    default: ({ children }: { children: ReactNode }) => <main>{children}</main>,
}));
vi.mock('@/hooks/useTurnRefresh', () => ({ useTurnRefresh: vi.fn() }));
vi.mock('@/lib/api', () => ({
    api: { city: mocks.city },
}));

const BASE: CityDetailResponse = {
    id: 5,
    name: '허창',
    level: 7,
    levelName: '대',
    region: 3,
    regionName: '중원',
    nationId: 1,
    visible: true,
    population: 84200,
    populationMax: 100000,
    agriculture: 6412,
    agricultureMax: 8000,
    commerce: 5900,
    commerceMax: 8000,
    security: 4100,
    securityMax: 5000,
    defense: 3100,
    defenseMax: 5000,
    wall: 7400,
    wallMax: 9000,
    trust: 72.4,
    trade: 104,
    supplyState: 1,
    frontState: 0,
    officers: 6,
    showDetailedInfo: true,
    lastExecute: '03-06 12:00:00',
    cityName: '허창',
    officerGovernor: { name: '순욱', npc: 0 },
    officerStrategist: { name: '정욱', npc: 0 },
    officerSecretary: { name: '-', npc: 0 },
    military: {
        enemyCrew: 0,
        enemyArmedCnt: 0,
        enemyCnt: 0,
        crewTotal: 18400,
        armedGenTotal: 5,
        genTotal: 6,
        crew90: 12000,
        gen90: 1,
        crew60: 2000,
        gen60: 1,
        crewDef: 0,
        genDef: 0,
    },
    generals: [
        {
            no: 11,
            ourGeneral: true,
            iconPath: 'hahoudon.jpg',
            npc: 0,
            isNPC: false,
            wounded: 0,
            name: '하후돈',
            leadership: 88,
            strength: 91,
            intel: 62,
            politics: 58,
            charm: 71,
            officerLevel: 8,
            officerLevelText: '장군',
            leadershipBonus: 0,
            crewType: 1,
            crewTypeName: '기병',
            crew: 12000,
            train: 84,
            atmos: 77,
            nation: 1,
            nationName: '조조',
        },
    ],
    generalNames: [{ name: '하후돈', npc: 0 }],
    citySelector: [],
};

const HEADERS = ['얼 굴', '이 름', '통솔', '무력', '지력', '정치', '매력', '관 직', '守', '병 종', '병 사', '훈련', '사기', '명 령'];

describe('CityPage (06 도시)', () => {
    beforeEach(() => {
        mocks.city.mockReset();
        mocks.id = '5';
    });

    it('keeps the 14 verbatim columns and renders the face cell as a 28px portrait icon', async () => {
        mocks.city.mockResolvedValue(BASE);
        render(<CityPage />);
        // 로딩 → 본문 전환 뒤에 본다(로딩 상태의 h1 은 교체된다).
        expect(await screen.findByRole('columnheader', { name: '얼 굴' })).toBeInTheDocument();
        expect(screen.getByRole('heading', { name: '도시 정보' })).toBeInTheDocument();
        expect(mocks.city).toHaveBeenCalledWith(5);
        const headers = screen.getAllByRole('columnheader').map((th) => th.textContent);
        expect(headers).toEqual(HEADERS);
        const row = screen.getAllByRole('row')[1];
        const portrait = row.querySelector('.os-portrait');
        expect(portrait).toHaveAttribute('data-size', 'icon-28');
        expect(row).toHaveTextContent('하후돈');
        expect(row).toHaveTextContent('기병');
        // 히어로·게이지·집계 — 라벨 verbatim, 수치는 응답 그대로
        expect(screen.getByText('허창', { selector: '.city-hero__name' })).toBeInTheDocument();
        expect(screen.getByText('【중원 | 대】')).toBeInTheDocument();
        expect(screen.getByText('지배 도시')).toBeInTheDocument();
        expect(screen.queryByText('보급 끊김')).not.toBeInTheDocument();
        expect(screen.getByRole('meter', { name: '주민' })).toHaveAttribute('aria-valuenow', '84200');
        expect(screen.getByRole('meter', { name: '시세' })).toBeInTheDocument();
        expect(screen.getAllByRole('meter')).toHaveLength(8);
        const military = screen.getByRole('heading', { name: '주둔' }).closest('.os-panel') as HTMLElement;
        expect(within(military).getByText('병장(총)')).toBeInTheDocument();
        expect(within(military).getByText('18,400/5(6)')).toBeInTheDocument();
        expect(within(military).getByText('수비○').nextElementSibling).toHaveTextContent('-');
    });

    it('masks fogged cities without fabricating gauges and flags a cut supply line', async () => {
        mocks.city.mockResolvedValue({ ...BASE, visible: false, showDetailedInfo: false, supplyState: 0, generals: [], generalNames: [], population: null });
        render(<CityPage />);
        expect(await screen.findByText(/첩보가 없어 내정 정보를 볼 수 없습니다/)).toBeInTheDocument();
        expect(screen.queryAllByRole('meter')).toHaveLength(0);
        expect(screen.queryByRole('table')).not.toBeInTheDocument();
        expect(screen.getByText('보급 끊김')).toBeInTheDocument();
    });

    it('routes the 도시선택 selector through the server-scoped city href', async () => {
        mocks.city.mockResolvedValue({
            ...BASE,
            citySelector: [
                { cityId: 5, cityName: '허창', relation: 1, selected: true },
                { cityId: 9, cityName: '낙양', relation: 2, selected: false },
            ],
        });
        render(<CityPage />);
        const select = await screen.findByRole('combobox');
        expect(within(select).getByRole('option', { name: '【낙양】타국' })).toBeInTheDocument();
    });
});
