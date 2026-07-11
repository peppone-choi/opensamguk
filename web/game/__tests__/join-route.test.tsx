import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import JoinPage from '@/app/game/join/page';

const replaceMock = vi.hoisted(() => vi.fn());
const pushMock = vi.hoisted(() => vi.fn());
const refreshMock = vi.hoisted(() => vi.fn());
const frontInfoState = vi.hoisted(() => ({ hasGeneral: true }));
const apiMocks = vi.hoisted(() => ({
    join: vi.fn(),
    joinForm: vi.fn(),
    mapPreview: vi.fn(),
}));

vi.mock('next/navigation', () => ({
    useRouter: () => ({
        push: pushMock,
        replace: replaceMock,
        refresh: refreshMock,
        prefetch: vi.fn(),
        back: vi.fn(),
    }),
}));

vi.mock('@/components/Shell', () => ({
    default: ({ children }: { children: ReactNode }) => <div>{children}</div>,
}));

vi.mock('@/hooks/useFrontInfo', () => ({
    useFrontInfo: () => ({
        frontInfo: {
            result: true,
            global: { serverId: 's1' },
            general: { hasGeneral: frontInfoState.hasGeneral, name: '코덱스', generalId: 77 },
        },
        constData: null,
        menu: [],
        loading: false,
        error: null,
        refreshKey: 0,
        refresh: vi.fn(),
    }),
}));

vi.mock('@/lib/api', () => ({
    api: {
        mapPreview: apiMocks.mapPreview,
        join: apiMocks.join,
        joinForm: apiMocks.joinForm,
    },
}));

describe('JoinPage route guard', () => {
    beforeEach(() => {
        replaceMock.mockReset();
        pushMock.mockReset();
        refreshMock.mockReset();
        apiMocks.join.mockReset();
        apiMocks.joinForm.mockReset().mockResolvedValue({
            result: true,
            member: {
                name: '페포네',
                picture: 'custom.jpg',
                imageServer: 0,
                canUsePicture: true,
            },
            inheritTotalPoint: 20000,
            inheritCosts: {
                special: 6000,
                turntime: 2500,
                city: 1000,
                stat: 1000,
            },
            turnTermMinutes: 60,
            cities: [{ id: 10, name: '낙양', region: '사예' }],
            availableSpecialWar: {
                che_귀병: { title: '귀병', info: '계략 특기' },
            },
            geniusRemaining: 5,
        });
        apiMocks.mapPreview.mockReset().mockResolvedValue({ nations: [] });
        frontInfoState.hasGeneral = true;
    });

    it('이미 등록된 장수가 있으면 장수 등록 폼에서 현재 서버 게임으로 바로 입장한다', async () => {
        render(<JoinPage />);

        await waitFor(() => {
            expect(replaceMock).toHaveBeenCalledWith('/game/s1');
        });
        expect(pushMock).not.toHaveBeenCalledWith('/lobby');
    });

    it('정치와 매력을 포함한 다섯 능력치를 합계 275로 제출한다', async () => {
        frontInfoState.hasGeneral = false;
        apiMocks.join.mockResolvedValue({ status: 'BLOCKED', reason: '테스트 종료' });
        render(<JoinPage />);

        const sliders = screen.getAllByRole('slider');
        expect(sliders).toHaveLength(5);
        fireEvent.change(sliders[3], { target: { value: '60' } });
        fireEvent.change(sliders[4], { target: { value: '50' } });
        fireEvent.click(screen.getByRole('button', { name: '장수 생성' }));

        await waitFor(() => {
            expect(apiMocks.join).toHaveBeenCalledWith(expect.objectContaining({
                leadership: 55,
                strength: 55,
                intel: 55,
                politics: 60,
                charm: 50,
            }));
        });
    });

    it('유산과 계정 초상을 실제 선택해 join 요청에 전달한다', async () => {
        frontInfoState.hasGeneral = false;
        apiMocks.join.mockResolvedValue({ status: 'BLOCKED', reason: '테스트 종료' });
        render(<JoinPage />);

        await waitFor(() => {
            expect(screen.getByRole('img', { name: '전콘' })).toHaveAttribute('src', expect.stringContaining('/custom.jpg'));
        });
        fireEvent.click(await screen.findByRole('checkbox', { name: /보이기/ }));
        fireEvent.change(screen.getByLabelText('천재로 생성'), { target: { value: 'che_귀병' } });
        fireEvent.change(screen.getByLabelText('도시'), { target: { value: '10' } });
        expect(screen.getByRole('option', { name: '12:00.000 ~ 12:59.999' })).toBeInTheDocument();
        fireEvent.change(screen.getByLabelText('턴 시간 지정'), { target: { value: '12' } });
        const bonus = screen.getAllByLabelText(/추가 능력치/);
        fireEvent.change(bonus[0], { target: { value: '3' } });
        fireEvent.change(bonus[1], { target: { value: '1' } });
        fireEvent.change(bonus[2], { target: { value: '1' } });
        expect(screen.getByRole('checkbox', { name: '사용' })).toBeEnabled();
        fireEvent.click(screen.getByRole('button', { name: '장수 생성' }));

        await waitFor(() => {
            expect(apiMocks.join).toHaveBeenCalledWith(expect.objectContaining({
                pic: true,
                inheritSpecial: 'che_귀병',
                inheritCity: 10,
                inheritTurntimeZone: 12,
                inheritBonusStat: [3, 1, 1],
            }));
        });
    });
});
