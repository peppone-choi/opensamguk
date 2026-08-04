import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import SelectPoolPage from '@/app/game/select-pool/page';

const mocks = vi.hoisted(() => ({
    selectPool: vi.fn(),
    selectPoolPick: vi.fn(),
    selectPoolUpdate: vi.fn(),
    submitCommandAndAwaitResult: vi.fn(),
}));

vi.mock('@/components/Shell', () => ({
    default: ({ children }: { children: ReactNode }) => <div>{children}</div>,
}));

vi.mock('@/components/GameCard', () => ({
    default: ({ children }: { children: ReactNode }) => <section>{children}</section>,
}));

vi.mock('@/lib/api', () => ({
    api: {
        selectPool: mocks.selectPool,
        commands: {
            selectPoolPick: mocks.selectPoolPick,
            selectPoolUpdate: mocks.selectPoolUpdate,
        },
    },
}));

vi.mock('@/lib/commandSubmit', () => ({
    submitCommandAndAwaitResult: mocks.submitCommandAndAwaitResult,
}));

const candidate = {
    uniqueName: '청룡',
    generalName: '마초',
    picture: null,
    imageServer: 0,
    leadership: 91,
    strength: 97,
    intel: 74,
    politics: 44,
    charm: 88,
    dex: [1000, 2000, 3000, 4000, 5000],
    personality: 'che_의리',
    specialDomestic: null,
    specialWar: null,
    statEditable: false,
};

function pool(generalId: number | null) {
    return {
        result: true,
        generalId,
        validUntil: '2026-07-10T03:02:00Z',
        pick: [candidate],
    };
}

function expectedPayload() {
    return {
        uniqueName: '청룡',
        leadership: undefined,
        strength: undefined,
        intel: undefined,
        personalityName: undefined,
        useOwnPicture: false,
    };
}

async function renderPool(generalId: number | null) {
    mocks.selectPool.mockResolvedValue(pool(generalId));
    render(<SelectPoolPage />);

    await waitFor(() => {
        expect(
            screen.getByRole('button', { name: generalId == null ? '마초 선택' : '마초로 변경' }),
        ).toBeInTheDocument();
    });
}

describe('SelectPoolPage terminal command results', () => {
    beforeEach(() => {
        mocks.selectPool.mockReset();
        mocks.selectPoolPick.mockReset();
        mocks.selectPoolUpdate.mockReset();
        mocks.submitCommandAndAwaitResult.mockReset();
    });

    it('surfaces the PHP-fatal 500 without reloading after a pick attempt', async () => {
        mocks.selectPool.mockResolvedValue({
            ...pool(null),
            pick: [{ ...candidate, statEditable: true }],
            customOptions: {
                stat: true,
                personality: true,
                picture: true,
                personalities: [{ code: 'che_의협', name: '의협' }],
            },
            member: { name: '테스터', canUsePicture: true },
        });
        mocks.selectPoolPick.mockRejectedValue(new Error('500: Internal Server Error'));
        mocks.submitCommandAndAwaitResult.mockImplementation(async (submit: () => Promise<unknown>) => submit());
        render(<SelectPoolPage />);

        await waitFor(() => expect(screen.getByRole('button', { name: '마초 선택' })).toBeInTheDocument());
        const loadCallsBefore = mocks.selectPool.mock.calls.length;
        fireEvent.change(screen.getByLabelText('마초 통솔'), { target: { value: '60' } });
        fireEvent.change(screen.getByLabelText('마초 성격'), { target: { value: 'che_의협' } });
        fireEvent.click(screen.getByLabelText('마초 내 이미지 사용'));
        fireEvent.click(screen.getByRole('button', { name: '마초 선택' }));

        await waitFor(() => expect(mocks.selectPoolPick).toHaveBeenCalledWith({
            uniqueName: '청룡',
            leadership: 60,
            strength: 60,
            intel: 60,
            personalityName: 'che_의협',
            useOwnPicture: true,
        }, 0));
        await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('500: Internal Server Error'));
        expect(screen.queryByRole('status')).not.toBeInTheDocument();
        expect(mocks.selectPool.mock.calls.length).toBe(loadCallsBefore);
    });

    it('shows update success and reloads only after an applied terminal result', async () => {
        mocks.selectPoolUpdate.mockResolvedValue({ status: 'AVAILABLE', requestId: 'update-1' });
        mocks.submitCommandAndAwaitResult.mockImplementation(async (submit: () => Promise<unknown>) => {
            await submit();
            return {
                status: 'applied',
                result: { status: 'RESOLVED', requestId: 'update-1', ok: true, type: 'selectPoolUpdate', result: {} },
            };
        });
        await renderPool(77);

        const loadCallsBefore = mocks.selectPool.mock.calls.length;
        fireEvent.click(screen.getByRole('button', { name: '마초로 변경' }));

        await waitFor(() => expect(mocks.selectPoolUpdate).toHaveBeenCalledWith(expectedPayload(), 77));
        await waitFor(() => expect(mocks.submitCommandAndAwaitResult).toHaveBeenCalledTimes(1));
        await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('변경이 처리되었습니다.'));
        await waitFor(() => expect(mocks.selectPool.mock.calls.length).toBeGreaterThan(loadCallsBefore));
    });

    it('shows the update terminal denial reason verbatim without reloading', async () => {
        mocks.selectPoolUpdate.mockResolvedValue({ status: 'AVAILABLE', requestId: 'update-denied' });
        mocks.submitCommandAndAwaitResult.mockImplementation(async (submit: () => Promise<unknown>) => {
            await submit();
            return { status: 'rejected', reason: '변경할 수 없는 상태입니다.' };
        });
        await renderPool(77);

        const loadCallsBefore = mocks.selectPool.mock.calls.length;
        fireEvent.click(screen.getByRole('button', { name: '마초로 변경' }));

        await waitFor(() => expect(mocks.selectPoolUpdate).toHaveBeenCalledWith(expectedPayload(), 77));
        await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('변경할 수 없는 상태입니다.'));
        expect(screen.queryByRole('status')).not.toBeInTheDocument();
        expect(mocks.selectPool.mock.calls.length).toBe(loadCallsBefore);
    });

    it('shows 처리 지연 for a pending update without reloading', async () => {
        mocks.selectPoolUpdate.mockResolvedValue({ status: 'AVAILABLE', requestId: 'update-pending' });
        mocks.submitCommandAndAwaitResult.mockImplementation(async (submit: () => Promise<unknown>) => {
            await submit();
            return { status: 'pending', reason: '처리 지연' };
        });
        await renderPool(77);

        const loadCallsBefore = mocks.selectPool.mock.calls.length;
        fireEvent.click(screen.getByRole('button', { name: '마초로 변경' }));

        await waitFor(() => expect(mocks.selectPoolUpdate).toHaveBeenCalledWith(expectedPayload(), 77));
        await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('처리 지연'));
        expect(screen.queryByRole('status')).not.toBeInTheDocument();
        expect(mocks.selectPool.mock.calls.length).toBe(loadCallsBefore);
    });
});
