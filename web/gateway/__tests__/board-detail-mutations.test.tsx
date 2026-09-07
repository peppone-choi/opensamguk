import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import BoardPostDetail from '@/app/board/posts/[postId]/page';

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
  useAuthOptional: () => ({ user: authUser, loading: false, refresh: vi.fn(), logout: vi.fn() }),
}));

type FetchResponse = { readonly status?: number; readonly body?: unknown };

function response({ status = 200, body = {} }: FetchResponse): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

const detailBody = {
  post: {
    id: 42,
    category: 'FREE',
    authorName: '글쓴이',
    title: '제목',
    contentHtml: '본문',
    pinned: false,
    canDelete: true,
    deleted: false,
    createdAt: '2026-08-12T09:00:00Z',
    updatedAt: '2026-08-12T09:00:00Z',
  },
  comments: [
    {
      id: 4,
      authorName: '댓글 작성자',
      content: '<img src=x onerror=alert(1)>',
      canDelete: true,
      deleted: false,
      createdAt: '2026-08-12T10:00:00Z',
    },
  ],
};

function signedInUser(role = 'USER') {
  return {
    id: 1,
    username: role === 'ADMIN' ? 'admin' : 'tester',
    email: null,
    nickname: role === 'ADMIN' ? '관리자' : '테스터',
    role,
    picture: null,
    imageServer: 0,
  };
}

describe('gateway board detail mutations', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    authUser = signedInUser();
    routeParams = { postId: '42' };
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
      if (String(input).startsWith('/api/board/posts/42')) return Promise.resolve(response({ body: detailBody }));
      return Promise.resolve(response({ status: 404, body: { message: '없음', status: 404 } }));
    }));
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('sends an authenticated delete request and leaves ownership enforcement to the gateway', async () => {
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path === '/api/board/posts/42' && init?.method === 'DELETE') return Promise.resolve(new Response(null, { status: 204 }));
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
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path === '/api/board/posts/42/comments/4' && init?.method === 'DELETE') return Promise.resolve(new Response(null, { status: 204 }));
      if (path.startsWith('/api/board/posts/42')) return Promise.resolve(response({ body: detailBody }));
      return Promise.resolve(response({ status: 404, body: { message: '없음', status: 404 } }));
    }));

    render(<BoardPostDetail />);
    await screen.findByRole('heading', { name: /제목/ });
    fireEvent.click(screen.getByRole('button', { name: '댓글 삭제' }));

    await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/board/posts/42/comments/4', { method: 'DELETE' }));
    await waitFor(() => expect(screen.queryByText('<img src=x onerror=alert(1)>')).toBeNull());
  });

  it('shows post deletion only when the server authorizes that post', async () => {
    const detailWithCommentOnlyPermission = {
      post: { ...detailBody.post, canDelete: false },
      comments: [{ ...detailBody.comments[0], canDelete: true }],
    };
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ body: detailWithCommentOnlyPermission })));

    render(<BoardPostDetail />);
    await screen.findByRole('heading', { name: /제목/ });

    expect(screen.queryByRole('button', { name: '게시글 삭제' })).toBeNull();
    expect(screen.getByRole('button', { name: '댓글 삭제' })).toBeInTheDocument();
  });

  it('shows comment deletion only when the server authorizes that comment', async () => {
    const detailWithPostOnlyPermission = {
      post: { ...detailBody.post, canDelete: true },
      comments: [{ ...detailBody.comments[0], canDelete: false }],
    };
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ body: detailWithPostOnlyPermission })));

    render(<BoardPostDetail />);
    await screen.findByRole('heading', { name: /제목/ });

    expect(screen.getByRole('button', { name: '게시글 삭제' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '댓글 삭제' })).toBeNull();
  });

  it('preserves a loaded post and shows an inline alert when deletion is forbidden', async () => {
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path === '/api/board/posts/42' && init?.method === 'DELETE') {
        return Promise.resolve(response({ status: 403, body: { message: '게시글을 삭제할 권한이 없습니다.', status: 403 } }));
      }
      if (path.startsWith('/api/board/posts/42')) return Promise.resolve(response({ body: detailBody }));
      return Promise.resolve(response({ status: 404, body: { message: '없음', status: 404 } }));
    }));

    render(<BoardPostDetail />);
    await screen.findByRole('heading', { name: '제목' });
    fireEvent.click(screen.getByRole('button', { name: '게시글 삭제' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('게시글을 삭제할 권한이 없습니다.');
    expect(screen.getByRole('heading', { name: '제목' })).toBeInTheDocument();
    expect(push).not.toHaveBeenCalled();
  });

  it('lets an administrator toggle a post pin through the authenticated proxy', async () => {
    authUser = signedInUser('ADMIN');
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
