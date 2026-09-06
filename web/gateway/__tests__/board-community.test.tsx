import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import BoardIndex from '@/app/board/page';

vi.mock('next/link', () => ({ default: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a> }));
vi.mock('@/lib/auth-context', () => ({ useAuth: () => ({ user: { id: 1, username: 'tester', nickname: '테스터', role: 'USER', picture: null, imageServer: 0 } }) }));
vi.mock('@/components/Topbar', () => ({ default: () => <header>topbar</header> }));

const post = (id: number, title: string, extra: Record<string, unknown> = {}) => ({
  id, category: 'FREE', authorName: '글쓴이', authorPicture: null, authorImageServer: 0, title, contentHtml: '<p>x</p>', pinned: false,
  canDelete: false, deleted: false, createdAt: '2026-09-06T10:00:00Z', updatedAt: '2026-09-06T10:00:00Z', viewCount: 12, commentCount: 3,
  authorGeneralName: '하후돈', authorWorldId: 1, ...extra,
});
const page = (content: unknown[]) => ({ content, page: 0, size: 20, totalElements: content.length, totalPages: 1 });

function mockFetch() {
  return vi.fn(async (url: string) => {
    if (url === '/api/board/categories') {
      return { ok: true, status: 200, text: async () => JSON.stringify([{ category: 'NOTICE', count: 2 }, { category: 'FREE', count: 5 }, { category: 'SUGGESTION', count: 0 }, { category: 'STRATEGY', count: 1 }, { category: 'SERVER', count: 0 }, { category: 'CREATIVE', count: 0 }]) } as Response;
    }
    if (url.includes('sort=popular')) {
      return { ok: true, status: 200, text: async () => JSON.stringify(page([post(9, '인기 글 하나')])) } as Response;
    }
    if (url.includes('q=%EA%B2%80%EC%83%89')) {
      return { ok: true, status: 200, text: async () => JSON.stringify(page([post(7, '검색된 글')])) } as Response;
    }
    return { ok: true, status: 200, text: async () => JSON.stringify(page([post(1, '첫 글')])) } as Response;
  });
}

describe('BoardIndex (13 커뮤니티)', () => {
  beforeEach(() => { vi.stubGlobal('fetch', mockFetch()); });

  it('renders six categories with counts, the author server badge, and the rail', async () => {
    render(<BoardIndex />);
    expect(await screen.findByRole('link', { name: '첫 글' })).toBeInTheDocument();
    const group = screen.getByRole('group', { name: '게시판 분류' });
    expect(within(group).getAllByRole('button')).toHaveLength(7); // 전체 + 6
    await waitFor(() => expect(within(group).getByRole('button', { name: /^자유/ })).toHaveTextContent('5'));
    expect(within(group).getByRole('button', { name: /^전략·공략/ })).toBeInTheDocument();
    expect(screen.getByText('하후돈 · 월드 1')).toBeInTheDocument();
    expect(screen.getByText('조회 12')).toBeInTheDocument();
    expect(screen.getByText('댓글 3')).toBeInTheDocument();
    expect(await screen.findByRole('link', { name: '인기 글 하나' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '대표 장수 변경' })).toHaveAttribute('href', '/account#representative');
    expect(screen.getByText(/수뇌부만 · 열람 기록 남음/)).toBeInTheDocument();
  });

  it('adds sort and q to the list query only when they differ from the defaults', async () => {
    render(<BoardIndex />);
    await screen.findByRole('link', { name: '첫 글' });
    expect(fetch).toHaveBeenCalledWith('/api/board/posts?category=NOTICE&page=0&size=20', { cache: 'no-store' });
    fireEvent.click(screen.getByRole('tab', { name: '인기' }));
    await waitFor(() => expect(fetch).toHaveBeenLastCalledWith('/api/board/posts?category=NOTICE&page=0&size=20&sort=popular', { cache: 'no-store' }));
    fireEvent.click(within(screen.getByRole('group', { name: '게시판 분류' })).getByRole('button', { name: /^전체/ }));
    await waitFor(() => expect(fetch).toHaveBeenLastCalledWith('/api/board/posts?page=0&size=20&sort=popular', { cache: 'no-store' }));
    fireEvent.click(screen.getByRole('tab', { name: '최신' }));
    fireEvent.change(screen.getByRole('searchbox', { name: '검색' }), { target: { value: '검색' } });
    fireEvent.click(screen.getByRole('button', { name: '검색' }));
    await waitFor(() => expect(fetch).toHaveBeenLastCalledWith('/api/board/posts?page=0&size=20&q=%EA%B2%80%EC%83%89', { cache: 'no-store' }));
    expect(await screen.findByRole('link', { name: '검색된 글' })).toBeInTheDocument();
    expect(screen.getByText(/검색 결과/)).toHaveTextContent('1건');
  });
});
