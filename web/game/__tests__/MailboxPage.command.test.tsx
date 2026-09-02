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
    result: {
        ok: true;
        requestId: string;
        result: { msgType: 'private'; recipientId: number; recipientName: string };
        status: 'RESOLVED';
        type: 'sendMessage';
    };
};

describe('MailboxPage command reservation', () => {
    beforeEach(() => {
        mocks.contacts.mockResolvedValue({
            nation: [
                { mailbox: 9001, name: '촉', general: [[10, '유비', 4], [7, '관우', 0]] },
                { mailbox: 9002, name: '위', general: [[20, '조조', 0]] },
            ],
        });
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
        expect(screen.getByRole('button', { name: '발송' })).toBeDisabled();

        fireEvent.change(screen.getByRole('searchbox', { name: '수신 장수 검색' }), { target: { value: '관' } });
        expect(screen.queryByRole('option', { name: '유비' })).toBeNull();
        fireEvent.change(screen.getByRole('combobox', { name: '개인 수신 장수' }), { target: { value: '7' } });
        expect(screen.getByText('수신자: 관우 (7)')).toBeInTheDocument();

        fireEvent.change(editor, { target: { value: '서식 메시지' } });
        fireEvent.click(screen.getByRole('button', { name: '발송' }));

        await waitFor(() => expect(mocks.sendMessage).toHaveBeenCalledWith({
            mailbox: 7,
            text: '<p>서식 메시지</p>',
        }, 10));
        expect(screen.queryByText('서신을 발송했습니다.')).toBeNull();

        // 엔진 결과를 기다리는 동안 읽기 탭을 바꿔도 발송 시점의 개인 수신자 확인을 잃지 않는다.
        fireEvent.click(screen.getByRole('button', { name: '전체' }));
        await waitFor(() => expect(mocks.mailboxRecent).toHaveBeenCalledTimes(2));

        if (resolveResult === undefined) throw new Error('result resolver was not initialized');
        resolveResult({
            status: 'applied',
            result: {
                ok: true,
                requestId: 'mail-1',
                result: { msgType: 'private', recipientId: 7, recipientName: '관우' },
                status: 'RESOLVED',
                type: 'sendMessage',
            },
        });

        expect(await screen.findByText('관우 (7)에게 서신을 발송했습니다.')).toBeInTheDocument();
        expect(mocks.mailboxRecent).toHaveBeenCalledTimes(2);
    });

    it('개인 수신자 미선택과 자기 자신을 화면에서 차단한다', async () => {
        render(<MailboxPage />);

        const editor = await screen.findByRole('textbox', { name: '서신 내용' });
        await waitFor(() => expect(editor).not.toBeDisabled());
        fireEvent.change(editor, { target: { value: '안녕' } });

        expect(screen.getByRole('button', { name: '발송' })).toBeDisabled();
        expect(screen.queryByRole('option', { name: '유비' })).toBeNull();
        expect(mocks.sendMessage).not.toHaveBeenCalled();
    });

    it('개인 수신자 ID를 공개·국가·외교 메일함 주소로 재사용하지 않는다', async () => {
        mocks.submitCommandAndAwaitResult.mockImplementation(async (submit: () => Promise<unknown>) => {
            await submit();
            return {
                status: 'applied',
                result: { ok: true, requestId: 'mail', result: {}, status: 'RESOLVED', type: 'sendMessage' },
            };
        });
        render(<MailboxPage />);

        const editor = await screen.findByRole('textbox', { name: '서신 내용' });
        await waitFor(() => expect(editor).not.toBeDisabled());
        fireEvent.change(screen.getByRole('combobox', { name: '개인 수신 장수' }), { target: { value: '7' } });

        fireEvent.click(screen.getByRole('button', { name: '전체' }));
        fireEvent.change(editor, { target: { value: '전체 공지' } });
        fireEvent.click(screen.getByRole('button', { name: '발송' }));
        await waitFor(() => expect(mocks.sendMessage).toHaveBeenLastCalledWith({
            mailbox: 9999,
            text: '<p>전체 공지</p>',
        }, 10));

        fireEvent.click(screen.getByRole('button', { name: '외교' }));
        fireEvent.change(screen.getByRole('combobox', { name: '외교 수신 국가' }), { target: { value: '9002' } });
        fireEvent.change(editor, { target: { value: '외교 서신' } });
        fireEvent.click(screen.getByRole('button', { name: '발송' }));
        await waitFor(() => expect(mocks.sendMessage).toHaveBeenLastCalledWith({
            mailbox: 9002,
            text: '<p>외교 서신</p>',
        }, 10));
    });

    it('외교권이 없으면 타국 서신함을 발송 대상으로 노출하지 않는다', async () => {
        mocks.contacts.mockResolvedValue({
            nation: [
                { mailbox: 9001, name: '촉', general: [[10, '유비', 0], [7, '관우', 0]] },
                { mailbox: 9002, name: '위', general: [[20, '조조', 0]] },
            ],
        });
        render(<MailboxPage />);

        const editor = await screen.findByRole('textbox', { name: '서신 내용' });
        await waitFor(() => expect(editor).not.toBeDisabled());
        await screen.findByRole('option', { name: '관우' });
        fireEvent.click(screen.getByRole('button', { name: '외교' }));

        expect(screen.getByText('외교 권한이 없습니다.')).toBeInTheDocument();
        expect(screen.queryByRole('combobox', { name: '외교 수신 국가' })).toBeNull();
        expect(screen.getByRole('button', { name: '발송' })).toBeDisabled();
    });

    it('개인 발송 결과의 유형·ID·이름이 누락되거나 다르면 성공으로 확정하지 않는다', async () => {
        mocks.submitCommandAndAwaitResult.mockImplementation(async (submit: () => Promise<unknown>) => {
            await submit();
            return {
                status: 'applied',
                result: {
                    ok: true,
                    requestId: 'mail-mismatch',
                    result: { msgType: 'private', recipientId: 20, recipientName: '조조' },
                    status: 'RESOLVED',
                    type: 'sendMessage',
                },
            };
        });
        render(<MailboxPage />);

        const editor = await screen.findByRole('textbox', { name: '서신 내용' });
        await waitFor(() => expect(editor).not.toBeDisabled());
        fireEvent.change(screen.getByRole('combobox', { name: '개인 수신 장수' }), { target: { value: '7' } });
        fireEvent.change(editor, { target: { value: '확인 필요' } });
        fireEvent.click(screen.getByRole('button', { name: '발송' }));

        expect(await screen.findByText('서신은 처리되었지만 수신자 확인에 실패했습니다.')).toBeInTheDocument();
        expect(editor).toHaveValue('<p>확인 필요</p>');
        expect(mocks.mailboxRecent).toHaveBeenCalledTimes(1);
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
