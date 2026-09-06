import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import BoardPage from '@/app/game/board/page';

type ModalProps = {
    extraArgs?: Record<string, unknown>;
    onReserved?: () => void;
    pinnedCommand?: string;
};

const apiMocks = vi.hoisted(() => ({
    board: vi.fn(),
    frontInfo: vi.fn(),
}));

const modalSpy = vi.hoisted(() => ({
    latest: null as ModalProps | null,
}));

vi.mock('next/navigation', () => ({
    useSearchParams: () => new URLSearchParams(),
}));

vi.mock('@/components/Shell', () => ({
    default: ({ children }: { children: ReactNode }) => <>{children}</>,
}));

vi.mock('@/components/GameCard', () => ({
    default: ({ children }: { children: ReactNode }) => <section>{children}</section>,
}));

vi.mock('@/components/StatusBadge', () => ({
    default: ({ children }: { children: ReactNode }) => <span>{children}</span>,
}));

vi.mock('@/components/RichTextEditor', () => ({
    RichTextEditor: ({ onChange, value }: { onChange: (html: string) => void; value: string }) => (
        <>
            <button
                aria-label="내용 작성"
                onClick={() => onChange('<p><strong>천하</strong> 통일</p>')}
                type="button"
            >
                서식 입력
            </button>
            <output data-testid="rich-value">{value}</output>
        </>
    ),
}));

vi.mock('@/components/CommandModal', () => ({
    default: (props: ModalProps) => {
        modalSpy.latest = props;
        return (
            <button aria-label="terminal success" onClick={() => props.onReserved?.()} type="button">
                terminal success
            </button>
        );
    },
}));

vi.mock('@/lib/api', () => ({
    api: apiMocks,
}));

class EventSourceStub {
    onerror: (() => void) | null = null;

    addEventListener(): void {}

    close(): void {}
}

describe('Board rich text command lifecycle', () => {
    beforeEach(() => {
        apiMocks.board.mockReset().mockResolvedValue({
            result: true,
            secret: false,
            title: '회의실',
            blockedReason: null,
            articles: [],
        });
        apiMocks.frontInfo.mockReset().mockResolvedValue({ general: { generalId: 10 } });
        modalSpy.latest = null;
        vi.stubGlobal('EventSource', EventSourceStub);
    });

    it('forwards formatted HTML unchanged and clears or refetches only through the terminal callback', async () => {
        render(<BoardPage />);

        await screen.findByText('게시물이 없습니다.');
        expect(apiMocks.board).toHaveBeenCalledTimes(1);

        fireEvent.click(screen.getByRole('button', { name: '내용 작성' }));
        await waitFor(() => {
            expect(screen.getByTestId('rich-value')).toHaveTextContent('<p><strong>천하</strong> 통일</p>');
        });
        fireEvent.click(screen.getByRole('button', { name: '등록' }));

        expect(await screen.findByRole('button', { name: 'terminal success' })).toBeInTheDocument();
        expect(modalSpy.latest?.pinnedCommand).toBe('boardArticle');
        expect(modalSpy.latest?.extraArgs).toEqual({
            isSecret: false,
            title: '',
            text: '<p><strong>천하</strong> 통일</p>',
            kind: 'general',
        });
        expect(apiMocks.board).toHaveBeenCalledTimes(1);

        fireEvent.click(screen.getByRole('button', { name: 'terminal success' }));

        await waitFor(() => expect(apiMocks.board).toHaveBeenCalledTimes(2));
        expect(screen.getByTestId('rich-value')).toHaveTextContent('');
    });
});
