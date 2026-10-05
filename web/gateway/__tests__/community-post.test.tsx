import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import BoardPostPage from '@/app/board/posts/[postId]/page';

const mocks = vi.hoisted(() => ({ user: null as Record<string, unknown> | null, push: vi.fn() }));
vi.mock('next/link', () => ({ default: ({ href, children, ...rest }: React.AnchorHTMLAttributes<HTMLAnchorElement> & { href: string }) => <a href={href} {...rest}>{children}</a> }));
vi.mock('next/navigation', () => ({ useParams: () => ({ postId: '42' }), useRouter: () => ({ push: mocks.push, replace: vi.fn(), refresh: vi.fn() }) }));
vi.mock('@/lib/auth-context', () => {
  const session = () => ({ user: mocks.user, loading: false, refresh: vi.fn(), logout: vi.fn() });
  return { useAuth: session, useAuthOptional: session };
});

const MEMBER = { id: 2, username: 'reader', email: null, nickname: '독자', role: 'USER', picture: null, imageServer: 0 };
const detail = (extra: Record<string, unknown> = {}) => ({
  post: {
    id: 42, category: 'STRATEGY', authorName: '북풍', title: '영천군 창고', contentHtml: '<p>본문</p>', pinned: false, canDelete: false, deleted: false,
    createdAt: '2026-09-29T13:41:00Z', updatedAt: '2026-09-30T01:00:00Z', viewCount: 812, commentCount: 1, authorGeneralName: '안량', authorWorldId: 1, ...extra,
  },
  comments: [{ id: 4, authorName: '솔바람', content: '좋은 글 고맙습니다.', canDelete: false, deleted: false, createdAt: '2026-09-29T14:40:00Z' }],
});
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

describe('P-G07 커뮤니티 글', () => {
  beforeEach(() => {
    mocks.user = MEMBER;
    vi.clearAllMocks();
  });
  afterEach(() => vi.unstubAllGlobals());

  it('머리: 대표 장수 칩(월드 번호 없음) · 「조회 N」 · 목록으로', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json(detail())));
    render(<BoardPostPage />);
    expect(await screen.findByRole('heading', { level: 1, name: '영천군 창고' })).toBeInTheDocument();
    expect(screen.getByText('대표 장수 · 안량')).toBeInTheDocument();
    expect(screen.queryByText(/월드/)).toBeNull();
    expect(screen.getByText(/조회 812$/)).toBeInTheDocument();
    expect(screen.getAllByRole('link', { name: '게시판 목록' })[0]).toHaveAttribute('href', '/board');
    expect(screen.getByRole('heading', { level: 2, name: '댓글 1' })).toBeInTheDocument();
  });

  it('고정만 바뀐 글에는 「수정됨」이 붙지 않는다(updatedAt 은 고정 · 해제 때도 바뀐다 — #1211 리뷰)', async () => {
    // board-api updatePin 이 고정 · 해제 때 updatedAt 을 갱신한다. 내용 수정 시각(editedAt)을 서버가 따로 줄 때까지 「수정됨」은 보이지 않는다.
    vi.stubGlobal('fetch', vi.fn(async () => json(detail({ pinned: true, createdAt: '2026-09-29T13:41:00Z', updatedAt: '2026-10-02T09:00:00Z' }))));
    render(<BoardPostPage />);
    expect(await screen.findByRole('heading', { level: 1, name: '영천군 창고' })).toBeInTheDocument();
    expect(screen.getByText(/조회 812$/)).toBeInTheDocument();
    expect(screen.queryByText(/수정됨/)).toBeNull();
  });

  it('신고: 사유가 비면 잠기고, 서버 거절은 시트 안에, 접수되면 시트를 닫고 알린다', async () => {
    let refused = true;
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      if (url === '/api/board/posts/42/report' && init?.method === 'POST') {
        return refused ? json({ message: '이미 신고한 게시글입니다.', status: 409 }, 409)
          : json({ id: 1, postId: 42, commentId: null, targetSummary: '영천군 창고', reporterName: '독자', reason: '광고 글입니다', status: 'OPEN', createdAt: '2026-09-30T02:00:00Z', handledAt: null });
      }
      return json(detail());
    }));
    render(<BoardPostPage />);
    await screen.findByRole('heading', { level: 1, name: '영천군 창고' });
    fireEvent.click(screen.getAllByRole('button', { name: '신고' })[0]);
    const sheet = await screen.findByRole('dialog', { name: '글 신고' });
    expect(within(sheet).getByRole('button', { name: '신고 접수' })).toHaveAttribute('data-reason', '사유를 쓰세요');
    expect(within(sheet).getByText('운영자가 확인합니다. 이미 신고해 아직 처리되지 않은 글은 다시 신고할 수 없습니다.')).toBeInTheDocument();
    fireEvent.change(within(sheet).getByLabelText('신고 사유'), { target: { value: '광고 글입니다' } });
    fireEvent.click(within(sheet).getByRole('button', { name: '신고 접수' }));
    expect(await within(sheet).findByRole('alert')).toHaveTextContent('이미 신고한 게시글입니다.');
    refused = false;
    fireEvent.click(within(sheet).getByRole('button', { name: '신고 접수' }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: '글 신고' })).toBeNull());
    // 대화상자 DOM 은 커밋 때 빠지지만, 배경 격리(aria-hidden · inert)는 공용 Modal 스택이 effect 정리에서 푼다.
    // 그 사이 알림 줄은 숨은 조상 밑이라 접근성 트리에 없다 — 동기로 찾으면 부하에 따라 갈린다(main run 37265236111). 풀릴 때까지 기다린다.
    expect(await screen.findByRole('status')).toHaveTextContent('신고를 접수했습니다. 운영자가 확인합니다.');
    expect(fetch).toHaveBeenLastCalledWith('/api/board/posts/42/report', expect.objectContaining({ method: 'POST' }));
  });

  it('댓글 등록은 비면 잠기고, 등록되면 목록에 붙는다', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => (init?.method === 'POST'
      ? json({ id: 5, authorName: '독자', content: '저도 해 봤습니다.', canDelete: true, deleted: false, createdAt: '2026-09-30T02:00:00Z' })
      : json(detail()))));
    render(<BoardPostPage />);
    await screen.findByRole('heading', { level: 1, name: '영천군 창고' });
    expect(screen.getByRole('button', { name: '댓글 등록' })).toHaveAttribute('data-reason', '댓글 내용을 쓰세요');
    fireEvent.change(screen.getByLabelText('댓글'), { target: { value: '저도 해 봤습니다.' } });
    fireEvent.click(screen.getByRole('button', { name: '댓글 등록' }));
    expect(await screen.findByText('저도 해 봤습니다.')).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 2, name: '댓글 2' })).toBeInTheDocument();
  });

  it('손님은 신고 · 댓글 대신 로그인 안내를 본다', async () => {
    mocks.user = null;
    vi.stubGlobal('fetch', vi.fn(async () => json(detail())));
    render(<BoardPostPage />);
    await screen.findByRole('heading', { level: 1, name: '영천군 창고' });
    expect(screen.queryByRole('button', { name: '신고' })).toBeNull();
    expect(screen.queryByLabelText('댓글')).toBeNull();
    expect(screen.getAllByRole('link', { name: '로그인' }).some((link) => link.getAttribute('href') === '/login?next=%2Fboard%2Fposts%2F42')).toBe(true);
  });

  it('없는 글과 읽기 실패를 가른다(실패만 다시 시도)', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({ message: '게시글을 찾을 수 없습니다.', status: 404 }, 404)));
    const { unmount } = render(<BoardPostPage />);
    expect(await screen.findByText('게시글을 찾을 수 없습니다')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '다시 시도' })).toBeNull();
    unmount();
    let calls = 0;
    vi.stubGlobal('fetch', vi.fn(async () => (++calls === 1 ? json({ message: '게시판 서버에 연결할 수 없습니다.', status: 502 }, 502) : json(detail()))));
    render(<BoardPostPage />);
    expect(await screen.findByText('게시판 서버에 연결할 수 없습니다.')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    expect(await screen.findByRole('heading', { level: 1, name: '영천군 창고' })).toBeInTheDocument();
  });
});
