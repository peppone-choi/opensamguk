import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import DiplomacyPage from '@/app/game/diplomacy/page';

const mocks = vi.hoisted(() => ({
    diplomacyLetters: vi.fn(),
    refresh: vi.fn(),
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
            diploSendLetter: vi.fn(),
            diploRollbackLetter: vi.fn(),
            diploDestroyLetter: vi.fn(),
        },
    },
    isIntakeDenied: vi.fn(() => false),
    isIntakeQueued: vi.fn(() => true),
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
});
