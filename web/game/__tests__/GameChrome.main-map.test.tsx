import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import GameChrome from '@/components/game/GameChrome';

const mocks = vi.hoisted(() => ({
    mapViewer: vi.fn(() => null),
    useFrontInfo: vi.fn(),
    showToast: vi.fn(),
    removeToast: vi.fn(),
    routerReplace: vi.fn(),
}));

vi.mock('next/navigation', () => ({
    useRouter: () => ({
        replace: mocks.routerReplace,
    }),
}));

vi.mock('@/hooks/useFrontInfo', () => ({
    useFrontInfo: mocks.useFrontInfo,
}));

vi.mock('@/hooks/useToast', () => ({
    useToast: () => ({ toasts: [], show: mocks.showToast, remove: mocks.removeToast }),
}));

vi.mock('@/components/game/MapViewer', () => ({
    default: mocks.mapViewer,
}));

vi.mock('@/components/game/GameInfo', () => ({ default: () => null }));
vi.mock('@/components/game/GlobalMenu', () => ({ default: () => null }));
vi.mock('@/components/game/MainControlBar', () => ({ default: () => null }));
vi.mock('@/components/game/MainControlDropdown', () => ({ default: () => null }));
vi.mock('@/components/game/PartialReservedCommand', () => ({ default: () => null }));
vi.mock('@/components/game/GeneralBasicCard', () => ({ default: () => <div data-testid="general-card" /> }));
vi.mock('@/components/game/NationBasicCard', () => ({ default: () => <div data-testid="nation-card" /> }));
vi.mock('@/components/game/CityBasicCard', () => ({ default: () => <div data-testid="city-card" /> }));
vi.mock('@/components/game/MessagePanel', () => ({ default: () => null }));
vi.mock('@/components/Toast', () => ({ default: () => null }));

const frontInfo = {
    global: {
        serverId: 's1',
        onlineNations: '위, 촉',
        isTournamentApplicationOpen: false,
        isBettingActive: false,
    },
    general: {
        hasGeneral: true,
        showSecret: false,
        permission: 0,
        officerLevel: 0,
        nationId: 1,
        generalId: 42,
    },
    nation: {
        level: 1,
        onlineGen: 3,
        notice: '한실부흥',
    },
    city: {
        id: 11,
    },
    recentRecord: {
        history: [],
        global: [],
        general: [],
        flushHistory: 0,
        flushGlobal: 0,
        flushGeneral: 0,
    },
};

describe('GameChrome main map', () => {
    beforeEach(() => {
        mocks.mapViewer.mockClear();
        mocks.routerReplace.mockClear();
        mocks.useFrontInfo.mockReturnValue({
            frontInfo,
            constData: { maxTurn: 10 },
            menu: [],
            loading: false,
            error: null,
            refreshKey: 7,
            refresh: vi.fn(),
        });
    });

    it('uses the legacy detail-map mode on the main board', () => {
        render(<GameChrome />);

        expect(mocks.mapViewer).toHaveBeenCalledTimes(1);
        const [props] = mocks.mapViewer.mock.calls[0] as unknown as [
            {
                live?: boolean;
                showMe?: number;
                refreshKey?: number;
                currentCityId?: number | null;
                disallowClick?: boolean;
            },
        ];
        expect(props).toMatchObject({
            live: true,
            showMe: 1,
            refreshKey: 7,
            currentCityId: 11,
        });
        expect(props.disallowClick).not.toBe(true);
    });

    it('keeps legacy main-board slots for map and info cards', () => {
        const { container } = render(<GameChrome />);

        expect(container.querySelector('.ingame-board')).not.toBeNull();
        expect(container.querySelector('.ib-map')).not.toBeNull();
        expect(screen.getByTestId('city-card').parentElement).toHaveClass('ib-city');
        expect(screen.getByTestId('nation-card').parentElement).toHaveClass('ib-nation');
        expect(screen.getByTestId('general-card').parentElement).toHaveClass('ib-general');
    });

    it('renders legacy PageFront status rows before the main board', () => {
        const { container } = render(<GameChrome />);

        expect(screen.getByText('접속중인 국가: 위, 촉')).toBeInTheDocument();
        expect(screen.getByText('【 접속자 】 3')).toBeInTheDocument();
        expect(screen.getByText('【 국가방침 】')).toBeInTheDocument();
        expect(screen.getByText('한실부흥')).toBeInTheDocument();
        expect(container.querySelector('.main-status')?.nextElementSibling).toHaveClass('ingame-board');
    });

    it('passes front-info into function children for the main record zone', () => {
        const { container } = render(
            <GameChrome>
                {(loadedFrontInfo) => (
                    <div data-testid="record-probe">{loadedFrontInfo.recentRecord.global[0]?.[1]}</div>
                )}
            </GameChrome>,
        );

        expect(screen.getByTestId('record-probe')).toHaveTextContent('');
        expect(screen.getByTestId('record-probe').closest('.main-page-content')).not.toBeNull();
        expect(container.querySelector('.ingame-board')?.nextElementSibling).toHaveClass('main-page-content');

        mocks.useFrontInfo.mockReturnValueOnce({
            frontInfo: {
                ...frontInfo,
                recentRecord: {
                    ...frontInfo.recentRecord,
                    global: [[99, '장수 동향 연결']],
                },
            },
            constData: { maxTurn: 10 },
            menu: [],
            loading: false,
            error: null,
            refreshKey: 8,
            refresh: vi.fn(),
        });

        render(
            <GameChrome>
                {(loadedFrontInfo) => (
                    <div data-testid="record-probe-filled">{loadedFrontInfo.recentRecord.global[0]?.[1]}</div>
                )}
            </GameChrome>,
        );

        expect(screen.getByTestId('record-probe-filled')).toHaveTextContent('장수 동향 연결');
    });

    it('redirects no-general users to the server-scoped join page instead of rendering the retired inline claim screen', () => {
        mocks.useFrontInfo.mockReturnValueOnce({
            frontInfo: {
                ...frontInfo,
                general: {
                    ...frontInfo.general,
                    hasGeneral: false,
                    generalId: null,
                },
            },
            constData: { maxTurn: 10 },
            menu: [],
            loading: false,
            error: null,
            refreshKey: 7,
            refresh: vi.fn(),
        });

        render(<GameChrome />);

        expect(mocks.routerReplace).toHaveBeenCalledWith('/game/s1/join');
        expect(screen.getByText('장수 생성 화면으로 이동 중입니다.')).toBeInTheDocument();
        expect(screen.queryByText('장수 등록')).not.toBeInTheDocument();
        expect(mocks.mapViewer).not.toHaveBeenCalled();
    });
});
