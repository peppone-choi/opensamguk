import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MailboxPage from '@/app/game/mailbox/page';

const mocks = vi.hoisted(() => ({
    contacts: vi.fn(),
    frontInfo: vi.fn(),
    mailboxRecent: vi.fn(),
    readLatestMessage: vi.fn(),
    sendMessage: vi.fn(),
    submitCommandAndAwaitResult: vi.fn(),
}));

vi.mock('@/lib/api', async importOriginal => {
    const actual = await importOriginal<typeof import('@/lib/api')>();
    Object.assign(actual.api, {
        contacts: mocks.contacts,
        frontInfo: mocks.frontInfo,
        mailboxRecent: mocks.mailboxRecent,
        commands: {
            readLatestMessage: mocks.readLatestMessage,
            sendMessage: mocks.sendMessage,
        },
    });
    return actual;
});

vi.mock('@/lib/commandSubmit', () => ({
    submitCommandAndAwaitResult: mocks.submitCommandAndAwaitResult,
}));

vi.mock('@/components/RichTextEditor', () => ({
    countHtmlCodePoints: (html: string) => Array.from(html).length,
    RichTextEditor: ({
        ariaLabel,
        disabled,
        onChange,
        value,
    }: {
        ariaLabel: string;
        disabled?: boolean;
        onChange: (html: string) => void;
        value: string;
    }) => (
        <textarea
            aria-label={ariaLabel}
            disabled={disabled}
            onChange={event => onChange(`<p>${event.target.value}</p>`)}
            value={value}
        />
    ),
}));

vi.mock('@/components/Shell', () => ({
    default: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

vi.mock('@/components/GameCard', () => ({
    default: ({ children }: { children: React.ReactNode }) => <section>{children}</section>,
}));

vi.mock('@/components/StatusBadge', () => ({
    default: ({ children }: { children: React.ReactNode }) => <span>{children}</span>,
}));

class EventSourceStub {
    onerror: (() => void) | null = null;

    addEventListener(): void {}

    close(): void {}
}

Object.defineProperty(globalThis, 'EventSource', {
    value: EventSourceStub,
    configurable: true,
});

const emptyMailbox = {
    diplomacy: [],
    national: [],
    private: [],
    public: [],
    sequence: 1,
};

type AppliedSendResult = {
    status: 'applied';
    result: { ok: true; requestId: string; result: Record<string, never>; status: 'RESOLVED'; type: 'sendMessage' };
};

describe('MailboxPage command reservation', () => {
    beforeEach(() => {
        mocks.contacts.mockResolvedValue({ nation: [] });
        mocks.frontInfo.mockResolvedValue({ general: { generalId: 10, nationId: 1 } });
        mocks.mailboxRecent.mockResolvedValue(emptyMailbox);
        mocks.readLatestMessage.mockResolvedValue({ status: 'AVAILABLE', requestId: 'read-latest' });
        mocks.sendMessage.mockResolvedValue({ status: 'AVAILABLE', requestId: 'mail-1' });
        mocks.submitCommandAndAwaitResult.mockReset();
    });

    afterEach(() => {
        vi.clearAllMocks();
    });

    it('sends serialized rich text and shows success only after the command result is applied', async () => {
        let resolveResult: ((value: AppliedSendResult) => void) | undefined;
        const result = new Promise<AppliedSendResult>(resolve => {
            resolveResult = resolve;
        });
        mocks.submitCommandAndAwaitResult.mockImplementation(async (submit: () => Promise<unknown>) => {
            await submit();
            return result;
        });

        render(<MailboxPage />);

        const editor = await screen.findByRole('textbox', { name: '서신 내용' });
        await waitFor(() => expect(editor).not.toBeDisabled());
        fireEvent.change(editor, { target: { value: '서식 메시지' } });
        fireEvent.click(screen.getByRole('button', { name: '발송' }));

        await waitFor(() => expect(mocks.sendMessage).toHaveBeenCalledWith({
            mailbox: 10,
            text: '<p>서식 메시지</p>',
        }, 10));
        expect(screen.queryByText('서신을 발송했습니다.')).toBeNull();

        if (resolveResult === undefined) throw new Error('result resolver was not initialized');
        resolveResult({
            status: 'applied',
            result: {
                ok: true,
                requestId: 'mail-1',
                result: {},
                status: 'RESOLVED',
                type: 'sendMessage',
            },
        });

        expect(await screen.findByText('서신을 발송했습니다.')).toBeInTheDocument();
    });

    it('does not send serialized rich text beyond the raw 500-code-point limit', async () => {
        render(<MailboxPage />);

        const editor = await screen.findByRole('textbox', { name: '서신 내용' });
        await waitFor(() => expect(editor).not.toBeDisabled());
        fireEvent.change(editor, { target: { value: '가'.repeat(501) } });

        const sendButton = screen.getByRole('button', { name: '발송' });
        await waitFor(() => expect(sendButton).toBeDisabled());
        fireEvent.click(sendButton);

        expect(mocks.sendMessage).not.toHaveBeenCalled();
        expect(mocks.submitCommandAndAwaitResult).not.toHaveBeenCalled();
    });
});
