import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import BoardIndex from '@/app/board/page';
import BoardPostDetail from '@/app/board/posts/[postId]/page';
import BoardWritePage from '@/app/board/write/page';

const replace = vi.fn();
const push = vi.fn();
const refresh = vi.fn();
let routeParams: { postId: string } = { postId: '42' };
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

vi.mock('next/navigation', () => ({
  useParams: () => routeParams,
  useRouter: () => ({ push, replace, refresh }),
}));

vi.mock('@/lib/auth-context', () => ({
  useAuth: () => ({ user: authUser, loading: false, refresh: vi.fn(), logout: vi.fn() }),
}));

type FetchResponse = { readonly status?: number; readonly body?: unknown };

function response({ status = 200, body = {} }: FetchResponse): Response {
  return new Response(JSON.stringify(body), {
    status,
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

const detailBody = {
  post: {
    id: 42,
    category: 'FREE',
    authorName: '글쓴이 <script>alert(1)</script>',
    title: '제목 <img src=x onerror=alert(1)>',
    contentHtml: '안전한 본문<br>두 번째 줄',
    pinned: false,
    deleted: false,
    createdAt: '2026-08-12T09:00:00Z',
    updatedAt: '2026-08-12T09:00:00Z',
  },
  comments: [
    {
      id: 4,
      authorName: '댓글 작성자',
      content: '<img src=x onerror=alert(1)>',
      deleted: false,
      createdAt: '2026-08-12T10:00:00Z',
    },
  ],
};

describe('gateway board pages', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    authUser = null;
    routeParams = { postId: '42' };
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
      const path = String(input);
      if (path.startsWith('/api/board/posts/42')) return Promise.resolve(response({ body: detailBody }));
      if (path.startsWith('/api/board/posts')) return Promise.resolve(response({ body: listBody }));
      return Promise.resolve(response({ status: 404, body: { message: '없음', status: 404 } }));
    }));
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
    expect(screen.getByRole('link', { name: '로그인 후 글쓰기' })).toHaveAttribute('href', '/login?next=%2Fboard%2Fwrite');
    expect(fetch).toHaveBeenCalledWith('/api/board/posts?category=NOTICE&page=0&size=20', { cache: 'no-store' });
  });

  it('changes category through the public API and preserves its query contract', async () => {
    render(<BoardIndex />);
    await screen.findByRole('link', { name: '서버 점검 안내' });

    fireEvent.click(screen.getByRole('tab', { name: '자유' }));

    await waitFor(() => expect(fetch).toHaveBeenLastCalledWith(
      '/api/board/posts?category=FREE&page=0&size=20',
      { cache: 'no-store' },
    ));
  });

  it('renders only server-owned contentHtml as HTML and keeps author title and comments as text', async () => {
    render(<BoardPostDetail />);

    expect(await screen.findByRole('heading', { name: /제목 <img src=x onerror=alert\(1\)>/ })).toBeInTheDocument();
    expect(screen.getByText('글쓴이 <script>alert(1)</script>')).toBeInTheDocument();
    expect(screen.getByText('<img src=x onerror=alert(1)>')).toBeInTheDocument();
    const content = document.querySelector<HTMLElement>('.board-post-content');
    expect(content).not.toBeNull();
    expect(content?.innerHTML).toBe('안전한 본문<br>두 번째 줄');
    expect(document.querySelector('img')).toBeNull();
    expect(document.querySelector('script')).toBeNull();
  });

  it('sends a plaintext comment through the authenticated proxy and displays the created comment', async () => {
    authUser = {
      id: 1,
      username: 'tester',
      email: null,
      nickname: '테스터',
      role: 'USER',
      picture: null,
      imageServer: 0,
    };
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path === '/api/board/posts/42/comments' && init?.method === 'POST') {
        return Promise.resolve(response({ status: 201, body: {
          id: 5,
          authorName: '테스터',
          content: '새 댓글',
          deleted: false,
          createdAt: '2026-08-12T10:30:00Z',
        } }));
      }
      if (path.startsWith('/api/board/posts/42')) return Promise.resolve(response({ body: detailBody }));
      return Promise.resolve(response({ status: 404, body: { message: '없음', status: 404 } }));
    }));

    render(<BoardPostDetail />);
    await screen.findByRole('heading', { name: /제목/ });
    expect(screen.getByLabelText('댓글')).toHaveAttribute('maxLength', '2000');
    fireEvent.change(screen.getByLabelText('댓글'), { target: { value: '새 댓글' } });
    fireEvent.click(screen.getByRole('button', { name: '댓글 등록' }));

    await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/board/posts/42/comments', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ content: '새 댓글' }),
    }));
    expect(await screen.findByText('새 댓글')).toBeInTheDocument();
  });

  it('sends a plaintext post through the authenticated proxy and opens the created detail', async () => {
    authUser = {
      id: 1,
      username: 'tester',
      email: null,
      nickname: '테스터',
      role: 'USER',
      picture: null,
      imageServer: 0,
    };
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path === '/api/board/posts' && init?.method === 'POST') {
        return Promise.resolve(response({ status: 201, body: {
          ...detailBody.post,
          id: 50,
          title: '새 글',
          contentHtml: '첫 줄<br>둘째 줄',
        } }));
      }
      return Promise.resolve(response({ status: 404, body: { message: '없음', status: 404 } }));
    }));

    render(<BoardWritePage />);
    expect(screen.getByLabelText('제목')).toHaveAttribute('maxLength', '120');
    expect(screen.getByLabelText('내용')).toHaveAttribute('maxLength', '10000');
    fireEvent.change(screen.getByLabelText('제목'), { target: { value: '새 글' } });
    fireEvent.change(screen.getByLabelText('내용'), { target: { value: '첫 줄\n둘째 줄' } });
    fireEvent.click(screen.getByRole('button', { name: '등록' }));

    await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/board/posts', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ category: 'FREE', title: '새 글', content: '첫 줄\n둘째 줄' }),
    }));
    expect(push).toHaveBeenCalledWith('/board/posts/50');
  });

  it('sends an authenticated delete request and leaves ownership enforcement to the gateway', async () => {
    authUser = {
      id: 1,
      username: 'tester',
      email: null,
      nickname: '테스터',
      role: 'USER',
      picture: null,
      imageServer: 0,
    };
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path === '/api/board/posts/42' && init?.method === 'DELETE') {
        return Promise.resolve(new Response(null, { status: 204 }));
      }
      if (path.startsWith('/api/board/posts/42')) return Promise.resolve(response({ body: detailBody }));
      return Promise.resolve(response({ status: 404, body: { message: '없음', status: 404 } }));
    }));

    render(<BoardPostDetail />);
    await screen.findByRole('heading', { name: /제목/ });

    fireEvent.click(screen.getByRole('button', { name: '게시글 삭제' }));

    await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/board/posts/42', { method: 'DELETE' }));
    expect(push).toHaveBeenCalledWith('/board');
  });

  it('deletes a comment through its nested post route', async () => {
    authUser = {
      id: 1,
      username: 'tester',
      email: null,
      nickname: '테스터',
      role: 'USER',
      picture: null,
      imageServer: 0,
    };
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path === '/api/board/posts/42/comments/4' && init?.method === 'DELETE') {
        return Promise.resolve(new Response(null, { status: 204 }));
      }
      if (path.startsWith('/api/board/posts/42')) return Promise.resolve(response({ body: detailBody }));
      return Promise.resolve(response({ status: 404, body: { message: '없음', status: 404 } }));
    }));

    render(<BoardPostDetail />);
    await screen.findByRole('heading', { name: /제목/ });
    fireEvent.click(screen.getByRole('button', { name: '댓글 삭제' }));

    await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/board/posts/42/comments/4', { method: 'DELETE' }));
    await waitFor(() => expect(screen.queryByText('<img src=x onerror=alert(1)>')).toBeNull());
  });

  it('lets an administrator toggle a post pin through the authenticated proxy', async () => {
    authUser = {
      id: 1,
      username: 'admin',
      email: null,
      nickname: '관리자',
      role: 'ADMIN',
      picture: null,
      imageServer: 0,
    };
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path === '/api/board/posts/42/pin' && init?.method === 'PATCH') {
        return Promise.resolve(response({ body: { ...detailBody.post, pinned: true } }));
      }
      if (path.startsWith('/api/board/posts/42')) return Promise.resolve(response({ body: detailBody }));
      return Promise.resolve(response({ status: 404, body: { message: '없음', status: 404 } }));
    }));

    render(<BoardPostDetail />);
    await screen.findByRole('heading', { name: /제목/ });
    fireEvent.click(screen.getByRole('button', { name: '게시글 고정' }));

    await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/board/posts/42/pin', {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ pinned: true }),
    }));
    expect(screen.getByText('고정')).toBeInTheDocument();
  });
});
