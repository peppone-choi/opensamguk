import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import BoardIndex from '@/app/board/page';

vi.mock('next/link', () => ({ default: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a> }));
vi.mock('@/lib/auth-context', () => {
  const session = () => ({ user: { id: 1, username: 'tester', nickname: '테스터', role: 'USER', picture: null, imageServer: 0 }, loading: false, refresh: vi.fn(), logout: vi.fn() });
  return { useAuth: session, useAuthOptional: session };
});

const post = (id: number, title: string, extra: Record<string, unknown> = {}) => ({
  id, category: 'FREE', authorName: '글쓴이', authorPicture: null, authorImageServer: 0, title, contentHtml: '<p>x</p>', pinned: false,
  canDelete: false, deleted: false, createdAt: '2026-09-06T10:00:00Z', updatedAt: '2026-09-06T10:00:00Z', viewCount: 12, commentCount: 3,
  authorGeneralName: '하후돈', authorWorldId: 1, ...extra,
});
const page = (content: unknown[], extra: Record<string, unknown> = {}) => ({ content, page: 0, size: 20, totalElements: content.length, totalPages: 1, ...extra });
const ok = (body: unknown) => ({ ok: true, status: 200, text: async () => JSON.stringify(body) }) as Response;
const fail = (status: number, message: string) => ({ ok: false, status, text: async () => JSON.stringify({ message, status }) }) as Response;

type Handler = (url: string) => Response | undefined;
let override: Handler = () => undefined;

function mockFetch() {
  return vi.fn(async (url: string) => {
    const custom = override(url);
    if (custom) return custom;
    if (url === '/api/board/categories') {
      return ok([{ category: 'NOTICE', count: 2 }, { category: 'FREE', count: 5 }, { category: 'SUGGESTION', count: 0 }, { category: 'STRATEGY', count: 1 }, { category: 'SERVER', count: 0 }, { category: 'CREATIVE', count: 0 }]);
    }
    if (url.includes('size=3&sort=popular')) return ok(page([post(9, '인기 글 하나')]));
    if (url.includes('q=%EA%B2%80%EC%83%89')) return ok(page([post(7, '검색된 글')]));
    return ok(page([post(1, '첫 글')]));
  });
}

const categories = () => screen.getByRole('radiogroup', { name: '게시판' });

describe('P-G06 커뮤니티 목록 — 로그인', () => {
  beforeEach(() => {
    override = () => undefined;
    vi.stubGlobal('fetch', mockFetch());
  });

  it('게시판 7칸 · 수 · 대표 장수 칩(월드 번호 없음) · 레일 셋 · 회원 머리줄', async () => {
    render(<BoardIndex />);
    expect(await screen.findByRole('link', { name: '첫 글' })).toHaveAttribute('href', '/board/posts/1');
    expect(within(categories()).getAllByRole('radio')).toHaveLength(7);
    expect(within(categories()).getByRole('radio', { name: /^공지/ })).toHaveAttribute('aria-checked', 'true');
    await waitFor(() => expect(within(categories()).getByRole('radio', { name: /^자유/ })).toHaveTextContent('5'));
    expect(within(categories()).getByRole('radio', { name: /^전체/ })).toHaveTextContent('8');
    expect(screen.getByText('대표 장수 · 하후돈')).toBeInTheDocument();
    expect(screen.queryByText(/월드/)).toBeNull();
    expect(screen.getByText('조회 12 · 댓글 3')).toBeInTheDocument();
    expect(await screen.findByRole('link', { name: '인기 글 하나' })).toHaveAttribute('href', '/board/posts/9');
    expect(screen.getByRole('link', { name: '대표 장수 바꾸기' })).toHaveAttribute('href', '/account#representative');
    expect(screen.getByText('얼굴은 계정 초상입니다. 대표 장수는 계정 설정에서 정합니다.')).toBeInTheDocument();
    expect(screen.getByText(/기밀실 참여자만 · 열람 기록이 남음/)).toBeInTheDocument();
    expect(screen.queryByText(/OPEN SAMGUK|전콘|수뇌부/)).toBeNull();
    expect(screen.getByRole('link', { name: '글쓰기' })).toHaveAttribute('href', '/board/write');
    expect(screen.queryByRole('link', { name: '로그인' })).toBeNull();
  });

  it('정렬 · 게시판 · 검색은 기본값이 아닐 때만 쿼리에 붙는다', async () => {
    render(<BoardIndex />);
    await screen.findByRole('link', { name: '첫 글' });
    expect(fetch).toHaveBeenCalledWith('/api/board/posts?category=NOTICE&page=0&size=20', { cache: 'no-store' });
    const sort = screen.getByRole('radiogroup', { name: '정렬' });
    expect(within(sort).getAllByRole('radio').map((r) => r.textContent)).toEqual(['최신', '인기', '내 글']);
    fireEvent.click(within(sort).getByRole('radio', { name: '인기' }));
    await waitFor(() => expect(fetch).toHaveBeenLastCalledWith('/api/board/posts?category=NOTICE&page=0&size=20&sort=popular', { cache: 'no-store' }));
    fireEvent.click(within(categories()).getByRole('radio', { name: /^전체/ }));
    await waitFor(() => expect(fetch).toHaveBeenLastCalledWith('/api/board/posts?page=0&size=20&sort=popular', { cache: 'no-store' }));
    fireEvent.click(within(sort).getByRole('radio', { name: '최신' }));
    fireEvent.change(screen.getByRole('searchbox', { name: '검색어' }), { target: { value: '검색' } });
    fireEvent.click(within(screen.getByRole('search')).getByRole('button', { name: '검색' }));
    await waitFor(() => expect(fetch).toHaveBeenLastCalledWith('/api/board/posts?page=0&size=20&q=%EA%B2%80%EC%83%89', { cache: 'no-store' }));
    expect(await screen.findByRole('link', { name: '검색된 글' })).toBeInTheDocument();
    expect(screen.getByText(/검색 결과/)).toHaveTextContent('「검색」 검색 결과 1건');
  });

  it('목록 실패는 서버 문장 + 다시 시도, 빈 목록 · 빈 검색은 다른 문구', async () => {
    let failing = true;
    override = (url) => (url.startsWith('/api/board/posts?category=NOTICE') && failing ? fail(502, '게시판 서버에 연결할 수 없습니다.') : undefined);
    render(<BoardIndex />);
    expect(await screen.findByText('게시판을 불러오지 못했습니다')).toBeInTheDocument();
    expect(screen.getByText('게시판 서버에 연결할 수 없습니다.')).toBeInTheDocument();
    failing = false;
    override = (url) => (url.includes('size=20') ? ok(page([])) : undefined);
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    expect(await screen.findByText('아직 게시글이 없습니다.')).toBeInTheDocument();
    fireEvent.change(screen.getByRole('searchbox', { name: '검색어' }), { target: { value: '없는 말' } });
    fireEvent.click(within(screen.getByRole('search')).getByRole('button', { name: '검색' }));
    expect(await screen.findByText('검색 결과가 없습니다.')).toBeInTheDocument();
  });

  it('인기 글은 빈 것과 실패를 가른다', async () => {
    override = (url) => (url.includes('size=3&sort=popular') ? fail(500, '인기 글 서버 오류') : undefined);
    const { unmount } = render(<BoardIndex />);
    expect(await screen.findByText('인기 글을 불러오지 못했습니다')).toBeInTheDocument();
    unmount();
    override = (url) => (url.includes('size=3&sort=popular') ? ok(page([])) : undefined);
    render(<BoardIndex />);
    expect(await screen.findByText('최근 7일 인기 글이 없습니다.')).toBeInTheDocument();
  });

  it('쪽 넘김: 끝에 닿은 단추는 사유와 함께 잠긴다', async () => {
    override = (url) => {
      if (!url.includes('size=20')) return undefined;
      const at = Number(new URL(url, 'http://x').searchParams.get('page'));
      return ok(page([post(100 + at, `쪽 ${at + 1} 글`)], { page: at, totalPages: 3, totalElements: 41 }));
    };
    render(<BoardIndex />);
    await screen.findByRole('link', { name: '쪽 1 글' });
    const pager = screen.getByRole('navigation', { name: '게시글 쪽' });
    expect(within(pager).getByRole('button', { name: '이전' })).toHaveAttribute('data-reason', '첫 쪽입니다');
    expect(within(pager).getByText('1 / 3')).toBeInTheDocument();
    fireEvent.click(within(pager).getByRole('button', { name: '다음' }));
    await screen.findByRole('link', { name: '쪽 2 글' });
    fireEvent.click(within(screen.getByRole('navigation', { name: '게시글 쪽' })).getByRole('button', { name: '다음' }));
    await screen.findByRole('link', { name: '쪽 3 글' });
    expect(within(screen.getByRole('navigation', { name: '게시글 쪽' })).getByRole('button', { name: '다음' })).toHaveAttribute('data-reason', '마지막 쪽입니다');
  });
});
