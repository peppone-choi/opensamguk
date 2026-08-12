import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import BoardWritePage from '@/app/board/write/page';

const replace = vi.fn();
const push = vi.fn();
const refresh = vi.fn();
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
  useRouter: () => ({ push, replace, refresh }),
}));

vi.mock('@/lib/auth-context', () => ({
  useAuth: () => ({ user: authUser, loading: false, refresh: vi.fn(), logout: vi.fn() }),
}));

function response(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 201,
    headers: { 'Content-Type': 'application/json' },
  });
}

const createdPost = {
  id: 50,
  category: 'FREE',
  authorName: '글쓴이',
  title: '새 글',
  contentHtml: '첫 줄<br>둘째 줄',
  pinned: false,
  canDelete: true,
  deleted: false,
  createdAt: '2026-08-12T09:00:00Z',
  updatedAt: '2026-08-12T09:00:00Z',
};

describe('gateway board write', () => {
  beforeEach(() => {
    authUser = {
      id: 1,
      username: 'tester',
      email: null,
      nickname: '테스터',
      role: 'USER',
      picture: null,
      imageServer: 0,
    };
    vi.clearAllMocks();
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input) === '/api/board/posts' && init?.method === 'POST') {
        return Promise.resolve(response(createdPost));
      }
      return Promise.resolve(new Response(JSON.stringify({ message: '없음', status: 404 }), { status: 404 }));
    }));
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('sends a plaintext post through the authenticated proxy and opens the created detail', async () => {
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
});
