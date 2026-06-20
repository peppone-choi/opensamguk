import { render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import MyPage from '@/app/game/my/page';
import { CONTROL_BUTTONS } from '@/lib/control-bar-config';

const apiMocks = vi.hoisted(() => ({
    frontInfo: vi.fn(),
    myPage: vi.fn(),
}));

vi.mock('@/components/Shell', () => ({
    default: ({ children }: { children: ReactNode }) => <div>{children}</div>,
}));

vi.mock('@/components/game/GeneralBasicCard', () => ({
    default: () => <section data-testid="general-basic-card" />,
}));

vi.mock('@/components/game/MyInfoLogPanel', () => ({
    default: ({ generalId }: { generalId: number }) => (
        <section data-testid="my-info-log-panel">general:{generalId}</section>
    ),
}));

vi.mock('@/lib/api', () => ({
    api: apiMocks,
}));

const frontInfo = {
    result: true,
    global: {},
    general: {
        hasGeneral: true,
        generalId: 77,
        name: '코덱스',
        nationId: 1,
        officerLevel: 1,
        permission: 0,
        showSecret: false,
        leadership: 70,
        strength: 60,
        intel: 80,
        injury: 0,
        gold: 100,
        rice: 200,
        crew: 300,
        cityId: 11,
        defenceTrain: 80,
    },
    nation: { id: 1, name: '후한왕조', color: '#333333', level: 1, gold: 0, rice: 0, tech: 0, capitalCityId: 1 },
    city: null,
    recentRecord: { history: [], global: [], general: [], flushHistory: 0, flushGlobal: 0, flushGeneral: 0 },
};

const myPage = {
    generalId: 77,
    name: '코덱스',
    nationId: 1,
    nationName: '후한왕조',
    cityId: 11,
    cityName: '업',
    officerLevel: 1,
    permission: 0,
    leadership: 70,
    strength: 60,
    intel: 80,
    politics: 55,
    charm: 45,
    injury: 0,
    experience: 1000,
    dedication: 1200,
    gold: 100,
    rice: 200,
    crew: 300,
    train: 90,
    atmos: 80,
    picture: 'default.jpg',
    imageServer: 0,
};

describe('MyPage route', () => {
    beforeEach(() => {
        apiMocks.frontInfo.mockReset();
        apiMocks.myPage.mockReset();
        apiMocks.frontInfo.mockResolvedValue(frontInfo);
        apiMocks.myPage.mockResolvedValue(myPage);
    });

    it('routes the legacy 내 정보&설정 control button to /game/my', () => {
        expect(CONTROL_BUTTONS.find((button) => button.label === '내 정보&설정')?.href).toBe('/game/my');
    });

    it('renders the legacy b_myPage read structure instead of a 404 route gap', async () => {
        render(<MyPage />);

        await waitFor(() => {
            expect(screen.getByRole('heading', { name: '내 정보&설정' })).toBeInTheDocument();
        });

        expect(screen.getByTestId('general-basic-card')).toBeInTheDocument();
        expect(screen.getByTestId('my-info-log-panel')).toHaveTextContent('general:77');
        expect(screen.getByText('후한왕조')).toBeInTheDocument();
        expect(screen.getByText('업')).toBeInTheDocument();
        expect(screen.getByText('훈련/사기')).toBeInTheDocument();
        expect(screen.getByText('90 / 80')).toBeInTheDocument();
        expect(screen.getByText('수비')).toBeInTheDocument();
        expect(screen.getByText('◎(훈사80)')).toBeInTheDocument();
    });
});
