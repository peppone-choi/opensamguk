// D3-04(web) — 유산 버프 비용은 정본 API(InheritPointResponse.inheritActionCost.buff =
// PHP GameConstBase.php:240 $inheritBuffPoints, v_inheritPoint.php:95 'buff' 직렬화)를
// 소비해야 한다. web 레이어에 박힌 INHERIT_COSTS 사본(패러티 우회)은 위반 —
// 서버가 내려준 배열과 다른 값을 그리면 안 된다.
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import NationPage from '@/app/game/nation/page';

// API가 내려주는 비용 배열을 정본 상수와 일부러 다르게 — 페이지가 로컬 사본을 쓰면 빨갛게 된다.
// (vi.mock은 파일 최상단으로 호이스팅되므로 vi.hoisted로 선언)
const API_BUFF_COSTS = vi.hoisted(() => [0, 111, 333, 777, 1500, 2500]);

// Shell 크롬(라우터/SSE/front-info)은 페이지 로직과 무관 — passthrough로 모킹.
vi.mock('@/components/Shell', () => ({
    default: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

vi.mock('@/lib/api', () => ({
    api: {
        myNationDetail: vi.fn().mockResolvedValue({
            result: true,
            hasNation: true,
            nationId: 1,
            name: '테스트국',
            color: '#000080',
            population: 1000,
            populationMax: 2000,
            crew: 100,
            crewMax: 200,
            power: 10,
            gold: 5000,
            rice: 5000,
            cityCount: 1,
            generalCount: 1,
            tech: 0,
            levelText: '주자사',
            level: 3,
            cities: [],
            taxRate: null,
            bill: null,
        }),
        inheritPoint: vi.fn().mockResolvedValue({
            result: true,
            items: {},
            currentInheritBuff: {},
            maxInheritBuff: 5,
            resetTurnTimeLevel: 0,
            resetSpecialWarLevel: 0,
            inheritActionCost: {
                buff: API_BUFF_COSTS,
                resetTurnTime: 100,
                resetSpecialWar: 100,
                randomUnique: 3000,
                nextSpecial: 1000,
                minSpecificUnique: 5000,
                checkOwner: 10,
                bornStatPoint: 10,
            },
            availableSpecialWar: {},
            availableUnique: {},
            lastInheritPointLogs: [],
            availableTargetGeneral: {},
            currentStat: { leadership: 50, strength: 50, intel: 50, statMin: 10, statMax: 90 },
        }),
        command: vi.fn(),
    },
}));

describe('nation page inherit buff costs (D3-04 web)', () => {
    it('renders buff costs from inheritActionCost.buff, not a local constant copy', async () => {
        render(<NationPage />);

        // currentLevel 0 → L1 비용 = buff[1] - buff[0] = 111. 로컬 사본이면 200이 그려진다.
        const l1Buttons = await screen.findAllByText('L1 (111P)');
        expect(l1Buttons.length).toBeGreaterThan(0);

        // L5 비용 = buff[5] - buff[0] = 2500. 로컬 사본이면 3000.
        expect(screen.getAllByText('L5 (2,500P)').length).toBeGreaterThan(0);
        expect(screen.queryByText('L1 (200P)')).not.toBeInTheDocument();
        expect(screen.queryByText('L5 (3,000P)')).not.toBeInTheDocument();
    });
});
