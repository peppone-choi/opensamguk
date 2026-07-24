import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import DiplomacyPage from '@/app/game/diplomacy/page';

const mocks = vi.hoisted(() => ({
    diplomacyLetters: vi.fn(),
    refresh: vi.fn(),
    diploSendLetter: vi.fn(),
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

class EventSourceStub {
    onerror: (() => void) | null = null;
    addEventListener(): void {}
    close(): void {}
}

Object.defineProperty(globalThis, 'EventSource', {
    value: EventSourceStub,
    configurable: true,
});

describe('DiplomacyPage command reservation', () => {
    beforeEach(() => {
        mocks.diplomacyLetters.mockReset();
        mocks.diploSendLetter.mockReset();
        mocks.submitCommandAndAwaitResult.mockReset();
        mocks.refresh.mockReset();
    });

    it('submits quick diplomacy commands through the nation reservation queue', async () => {
        mocks.commandModalProps.length = 0;
        mocks.diplomacyLetters.mockResolvedValueOnce({
            myNationID: 1,
            nations: {
                1: { id: 1, name: '촉', color: '#228833', level: 1 },
                2: { id: 2, name: '위', color: '#3355aa', level: 1 },
            },
            letters: [],
        });

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

    it('shows send success only after the command result is applied', async () => {
        mocks.diplomacyLetters.mockResolvedValue({
            myNationID: 1,
            nations: {
                1: { id: 1, name: '촉', color: '#228833', level: 1 },
                2: { id: 2, name: '위', color: '#3355aa', level: 1 },
            },
            letters: [],
        });
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
        fireEvent.change(screen.getByPlaceholderText('본문을 입력하세요'), { target: { value: '함께 합시다.' } });
        fireEvent.click(screen.getByRole('button', { name: '발송' }));

        await waitFor(() => expect(mocks.diploSendLetter).toHaveBeenCalledWith({
            destNation: 2,
            brief: '동맹 제안',
            detail: '함께 합시다.',
            prevNo: null,
        }, 10));
        await waitFor(() => expect(screen.getByText('전송했습니다.')).toBeInTheDocument());
    });
});
