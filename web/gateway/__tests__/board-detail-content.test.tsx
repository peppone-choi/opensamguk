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
    authorName: '글쓴이 <script>alert(1)</script>',
    title: '제목 <img src=x onerror=alert(1)>',
    contentHtml: '안전한 본문&lt;img src=x onerror=alert(1)&gt;<br>두 번째 줄',
    pinned: false,
    canDelete: false,
    deleted: false,
    createdAt: '2026-08-12T09:00:00Z',
    updatedAt: '2026-08-12T09:00:00Z',
  },
  comments: [
    {
      id: 4,
      authorName: '댓글 작성자',
      content: '<img src=x onerror=alert(1)>',
      canDelete: false,
      deleted: false,
      createdAt: '2026-08-12T10:00:00Z',
    },
  ],
};

describe('gateway board detail content', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    authUser = null;
    routeParams = { postId: '42' };
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
      const path = String(input);
      if (path.startsWith('/api/board/posts/42')) return Promise.resolve(response({ body: detailBody }));
      return Promise.resolve(response({ status: 404, body: { message: '없음', status: 404 } }));
    }));
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('renders only server-owned contentHtml as HTML and keeps author title and comments as text', async () => {
    render(<BoardPostDetail />);

    expect(await screen.findByRole('heading', { name: /제목 <img src=x onerror=alert\(1\)>/ })).toBeInTheDocument();
    expect(screen.getByText('글쓴이 <script>alert(1)</script>')).toBeInTheDocument();
    expect(screen.getByText('<img src=x onerror=alert(1)>')).toBeInTheDocument();
    const content = document.querySelector<HTMLElement>('.board-post-content');
    expect(content).not.toBeNull();
    expect(content?.innerHTML).toBe('안전한 본문&lt;img src=x onerror=alert(1)&gt;<br>두 번째 줄');
    expect(content?.querySelector('img')).toBeNull();
    expect(content?.querySelector('script')).toBeNull();
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
          canDelete: true,
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
});
