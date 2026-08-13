import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import DiplomacyPage from '@/app/game/diplomacy/page';

const mocks = vi.hoisted(() => ({
    diplomacyLetters: vi.fn(),
    refresh: vi.fn(),
    diploSendLetter: vi.fn(),
    diploRespondLetter: vi.fn(),
    submitCommandAndAwaitResult: vi.fn(),
    commandModalProps: [] as Array<Record<string, unknown>>,
}));

vi.mock('@/hooks/useFrontInfo', () => ({
    useFrontInfo: () => ({
        frontInfo: {
            general: {
                generalId: 10,
                nationId: 1,
                permission: 5,
            },
        },
        refresh: mocks.refresh,
    }),
}));

vi.mock('@/lib/api', () => ({
    api: {
        diplomacyLetters: mocks.diplomacyLetters,
        commands: {
            diploSendLetter: mocks.diploSendLetter,
            diploRollbackLetter: vi.fn(),
            diploDestroyLetter: vi.fn(),
            diploRespondLetter: mocks.diploRespondLetter,
        },
    },
}));

vi.mock('@/lib/commandSubmit', () => ({
    submitCommandAndAwaitResult: mocks.submitCommandAndAwaitResult,
}));

vi.mock('@/components/CommandModal', () => ({
    default: (props: Record<string, unknown>) => {
        mocks.commandModalProps.push(props);
        return <div data-testid="command-modal" />;
    },
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

vi.mock('@/components/RichTextEditor', () => ({
    countHtmlCodePoints: (html: string) => Array.from(html).length,
    RichTextEditor: ({
        ariaLabel,
        onChange,
        value,
    }: {
        ariaLabel: string;
        onChange: (html: string) => void;
        value: string;
    }) => (
        <textarea
            aria-label={ariaLabel}
            onChange={event => onChange(`<p>${event.target.value}</p>`)}
            value={value}
        />
    ),
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

const receivedProposedLetter = {
    no: 7,
    src: { nationID: 2, nationName: '위', nationColor: '#3355aa', generalName: '조조', generalIcon: null },
    dest: { nationID: 1, nationName: '촉', nationColor: '#228833', generalName: null, generalIcon: null },
    prev_no: null,
    state: 'proposed',
    stateText: '제안됨',
    state_opt: null,
    brief: '동맹 제안',
    detail: '함께 합시다.',
    date: '200-01-01 00:00:00',
};

function diplomacyPayload(letters: unknown[] = []) {
    return {
        result: true,
        myNationID: 1,
        nations: {
            1: { id: 1, name: '촉', color: '#228833', level: 1 },
            2: { id: 2, name: '위', color: '#3355aa', level: 1 },
        },
        letters,
    };
}

describe('DiplomacyPage command reservation', () => {
    beforeEach(() => {
        mocks.diplomacyLetters.mockReset();
        mocks.diploSendLetter.mockReset();
        mocks.diploRespondLetter.mockReset();
        mocks.submitCommandAndAwaitResult.mockReset();
        mocks.refresh.mockReset();
        mocks.commandModalProps.length = 0;
        vi.stubGlobal('confirm', vi.fn(() => true));
        vi.stubGlobal('prompt', vi.fn(() => ''));
    });

    afterEach(() => {
        vi.unstubAllGlobals();
    });

    it('submits quick diplomacy commands through the nation reservation queue', async () => {
        mocks.diplomacyLetters.mockResolvedValueOnce(diplomacyPayload());

        render(<DiplomacyPage />);

        await waitFor(() => expect(mocks.diplomacyLetters).toHaveBeenCalled());
        fireEvent.click(screen.getByRole('button', { name: '종전 제의' }));

        expect(screen.getByTestId('command-modal')).toBeInTheDocument();
        expect(mocks.commandModalProps.at(-1)).toMatchObject({
            generalId: 10,
            nationId: 1,
            pinnedCommand: 'che_종전제의',
            pinnedArgType: 'nation',
            isNationCommand: true,
        });
    });

    it('resolves the non-aggression proposal form from the server command catalog', async () => {
        mocks.diplomacyLetters.mockResolvedValueOnce(diplomacyPayload());

        render(<DiplomacyPage />);

        await waitFor(() => expect(mocks.diplomacyLetters).toHaveBeenCalled());
        fireEvent.click(screen.getByRole('button', { name: '불가침 제의' }));

        expect(mocks.commandModalProps.at(-1)).toMatchObject({
            generalId: 10,
            nationId: 1,
            pinnedCommand: 'che_불가침제의',
            pinnedArgType: 'nation',
            resolvePinnedFromCatalog: true,
            isNationCommand: true,
        });
    });

    it('shows send success only after the command result is applied', async () => {
        mocks.diplomacyLetters.mockResolvedValue(diplomacyPayload());
        mocks.diploSendLetter.mockResolvedValue({ status: 'AVAILABLE', requestId: 'diplo-1' });
        mocks.submitCommandAndAwaitResult.mockImplementation(async (submit: () => Promise<unknown>) => {
            await submit();
            return {
                status: 'applied',
                result: { status: 'RESOLVED', requestId: 'diplo-1', ok: true, type: 'diploSendLetter', result: {} },
            };
        });

        render(<DiplomacyPage />);

        await waitFor(() => expect(mocks.diplomacyLetters).toHaveBeenCalled());
        fireEvent.click(screen.getByRole('button', { name: '펼치기' }));
        fireEvent.change(screen.getAllByRole('combobox')[0], { target: { value: '2' } });
        fireEvent.change(screen.getByPlaceholderText('요약문을 입력하세요'), { target: { value: '동맹 제안' } });
        fireEvent.change(screen.getByRole('textbox', { name: '외교 서신 본문' }), { target: { value: '함께 합시다.' } });
        fireEvent.click(screen.getByRole('button', { name: '발송' }));

        await waitFor(() => expect(mocks.diploSendLetter).toHaveBeenCalledWith({
            destNation: 2,
            brief: '동맹 제안',
            detail: '<p>함께 합시다.</p>',
            prevNo: null,
        }, 10));
        await waitFor(() => expect(screen.getByText('전송했습니다.')).toBeInTheDocument());
    });

    it('does not reserve a diplomacy body beyond the raw 500-code-point limit', async () => {
        mocks.diplomacyLetters.mockResolvedValue(diplomacyPayload());

        render(<DiplomacyPage />);

        await waitFor(() => expect(mocks.diplomacyLetters).toHaveBeenCalled());
        fireEvent.click(screen.getByRole('button', { name: '펼치기' }));
        fireEvent.change(screen.getAllByRole('combobox')[0], { target: { value: '2' } });
        fireEvent.change(screen.getByPlaceholderText('요약문을 입력하세요'), { target: { value: '동맹 제안' } });
        fireEvent.change(screen.getByRole('textbox', { name: '외교 서신 본문' }), { target: { value: '가'.repeat(501) } });

        const sendButton = screen.getByRole('button', { name: '발송' });
        await waitFor(() => expect(sendButton).toBeDisabled());
        fireEvent.click(sendButton);

        expect(mocks.diploSendLetter).not.toHaveBeenCalled();
        expect(mocks.submitCommandAndAwaitResult).not.toHaveBeenCalled();
    });

    it('approves a received proposed letter through the awaited command result', async () => {
        mocks.diplomacyLetters.mockResolvedValue(diplomacyPayload([receivedProposedLetter]));
        mocks.diploRespondLetter.mockResolvedValue({ status: 'AVAILABLE', requestId: 'respond-approve' });
        mocks.submitCommandAndAwaitResult.mockImplementation(async (submit: () => Promise<unknown>) => {
            await submit();
            return {
                status: 'applied',
                result: { status: 'RESOLVED', requestId: 'respond-approve', ok: true, type: 'diploRespondLetter', result: {} },
            };
        });

        render(<DiplomacyPage />);

        await waitFor(() => expect(mocks.diplomacyLetters).toHaveBeenCalled());
        fireEvent.click(screen.getByRole('button', { name: '승인' }));

        expect(globalThis.confirm).toHaveBeenCalledWith('승인하시겠습니까?');
        await waitFor(() => expect(mocks.diploRespondLetter).toHaveBeenCalledWith({
            letterNo: 7,
            isAgree: true,
            reason: '',
        }, 10));
        await waitFor(() => expect(screen.getByText('승인했습니다.')).toBeInTheDocument());
    });

    it('rejects a received proposed letter with a truncated reason through the awaited command result', async () => {
        const longReason = '1234567890'.repeat(6);
        vi.stubGlobal('prompt', vi.fn(() => longReason));
        mocks.diplomacyLetters.mockResolvedValue(diplomacyPayload([receivedProposedLetter]));
        mocks.diploRespondLetter.mockResolvedValue({ status: 'AVAILABLE', requestId: 'respond-reject' });
        mocks.submitCommandAndAwaitResult.mockImplementation(async (submit: () => Promise<unknown>) => {
            await submit();
            return {
                status: 'applied',
                result: { status: 'RESOLVED', requestId: 'respond-reject', ok: true, type: 'diploRespondLetter', result: {} },
            };
        });

        render(<DiplomacyPage />);

        await waitFor(() => expect(mocks.diplomacyLetters).toHaveBeenCalled());
        fireEvent.click(screen.getByRole('button', { name: '거부' }));

        expect(globalThis.prompt).toHaveBeenCalledWith('거부 사유를 입력하세요.', '');
        await waitFor(() => expect(mocks.diploRespondLetter).toHaveBeenCalledWith({
            letterNo: 7,
            isAgree: false,
            reason: longReason.slice(0, 50),
        }, 10));
        await waitFor(() => expect(screen.getByText('거부했습니다.')).toBeInTheDocument());
    });

    it('does not submit a reject request when the reason prompt is canceled', async () => {
        vi.stubGlobal('prompt', vi.fn(() => null));
        mocks.diplomacyLetters.mockResolvedValue(diplomacyPayload([receivedProposedLetter]));

        render(<DiplomacyPage />);

        await waitFor(() => expect(mocks.diplomacyLetters).toHaveBeenCalled());
        fireEvent.click(screen.getByRole('button', { name: '거부' }));

        expect(globalThis.prompt).toHaveBeenCalledWith('거부 사유를 입력하세요.', '');
        expect(mocks.submitCommandAndAwaitResult).not.toHaveBeenCalled();
        expect(mocks.diploRespondLetter).not.toHaveBeenCalled();
    });
});
