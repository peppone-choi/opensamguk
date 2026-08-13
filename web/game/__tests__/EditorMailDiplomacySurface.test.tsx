import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import DiplomacyPage from '@/app/game/diplomacy/page';
import MailboxPage from '@/app/game/mailbox/page';

const mocks = vi.hoisted(() => ({
    contacts: vi.fn(),
    diplomacyLetters: vi.fn(),
    frontInfo: vi.fn(),
    mailboxRecent: vi.fn(),
    readLatestMessage: vi.fn(),
    refresh: vi.fn(),
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

vi.mock('@/lib/api', async importOriginal => {
    const actual = await importOriginal<typeof import('@/lib/api')>();
    Object.assign(actual.api, {
        contacts: mocks.contacts,
        diplomacyLetters: mocks.diplomacyLetters,
        frontInfo: mocks.frontInfo,
        mailboxRecent: mocks.mailboxRecent,
        commands: {
            readLatestMessage: mocks.readLatestMessage,
        },
    });
    return actual;
});

vi.mock('@/components/CommandModal', () => ({
    default: () => <div data-testid="command-modal" />,
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

function mailboxPayload(text: string) {
    return {
        diplomacy: [],
        national: [],
        private: [{
            id: 1,
            msgType: 'private' as const,
            src: { id: 10, name: '나', nation_id: 1, nation: '촉', color: '#228833' },
            dest: { id: 20, name: '상대', nation_id: 2, nation: '위', color: '#3355aa' },
            text,
            option: null,
            time: '200-01-01T00:00:00Z',
        }],
        public: [],
        sequence: 1,
    };
}

function diplomacyPayload() {
    return {
        result: true,
        myNationID: 1,
        nations: {
            1: { id: 1, name: '촉', color: '#228833', level: 1 },
            2: { id: 2, name: '위', color: '#3355aa', level: 1 },
        },
        letters: [{
            no: 7,
            src: { nationID: 2, nationName: '위', nationColor: '#3355aa', generalName: '조조', generalIcon: null },
            dest: { nationID: 1, nationName: '촉', nationColor: '#228833', generalName: null, generalIcon: null },
            prev_no: null,
            state: 'proposed',
            stateText: '제안됨',
            state_opt: null,
            brief: '<p><strong>공개 문서</strong></p>',
            detail: '<p><em>외교 본문</em><img src=x onerror=alert(1)></p>',
            date: '200-01-01 00:00:00',
        }],
    };
}

beforeEach(() => {
    mocks.contacts.mockResolvedValue({ nation: [] });
    mocks.diplomacyLetters.mockResolvedValue(diplomacyPayload());
    mocks.frontInfo.mockResolvedValue({ general: { generalId: 10, nationId: 1 } });
    mocks.mailboxRecent.mockResolvedValue(mailboxPayload('<p><strong>서식 메시지</strong><img src=x onerror=alert(1)></p>'));
    mocks.readLatestMessage.mockResolvedValue({ status: 'AVAILABLE', requestId: 'read-latest' });
});

afterEach(() => {
    vi.clearAllMocks();
});

describe('OPENSAM-87 rich-text surface wiring', () => {
    it('renders the mailbox rich-text editor and sanitizes stored message markup', async () => {
        // Given
        const { container } = render(<MailboxPage />);

        // Then
        const editor = await screen.findByRole('textbox', { name: '서신 내용' });
        await waitFor(() => expect(editor).toHaveAttribute('contenteditable', 'true'));
        expect(await screen.findByText(/\/ 500/)).toBeInTheDocument();
        await waitFor(() => expect(container.querySelector('strong')).toHaveTextContent('서식 메시지'));
        expect(container.querySelector('img')).toBeNull();
    });

    it('keeps the diplomacy summary plain while using rich text and safe rendering for the document body', async () => {
        // Given
        const { container } = render(<DiplomacyPage />);

        await waitFor(() => expect(mocks.diplomacyLetters).toHaveBeenCalled());

        // When
        fireEvent.click(screen.getByRole('button', { name: '펼치기' }));

        // Then
        expect(screen.getByPlaceholderText('요약문을 입력하세요')).toHaveAttribute('type', 'text');
        expect(await screen.findByRole('textbox', { name: '외교 서신 본문' })).toHaveAttribute('contenteditable', 'true');
        await waitFor(() => expect(container.querySelector('strong')).toHaveTextContent('공개 문서'));
        expect(container.querySelector('em')).toHaveTextContent('외교 본문');
        expect(container.querySelector('img')).toBeNull();
    });
});
