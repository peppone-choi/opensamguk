import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import CommandModal from '@/components/CommandModal';

const mocks = vi.hoisted(() => ({
    command: vi.fn(),
    nationBulk: vi.fn(),
    pollCommandResultResponse: vi.fn(),
}));

vi.mock('@/lib/api', async importOriginal => {
    const actual = await importOriginal<typeof import('@/lib/api')>();
    return {
        ...actual,
        api: {
            ...actual.api,
            command: mocks.command,
            commandQueue: {
                ...actual.api.commandQueue,
                nationBulk: mocks.nationBulk,
            },
        },
        pollCommandResultResponse: mocks.pollCommandResultResponse,
    };
});

function renderModal(isNationCommand = false) {
    const onClose = vi.fn();
    const onReserved = vi.fn();
    const onToast = vi.fn();

    render(
        <CommandModal
            generalId={7}
            turnIdx={2}
            onClose={onClose}
            onReserved={onReserved}
            onToast={onToast}
            pinnedCommand="che_test"
            pinnedLabel="시험"
            pinnedArgType={null}
            isNationCommand={isNationCommand}
        />,
    );

    return { onClose, onReserved, onToast };
}

describe('CommandModal terminal result handling', () => {
    beforeEach(() => {
        mocks.command.mockReset();
        mocks.nationBulk.mockReset();
        mocks.pollCommandResultResponse.mockReset();
    });

    it('shows success only after a general command terminal result is applied', async () => {
        mocks.command.mockResolvedValueOnce({ status: 'AVAILABLE', requestId: 'general-applied' });
        mocks.pollCommandResultResponse.mockResolvedValueOnce({
            status: 'RESOLVED',
            requestId: 'general-applied',
            ok: true,
            type: 'executionApplied',
            result: { commandKind: 'RESERVED_TURN' },
        });
        const { onClose, onReserved, onToast } = renderModal();

        fireEvent.click(screen.getByRole('button', { name: '예약' }));

        await waitFor(() => expect(mocks.command).toHaveBeenCalledWith('che_test', {}, 7, 2));
        await waitFor(() => expect(mocks.pollCommandResultResponse).toHaveBeenCalledWith('general-applied', expect.any(AbortSignal)));

        expect(onToast).toHaveBeenCalledWith('시험 명령이 실행되었습니다.', 'success');
        expect(onReserved).toHaveBeenCalledOnce();
        expect(onClose).toHaveBeenCalledOnce();
    });

    it('renders the exact engine rejection reason without closing a general command modal', async () => {
        const reason = '엔진에서 거절했습니다.';
        mocks.command.mockResolvedValueOnce({ status: 'AVAILABLE', requestId: 'general-rejected' });
        mocks.pollCommandResultResponse.mockResolvedValueOnce({
            status: 'RESOLVED',
            requestId: 'general-rejected',
            ok: false,
            type: 'che_test',
            reason,
            result: {},
        });
        const { onClose, onReserved, onToast } = renderModal();

        fireEvent.click(screen.getByRole('button', { name: '예약' }));

        expect(await screen.findByText(reason)).toBeInTheDocument();
        expect(onToast).not.toHaveBeenCalled();
        expect(onReserved).not.toHaveBeenCalled();
        expect(onClose).not.toHaveBeenCalled();
    });

    it('renders 처리 지연 and keeps a general command modal open when its result stays pending', async () => {
        mocks.command.mockResolvedValueOnce({ status: 'AVAILABLE', requestId: 'general-pending' });
        mocks.pollCommandResultResponse.mockResolvedValueOnce(null);
        const { onClose, onReserved, onToast } = renderModal();

        fireEvent.click(screen.getByRole('button', { name: '예약' }));

        expect(await screen.findByText('처리 지연')).toBeInTheDocument();
        expect(onToast).not.toHaveBeenCalled();
        expect(onReserved).not.toHaveBeenCalled();
        expect(onClose).not.toHaveBeenCalled();
    });

    it('reports the real nation queue mutation as reserved instead of executed', async () => {
        mocks.nationBulk.mockResolvedValueOnce({ status: 'AVAILABLE', requestId: 'nation-applied' });
        mocks.pollCommandResultResponse.mockResolvedValueOnce({
            status: 'RESOLVED',
            requestId: 'nation-applied',
            ok: true,
            type: 'queueMutation',
            result: { commandKind: 'QUEUE_MUTATION' },
        });
        const { onClose, onReserved, onToast } = renderModal(true);

        fireEvent.click(screen.getByRole('button', { name: '예약' }));

        await waitFor(() =>
            expect(mocks.nationBulk).toHaveBeenCalledWith(7, [
                { action: 'che_test', turnList: [2], arg: {} },
            ]),
        );
        await waitFor(() => expect(mocks.pollCommandResultResponse).toHaveBeenCalledWith('nation-applied', expect.any(AbortSignal)));

        expect(onToast).toHaveBeenCalledWith('시험 명령이 예약되었습니다.', 'success');
        expect(onReserved).toHaveBeenCalledOnce();
        expect(onClose).toHaveBeenCalledOnce();
    });
});
