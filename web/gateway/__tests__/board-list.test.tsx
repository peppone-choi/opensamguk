import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import BoardIndex from '@/app/board/page';

let authUser: {
  id: number;
  username: string;
  email: null;
  nickname: string | null;
  role: string;
  picture: null;
  imageServer: number;
} | null = null;

vi.mock('next/link', () => ({
  default: ({ href, children, ...props }: React.AnchorHTMLAttributes<HTMLAnchorElement> & { href: string }) => (
    <a href={href} {...props}>{children}</a>
  ),
}));

vi.mock('@/lib/auth-context', () => ({
  useAuth: () => ({ user: authUser, loading: false, refresh: vi.fn(), logout: vi.fn() }),
  useAuthOptional: () => ({ user: authUser, loading: false, refresh: vi.fn(), logout: vi.fn() }),
}));

function response(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}

const listBody = {
  content: [
    {
      id: 9,
      category: 'NOTICE',
      authorName: '운영자',
      title: '서버 점검 안내',
      contentHtml: '점검 안내',
      pinned: true,
      canDelete: false,
      deleted: false,
      createdAt: '2026-08-12T09:00:00Z',
      updatedAt: '2026-08-12T09:00:00Z',
    },
    {
      id: 8,
      category: 'FREE',
      authorName: '테스터',
      title: '자유 게시글',
      contentHtml: '본문',
      pinned: false,
      canDelete: false,
      deleted: false,
      createdAt: '2026-08-12T08:00:00Z',
      updatedAt: '2026-08-12T08:00:00Z',
    },
  ],
  page: 0,
  size: 20,
  totalElements: 2,
  totalPages: 1,
};

describe('gateway board list', () => {
  beforeEach(() => {
    authUser = null;
    vi.clearAllMocks();
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(listBody)));
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('lets an anonymous visitor read the public list', async () => {
    render(<BoardIndex />);

    expect(await screen.findByRole('heading', { name: '커뮤니티 게시판' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '서버 점검 안내' })).toHaveAttribute('href', '/board/posts/9');
    expect(screen.getByText('고정')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '로그인 후 글쓰기' }))
      .toHaveClass('board-heading-action');
    expect(screen.getByRole('link', { name: '로그인 후 글쓰기' })).toHaveAttribute('href', '/login?next=%2Fboard%2Fwrite');
    expect(fetch).toHaveBeenCalledWith('/api/board/posts?category=NOTICE&page=0&size=20', { cache: 'no-store' });
  });

  it('changes category through the public API and preserves its query contract', async () => {
    render(<BoardIndex />);
    await screen.findByRole('link', { name: '서버 점검 안내' });

    const categoryGroup = screen.getByRole('group', { name: '게시판 분류' });
    const notice = screen.getByRole('button', { name: '공지' });
    const free = screen.getByRole('button', { name: '자유' });

    expect(categoryGroup).toContainElement(notice);
    expect(notice).toHaveAttribute('aria-pressed', 'true');
    expect(free).toHaveAttribute('aria-pressed', 'false');
    fireEvent.click(free);

    await waitFor(() => expect(fetch).toHaveBeenLastCalledWith(
      '/api/board/posts?category=FREE&page=0&size=20',
      { cache: 'no-store' },
    ));
    expect(notice).toHaveAttribute('aria-pressed', 'false');
    expect(free).toHaveAttribute('aria-pressed', 'true');
  });
});
