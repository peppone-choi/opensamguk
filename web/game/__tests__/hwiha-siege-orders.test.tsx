import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import SiegePage from '@/app/game/hwiha/siege/page';
import OrdersPage from '@/app/game/hwiha/orders/page';

const mock = vi.hoisted(() => ({
    hwihaSieges: vi.fn(), hwihaRetinue: vi.fn(), hwihaWarehouses: vi.fn(),
    reservedCommands: vi.fn(), command: vi.fn(), courtReward: vi.fn(),
    submit: vi.fn(), refresh: vi.fn(),
}));
vi.mock('@/components/HwihaShell', () => ({ default: ({ children }: { children: ReactNode }) => <div>{children}</div> }));
vi.mock('@/components/command/HwihaCourtForm', () => ({ default: () => <div>발령 폼</div> }));
vi.mock('@/lib/hwiha-session', () => ({ useHwihaSession: () => ({
    generalId: 9, isHwihaWorld: true, frontInfo: { global: { year: 190, month: 1, turnPhase: 1 } }, refresh: mock.refresh,
}) }));
vi.mock('@/lib/api', () => ({ api: {
    hwihaSieges: mock.hwihaSieges, hwihaRetinue: mock.hwihaRetinue, hwihaWarehouses: mock.hwihaWarehouses,
    reservedCommands: mock.reservedCommands, command: mock.command, courtReward: mock.courtReward,
} }));
vi.mock('@/lib/commandSubmit', () => ({ submitCommandAndAwaitResult: mock.submit }));

const siege = {
    countyId: 12, countyName: '초현', status: 'ACTIVE', endReason: null,
    besieger: { generalId: 9, name: '장수', nationId: 1, nationName: '위' },
    defenderNationId: 2, defenderNationName: '원', startedAt: { year: 190, month: 1, phase: 1 },
    turns: 3, grain: 500, morale: 4200, garrison: 180, trust: 30,
    countySupplied: false, besiegerTroops: 600, besiegerFed: true, canAct: true,
    surrenderDemandAccepted: true, timeline: [{ year: 190, month: 1, phase: 1, event: 'START', morale: 5000, garrison: 180 }],
};

describe('휘하 공성·상사 화면', () => {
    beforeEach(() => {
        vi.clearAllMocks();
        mock.hwihaSieges.mockResolvedValue({ status: 'READY', sieges: [siege] });
        mock.reservedCommands.mockResolvedValue({ slots: [{ turnIdx: 0 }] });
        mock.command.mockResolvedValue({ status: 'AVAILABLE', requestId: 'r1' });
        mock.courtReward.mockResolvedValue({ status: 'AVAILABLE', requestId: 'r2' });
        mock.submit.mockImplementation(async (send: () => Promise<unknown>) => { await send(); return { status: 'reserved' }; });
        mock.hwihaRetinue.mockResolvedValue({ status: 'READY', people: [{ retainerId: 31, generalId: 55, name: '문관', loyalty: 60, locationCityId: 12 }] });
        mock.hwihaWarehouses.mockResolvedValue({ status: 'READY', warehouses: [
            { cityId: 12, supplied: true, stock: { money: 100 } },
            { cityId: 13, supplied: true, stock: { money: 50 } },
        ] });
    });

    it('shows real siege state and reserves an action in the first open turn', async () => {
        render(<SiegePage />);
        expect(await screen.findByText('초현')).toBeInTheDocument();
        expect(screen.getByText(/항복 권고 조건.*수락 가능/)).toBeInTheDocument();
        expect(screen.getByText(/포위 시작/)).toBeInTheDocument();
        await userEvent.click(screen.getByRole('button', { name: '강공 예약' }));
        await waitFor(() => expect(mock.command).toHaveBeenCalledWith('action.assault', {}, 9, 1));
    });

    it('disables orders for a noncommander and shows a server execution rejection', async () => {
        mock.hwihaSieges.mockResolvedValueOnce({ status: 'READY', sieges: [{ ...siege, canAct: false }] });
        const view = render(<SiegePage />);
        expect(await screen.findByText('초현')).toBeInTheDocument();
        expect(screen.getByRole('button', { name: '강공 예약' })).toBeDisabled();
        view.unmount();
        mock.submit.mockResolvedValueOnce({ status: 'rejected', reason: 'UNIT_UNAVAILABLE', result: { result: { code: 'UNIT_UNAVAILABLE' } } });
        render(<SiegePage />);
        expect(await screen.findByText('초현')).toBeInTheDocument();
        await userEvent.click(screen.getByRole('button', { name: '강공 예약' }));
        expect(await screen.findByRole('alert')).toHaveTextContent('쓸 수 없는 병종');
    });

    it('shows an empty state when the server has no siege', async () => {
        mock.hwihaSieges.mockResolvedValueOnce({ status: 'READY', sieges: [] });
        render(<SiegePage />);
        expect(await screen.findByText('관여한 포위가 없습니다.')).toBeInTheDocument();
        expect(screen.queryByRole('button', { name: '강공 예약' })).not.toBeInTheDocument();
    });

    it('explains when a queued siege order finds no active siege at execution', async () => {
        mock.submit.mockResolvedValueOnce({ status: 'rejected', reason: 'NOT_BESIEGING', result: { result: { code: 'NOT_BESIEGING' } } });
        render(<SiegePage />);
        expect(await screen.findByText('초현')).toBeInTheDocument();
        await userEvent.click(screen.getByRole('button', { name: '항복 권고 예약' }));
        expect(await screen.findByRole('alert')).toHaveTextContent('지휘 중인 포위가 없습니다.');
    });

    it('limits reward to the selected person card network balance', async () => {
        render(<OrdersPage />);
        await userEvent.selectOptions(await screen.findByLabelText('상사 대상'), '31');
        expect(screen.getByText(/사용 가능한 창고망 금: 150/)).toBeInTheDocument();
        await userEvent.type(screen.getByLabelText('상사 금액'), '151');
        expect(screen.getByRole('button', { name: '상사 접수' })).toBeDisabled();
        await userEvent.clear(screen.getByLabelText('상사 금액'));
        await userEvent.type(screen.getByLabelText('상사 금액'), '100');
        await userEvent.click(screen.getByRole('button', { name: '상사 접수' }));
        await waitFor(() => expect(mock.courtReward).toHaveBeenCalledWith(9, { retainerId: 31, money: 100 }));
    });
});
