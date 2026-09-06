import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import MessagePanel from '@/components/game/MessagePanel';

const mocks = vi.hoisted(() => ({
    mailbox: vi.fn(),
    sendMessage: vi.fn(),
    submitCommandAndAwaitResult: vi.fn(),
}));

vi.mock('@/lib/api', () => ({
    api: {
        mailbox: mocks.mailbox,
        commands: {
            sendMessage: mocks.sendMessage,
        },
    },
}));

vi.mock('@/lib/commandSubmit', () => ({
    submitCommandAndAwaitResult: mocks.submitCommandAndAwaitResult,
}));

vi.mock('@/components/game/MessagePlate', () => ({
    default: () => <article />,
}));

describe('MessagePanel', () => {
    beforeEach(() => {
        mocks.mailbox.mockReset();
        mocks.sendMessage.mockReset();
        mocks.submitCommandAndAwaitResult.mockReset();
    });

    it('defaults to the caller nation mailbox like legacy MessagePanel', async () => {
        mocks.mailbox.mockResolvedValueOnce([]);

        render(<MessagePanel generalId={10} nationId={1} onToast={vi.fn()} />);

        await waitFor(() => expect(mocks.mailbox).toHaveBeenCalledWith(9001));
        expect(screen.getByRole('tab', { name: '국가 메시지' })).toHaveAttribute('aria-selected', 'true');
        expect(screen.getByRole('tab', { name: '전체 메시지' })).toHaveAttribute('aria-selected', 'false');
    });

    it('clears and reloads a sent message only after an applied result', async () => {
        mocks.mailbox.mockResolvedValue([]);
        mocks.sendMessage.mockResolvedValue({ status: 'AVAILABLE', requestId: 'send-1' });
        mocks.submitCommandAndAwaitResult.mockImplementation(async (submit: () => Promise<unknown>) => {
            await submit();
            return {
                status: 'applied',
                result: { status: 'RESOLVED', requestId: 'send-1', ok: true, type: 'sendMessage', result: {} },
            };
        });
        const onToast = vi.fn();

        render(<MessagePanel generalId={10} nationId={1} onToast={onToast} />);
        await waitFor(() => expect(mocks.mailbox).toHaveBeenCalledWith(9001));
        fireEvent.change(screen.getByPlaceholderText('서신을 입력하세요'), { target: { value: '전달문' } });
        const mailboxCallsBefore = mocks.mailbox.mock.calls.length;
        fireEvent.click(screen.getByRole('button', { name: '서신전달&갱신' }));

        await waitFor(() => expect(mocks.sendMessage).toHaveBeenCalledWith({ mailbox: 9001, text: '전달문' }, 10));
        await waitFor(() => expect(onToast).toHaveBeenCalledWith('서신을 접수했습니다.', 'success'));
        expect(screen.getByPlaceholderText('서신을 입력하세요')).toHaveValue('');
        await waitFor(() => expect(mocks.mailbox.mock.calls.length).toBeGreaterThan(mailboxCallsBefore));
    });

    it('keeps the draft when a sent message is still pending', async () => {
        mocks.mailbox.mockResolvedValue([]);
        mocks.sendMessage.mockResolvedValue({ status: 'AVAILABLE', requestId: 'send-pending' });
        mocks.submitCommandAndAwaitResult.mockImplementation(async (submit: () => Promise<unknown>) => {
            await submit();
            return { status: 'pending', reason: '처리 지연' };
        });
        const onToast = vi.fn();

        render(<MessagePanel generalId={10} nationId={1} onToast={onToast} />);
        await waitFor(() => expect(mocks.mailbox).toHaveBeenCalledWith(9001));
        fireEvent.change(screen.getByPlaceholderText('서신을 입력하세요'), { target: { value: '전달문' } });
        fireEvent.click(screen.getByRole('button', { name: '서신전달&갱신' }));

        await waitFor(() => expect(onToast).toHaveBeenCalledWith('처리 지연', 'info'));
        expect(screen.getByPlaceholderText('서신을 입력하세요')).toHaveValue('전달문');
    });

    it('surfaces a resolved engine denial reason verbatim', async () => {
        mocks.mailbox.mockResolvedValue([]);
        mocks.sendMessage.mockResolvedValue({ status: 'AVAILABLE', requestId: 'send-denied' });
        mocks.submitCommandAndAwaitResult.mockImplementation(async (submit: () => Promise<unknown>) => {
            await submit();
            return { status: 'rejected', reason: '서신을 보낼 수 없는 대상입니다.' };
        });
        const onToast = vi.fn();

        render(<MessagePanel generalId={10} nationId={1} onToast={onToast} />);
        await waitFor(() => expect(mocks.mailbox).toHaveBeenCalledWith(9001));
        fireEvent.change(screen.getByPlaceholderText('서신을 입력하세요'), { target: { value: '전달문' } });
        fireEvent.click(screen.getByRole('button', { name: '서신전달&갱신' }));

        await waitFor(() => expect(onToast).toHaveBeenCalledWith('서신을 보낼 수 없는 대상입니다.', 'error'));
        expect(screen.getByPlaceholderText('서신을 입력하세요')).toHaveValue('전달문');
    });
});
