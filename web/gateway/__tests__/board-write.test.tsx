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

vi.mock('@/components/board/BoardRichTextEditor', () => ({
  default: ({ ariaLabel, onChange }: { readonly ariaLabel: string; readonly onChange: (html: string) => void }) => (
    <div>
      <div aria-label="서식 도구" role="toolbar">
        <button aria-label="굵게" type="button">굵게</button>
        <button aria-label="기울임" type="button">기울임</button>
        <button aria-label="취소선" type="button">취소선</button>
      </div>
      <div
        aria-label={ariaLabel}
        contentEditable
        onInput={(event) => onChange(event.currentTarget.innerHTML)}
        role="textbox"
      />
    </div>
  ),
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

  it('sends rich text from the StarterKit editor through the authenticated proxy', async () => {
    render(<BoardWritePage />);
    expect(screen.getByRole('link', { name: '취소' })).toHaveClass('board-heading-action');
    expect(screen.getByLabelText('제목')).toHaveAttribute('maxLength', '120');
    expect(screen.getByRole('button', { name: '굵게' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '기울임' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '취소선' })).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('제목'), { target: { value: '새 글' } });
    fireEvent.input(screen.getByRole('textbox', { name: '내용' }), {
      target: { innerHTML: '<p><strong>첫 줄</strong><br>둘째 줄</p>' },
    });
    fireEvent.click(screen.getByRole('button', { name: '등록' }));

    await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/board/posts', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        category: 'FREE',
        title: '새 글',
        content: '<p><strong>첫 줄</strong><br>둘째 줄</p>',
        contentFormat: 'RICH_HTML',
      }),
    }));
    expect(push).toHaveBeenCalledWith('/board/posts/50');
  });

  it('rejects rich text with no visible content before calling the proxy', () => {
    render(<BoardWritePage />);
    fireEvent.change(screen.getByLabelText('제목'), { target: { value: '빈 글' } });
    fireEvent.input(screen.getByRole('textbox', { name: '내용' }), {
      target: { innerHTML: '<p><br></p>' },
    });
    fireEvent.click(screen.getByRole('button', { name: '등록' }));

    expect(screen.getByRole('alert')).toHaveTextContent('제목과 내용을 모두 입력해주세요.');
    expect(fetch).not.toHaveBeenCalled();
  });

  it('uses the backend UTF-16 length contract for astral characters', () => {
    render(<BoardWritePage />);
    fireEvent.change(screen.getByLabelText('제목'), { target: { value: '긴 글' } });
    fireEvent.input(screen.getByRole('textbox', { name: '내용' }), {
      target: { innerHTML: `<p>${'𠮷'.repeat(5001)}</p>` },
    });
    fireEvent.click(screen.getByRole('button', { name: '등록' }));

    expect(screen.getByRole('alert')).toHaveTextContent('내용은 10000자 이내로 입력해주세요.');
    expect(fetch).not.toHaveBeenCalled();
  });
});
