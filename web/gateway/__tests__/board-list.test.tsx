import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
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
    vi.stubGlobal('fetch', vi.fn(async () => response(listBody)));
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('lets an anonymous visitor read the public list under the public header', async () => {
    render(<BoardIndex />);

    expect(await screen.findByRole('heading', { level: 1, name: '커뮤니티 게시판' })).toBeInTheDocument();
    const list = screen.getByRole('region', { name: '게시글' });
    expect(await within(list).findByRole('link', { name: '서버 점검 안내' })).toHaveAttribute('href', '/board/posts/9');
    expect(within(list).getByText('고정')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '로그인 후 글쓰기' })).toHaveAttribute('href', '/login?next=%2Fboard%2Fwrite');
    // 옛 상단바는 손님에게도 「로그아웃」을 보였다(설계서 §3 공통) — 손님 머리줄 오른쪽은 「로그인」.
    const header = screen.getByRole('banner');
    expect(within(header).getByRole('link', { name: '로그인' })).toHaveAttribute('href', '/login');
    expect(within(header).queryByText(/로\s*그\s*아\s*웃|로그아웃/)).toBeNull();
    expect(screen.getByText('로그인하면 글쓰기 · 신고 · 대표 장수 설정을 쓸 수 있습니다.')).toBeInTheDocument();
    expect(fetch).toHaveBeenCalledWith('/api/board/posts?category=NOTICE&page=0&size=20', { cache: 'no-store' });
  });

  it('keeps 「내 글」 out of the guest sort and explains why when pressed', async () => {
    render(<BoardIndex />);
    await within(screen.getByRole('region', { name: '게시글' })).findByRole('link', { name: '서버 점검 안내' });
    const sort = screen.getByRole('radiogroup', { name: '정렬' });
    expect(within(sort).getAllByRole('radio').map((r) => r.textContent)).toEqual(['최신', '인기']);
    const mine = screen.getByRole('button', { name: '내 글' });
    expect(mine).toHaveAttribute('aria-disabled', 'true');
    expect(mine).toHaveAttribute('data-reason', '로그인하면 볼 수 있습니다');
    fireEvent.click(mine);
    expect(fetch).not.toHaveBeenCalledWith(expect.stringContaining('sort=mine'), expect.anything());
  });

  it('changes category through the public API and preserves its query contract', async () => {
    render(<BoardIndex />);
    await within(screen.getByRole('region', { name: '게시글' })).findByRole('link', { name: '서버 점검 안내' });

    const group = screen.getByRole('radiogroup', { name: '게시판' });
    const notice = within(group).getByRole('radio', { name: /^공지/ });
    const free = within(group).getByRole('radio', { name: /^자유/ });
    expect(notice).toHaveAttribute('aria-checked', 'true');
    expect(free).toHaveAttribute('aria-checked', 'false');
    fireEvent.click(free);

    await waitFor(() => expect(fetch).toHaveBeenLastCalledWith(
      '/api/board/posts?category=FREE&page=0&size=20',
      { cache: 'no-store' },
    ));
    expect(notice).toHaveAttribute('aria-checked', 'false');
    expect(free).toHaveAttribute('aria-checked', 'true');
  });
});
