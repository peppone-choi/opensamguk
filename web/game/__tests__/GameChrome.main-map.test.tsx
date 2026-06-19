import { render } from '@testing-library/react';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import GameChrome from '@/components/game/GameChrome';

const mocks = vi.hoisted(() => ({
    mapViewer: vi.fn(() => null),
    useFrontInfo: vi.fn(),
    showToast: vi.fn(),
    removeToast: vi.fn(),
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
vi.mock('@/components/game/CharacterClaim', () => ({ default: () => null }));
vi.mock('@/components/game/PartialReservedCommand', () => ({ default: () => null }));
vi.mock('@/components/game/GeneralBasicCard', () => ({ default: () => null }));
vi.mock('@/components/game/NationBasicCard', () => ({ default: () => null }));
vi.mock('@/components/game/CityBasicCard', () => ({ default: () => null }));
vi.mock('@/components/game/MessagePanel', () => ({ default: () => null }));
vi.mock('@/components/Toast', () => ({ default: () => null }));

const frontInfo = {
    global: {
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
    },
    city: {
        id: 11,
    },
    recentRecord: [],
};

describe('GameChrome main map', () => {
    beforeEach(() => {
        mocks.mapViewer.mockClear();
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
});
