import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, type MockInstance, vi } from 'vitest';
import MyPage from '@/app/game/my/page';
import { CONTROL_BUTTONS } from '@/lib/control-bar-config';

const apiMocks = vi.hoisted(() => ({
    frontInfo: vi.fn(),
    myPage: vi.fn(),
    instantAction: vi.fn(),
}));

const commandMocks = vi.hoisted(() => ({
    submitCommandAndAwaitResult: vi.fn(),
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

vi.mock('@/lib/commandSubmit', () => ({
    submitCommandAndAwaitResult: commandMocks.submitCommandAndAwaitResult,
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
    items: [
        { type: 'horse', label: '명마', code: 'che_명마_15_적토마', name: '적토마(+15)', droppable: true },
        { type: 'weapon', label: '무기', code: 'None', name: '-', droppable: false },
        { type: 'book', label: '서적', code: 'che_서적_03_손자병법', name: '손자병법(+3)', droppable: true },
        { type: 'item', label: '도구', code: 'None', name: '-', droppable: false },
    ],
    instantActions: {
        instantRetreatPossible: true,
        dieOnPrestartPossible: true,
    },
};

describe('MyPage route', () => {
    let confirmSpy: MockInstance<typeof window.confirm>;

    beforeEach(() => {
        confirmSpy = vi.spyOn(window, 'confirm');
        apiMocks.frontInfo.mockReset();
        apiMocks.myPage.mockReset();
        apiMocks.instantAction.mockReset();
        commandMocks.submitCommandAndAwaitResult.mockReset();
        apiMocks.frontInfo.mockResolvedValue(frontInfo);
        apiMocks.myPage.mockResolvedValue(myPage);
        apiMocks.instantAction.mockResolvedValue({ status: 'AVAILABLE', requestId: 'req-1', code: 'DropItem' });
        commandMocks.submitCommandAndAwaitResult.mockImplementation(async (submit: () => Promise<unknown>) => {
            await submit();
            return { status: 'pending', reason: '처리 지연' };
        });
        confirmSpy.mockReturnValue(true);
    });

    afterEach(() => {
        confirmSpy.mockRestore();
    });

    it('routes the legacy 내 정보&설정 control button to /game/my', () => {
        expect(CONTROL_BUTTONS.find((button) => button.label === '내 정보&설정')?.href).toBe('/game/my');
        expect(CONTROL_BUTTONS.find((button) => button.label === '세력 장수')?.href).toBe('/game/my-generals');
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

    it('shows instant action controls from item list and read flags', async () => {
        render(<MyPage />);

        await waitFor(() => {
            expect(screen.getByRole('button', { name: '아이템 버리기' })).toBeInTheDocument();
        });

        expect(screen.getByRole('combobox', { name: '버릴 아이템' })).toHaveValue('horse');
        expect(screen.getByRole('button', { name: '즉시 접경귀환' })).toBeInTheDocument();
        expect(screen.getByRole('button', { name: '개전 전 장수 삭제' })).toBeInTheDocument();
    });

    it('hides instant action controls when item list and read flags are off', async () => {
        apiMocks.myPage.mockResolvedValue({
            ...myPage,
            items: [
                { type: 'horse', label: '명마', code: 'None', name: '-', droppable: false },
                { type: 'weapon', label: '무기', code: 'None', name: '-', droppable: false },
                { type: 'book', label: '서적', code: 'None', name: '-', droppable: false },
                { type: 'item', label: '도구', code: 'None', name: '-', droppable: false },
            ],
            instantActions: {
                instantRetreatPossible: false,
                dieOnPrestartPossible: false,
            },
        });

        render(<MyPage />);

        await waitFor(() => {
            expect(screen.getByRole('heading', { name: '내 정보&설정' })).toBeInTheDocument();
        });

        expect(screen.queryByRole('button', { name: '아이템 버리기' })).not.toBeInTheDocument();
        expect(screen.queryByRole('button', { name: '즉시 접경귀환' })).not.toBeInTheDocument();
        expect(screen.queryByRole('button', { name: '개전 전 장수 삭제' })).not.toBeInTheDocument();
    });

    it('submits DropItem through submitCommandAndAwaitResult with the selected item type', async () => {
        render(<MyPage />);

        await waitFor(() => {
            expect(screen.getByRole('button', { name: '아이템 버리기' })).toBeInTheDocument();
        });

        fireEvent.change(screen.getByRole('combobox', { name: '버릴 아이템' }), { target: { value: 'book' } });
        fireEvent.click(screen.getByRole('button', { name: '아이템 버리기' }));

        await waitFor(() => {
            expect(apiMocks.instantAction).toHaveBeenCalledWith('DropItem', 77, { itemType: 'book' });
        });
        expect(commandMocks.submitCommandAndAwaitResult).toHaveBeenCalledTimes(1);
        expect(confirmSpy).toHaveBeenCalledWith('손자병법(+3)을(를) 버리시겠습니까?');
    });

    it('submits InstantRetreat and DieOnPrestart through submitCommandAndAwaitResult', async () => {
        render(<MyPage />);

        await waitFor(() => {
            expect(screen.getByRole('button', { name: '즉시 접경귀환' })).toBeInTheDocument();
        });

        fireEvent.click(screen.getByRole('button', { name: '즉시 접경귀환' }));
        await waitFor(() => {
            expect(apiMocks.instantAction).toHaveBeenCalledWith('InstantRetreat', 77, undefined);
        });

        fireEvent.click(screen.getByRole('button', { name: '개전 전 장수 삭제' }));
        await waitFor(() => {
            expect(apiMocks.instantAction).toHaveBeenCalledWith('DieOnPrestart', 77, undefined);
        });
        expect(commandMocks.submitCommandAndAwaitResult).toHaveBeenCalledTimes(2);
    });
});
