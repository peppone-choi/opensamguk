import { render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import BoardPage from '@/app/game/board/page';
import { isArticleBodyBlank } from '@/app/game/board/articleBody';

const apiMocks = vi.hoisted(() => ({
  board: vi.fn(),
  frontInfo: vi.fn(),
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

vi.mock('@/components/CommandModal', () => ({
  default: () => null,
}));

vi.mock('@/lib/api', () => ({
  api: apiMocks,
}));

class EventSourceStub {
  onerror: (() => void) | null = null;

  addEventListener(): void {}

  close(): void {}
}

describe('Board rich text', () => {
  beforeEach(() => {
    apiMocks.board.mockReset().mockResolvedValue({
      result: true,
      secret: false,
      title: '회의실',
      blockedReason: null,
      articles: [
        {
          id: 7,
          nationId: 1,
          authorGeneralId: 10,
          authorName: '유비',
          title: '천하의 뜻',
          contentHtml: '<p><strong>천하</strong> 통일</p>',
          date: '2026-08-13T10:00:00Z',
          comments: [],
        },
      ],
    });
    apiMocks.frontInfo.mockReset().mockResolvedValue({ general: { generalId: 0 } });
    vi.stubGlobal('EventSource', EventSourceStub);
  });

  it('renders persisted article formatting through SafeHtml', async () => {
    render(<BoardPage />);

    await screen.findByText('천하의 뜻');

    await waitFor(() => {
      expect(screen.getByText('천하', { selector: 'strong' })).toBeInTheDocument();
    });
  });

  it('renders persisted comment formatting through SafeHtml', async () => {
    apiMocks.board.mockResolvedValue({
      result: true,
      secret: false,
      title: '회의실',
      blockedReason: null,
      articles: [
        {
          id: 7,
          nationId: 1,
          authorGeneralId: 10,
          authorName: '유비',
          title: '천하의 뜻',
          contentHtml: '본문',
          date: '2026-08-13T10:00:00Z',
          comments: [
            {
              id: 3,
              authorGeneralId: 11,
              authorName: '관우',
              text: '<p><em>찬성</em>합니다</p>',
              date: '2026-08-13T11:00:00Z',
            },
          ],
        },
      ],
    });

    render(<BoardPage />);

    await screen.findByText('천하의 뜻');

    await waitFor(() => {
      expect(screen.getByText('찬성', { selector: 'em' })).toBeInTheDocument();
    });
  });

  it('uses the shared rich text editor for a writable article body', async () => {
    apiMocks.frontInfo.mockResolvedValue({ general: { generalId: 10 } });

    render(<BoardPage />);

    expect(await screen.findByRole('button', { name: '굵게' }, { timeout: 5_000 })).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: '내용' })).toHaveAttribute('contenteditable', 'true');
    expect(screen.getByPlaceholderText('제목')).toHaveAttribute('maxlength', '250');
    expect(screen.getByPlaceholderText('새 댓글 내용')).toHaveAttribute('maxlength', '250');
  });

  it('keeps visually empty rich article bodies inside the existing empty-body guard', () => {
    expect(isArticleBodyBlank('<p><br class="ProseMirror-trailingBreak"></p>')).toBe(true);
    expect(isArticleBodyBlank('<p> </p>')).toBe(true);
    expect(isArticleBodyBlank('<p>&nbsp;</p>')).toBe(true);
    expect(isArticleBodyBlank('<p><strong>천하</strong> 통일</p>')).toBe(false);
  });
});
