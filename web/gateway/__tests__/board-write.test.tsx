import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import BoardWritePage from '@/app/board/write/page';
import CommunityWrite from '@/components/community/CommunityWrite';

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
  useAuthOptional: () => ({ user: authUser, loading: false, refresh: vi.fn(), logout: vi.fn() }),
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

const editable = { ...createdPost, id: 42, category: 'STRATEGY', title: '고칠 글', contentHtml: '<p>원래 본문</p>' };

function stubFetch(detail: Record<string, unknown> = editable) {
  vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (path === '/api/board/posts' && init?.method === 'POST') return Promise.resolve(response(createdPost));
    if (path === '/api/board/posts/42' && init?.method === 'PATCH') return Promise.resolve(response({ ...editable, title: '고친 글' }));
    if (path === '/api/board/posts/42') return Promise.resolve(new Response(JSON.stringify({ post: detail, comments: [] }), { status: 200 }));
    return Promise.resolve(new Response(JSON.stringify({ message: '없음', status: 404 }), { status: 404 }));
  }));
}

function type(title: string, html: string) {
  fireEvent.change(screen.getByLabelText('제목'), { target: { value: title } });
  fireEvent.input(screen.getByRole('textbox', { name: '내용' }), { target: { innerHTML: html } });
}

describe('P-G08 커뮤니티 글쓰기', () => {
  beforeEach(() => {
    authUser = { id: 1, username: 'tester', email: null, nickname: '테스터', role: 'USER', picture: null, imageServer: 0 };
    vi.clearAllMocks();
    stubFetch();
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('sends rich text from the StarterKit editor through the authenticated proxy', async () => {
    render(<CommunityWrite editId={null} />);
    expect(screen.getByRole('heading', { level: 1, name: '게시글 작성' })).toBeInTheDocument();
    expect(screen.getByLabelText('제목')).toHaveAttribute('maxLength', '120');
    for (const name of ['굵게', '기울임', '취소선']) expect(screen.getByRole('button', { name })).toBeInTheDocument();
    type('새 글', '<p><strong>첫 줄</strong><br>둘째 줄</p>');
    fireEvent.click(screen.getByRole('button', { name: '등록' }));

    await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/board/posts', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ category: 'FREE', title: '새 글', content: '<p><strong>첫 줄</strong><br>둘째 줄</p>', contentFormat: 'RICH_HTML' }),
    }));
    expect(push).toHaveBeenCalledWith('/board/posts/50');
  });

  it('locks 등록 with a reason while title or visible content is missing', () => {
    render(<CommunityWrite editId={null} />);
    type('빈 글', '<p><br></p>');
    const submit = screen.getByRole('button', { name: '등록' });
    expect(submit).toHaveAttribute('aria-disabled', 'true');
    expect(submit).toHaveAttribute('data-reason', '제목과 내용을 모두 입력해주세요');
    fireEvent.click(submit);
    expect(fetch).not.toHaveBeenCalled();
  });

  it('uses the backend UTF-16 length contract for astral characters', () => {
    render(<CommunityWrite editId={null} />);
    type('긴 글', `<p>${'𠮷'.repeat(5001)}</p>`);
    expect(screen.getByRole('button', { name: '등록' })).toHaveAttribute('data-reason', '내용은 10000자 이내로 입력해주세요');
    expect(fetch).not.toHaveBeenCalled();
  });

  it('shows 공지 only to administrators', () => {
    const { unmount } = render(<CommunityWrite editId={null} />);
    const boards = () => within(screen.getByRole('radiogroup', { name: '게시판' })).getAllByRole('radio').map((r) => r.textContent);
    expect(boards()).toEqual(['자유', '건의', '전략·공략', '서버 이야기', '창작·일지']);
    unmount();
    authUser = { ...authUser!, role: 'ADMIN' };
    render(<CommunityWrite editId={null} />);
    expect(boards()[0]).toBe('공지');
  });

  it('asks before throwing away a draft, and leaves at once when nothing was written', async () => {
    const { unmount } = render(<CommunityWrite editId={null} />);
    fireEvent.click(screen.getByRole('button', { name: '취소' }));
    expect(push).toHaveBeenCalledWith('/board');
    unmount();
    push.mockClear();
    render(<CommunityWrite editId={null} />);
    type('쓰던 글', '<p>반쯤</p>');
    fireEvent.click(screen.getByRole('button', { name: '취소' }));
    const dialog = await screen.findByRole('dialog', { name: '쓰던 글 버리기' });
    fireEvent.click(within(dialog).getByRole('button', { name: '계속 쓰기' }));
    expect(push).not.toHaveBeenCalled();
    expect(screen.getByLabelText('제목')).toHaveValue('쓰던 글');
    fireEvent.click(screen.getByRole('button', { name: '취소' }));
    fireEvent.click(within(await screen.findByRole('dialog', { name: '쓰던 글 버리기' })).getByRole('button', { name: '버리기' }));
    expect(push).toHaveBeenCalledWith('/board');
  });

  it('edits an existing post through PATCH when the server allows it', async () => {
    render(<CommunityWrite editId="42" />);
    expect(await screen.findByDisplayValue('고칠 글')).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1, name: '게시글 수정' })).toBeInTheDocument();
    expect(within(screen.getByRole('radiogroup', { name: '게시판' })).getByRole('radio', { name: '전략·공략' })).toHaveAttribute('aria-checked', 'true');
    fireEvent.change(screen.getByLabelText('제목'), { target: { value: '고친 글' } });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));
    await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/board/posts/42', {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ category: 'STRATEGY', title: '고친 글', content: '<p>원래 본문</p>', contentFormat: 'RICH_HTML' }),
    }));
    expect(push).toHaveBeenCalledWith('/board/posts/42');
  });

  it('refuses to open the editor for someone who may not change the post', async () => {
    stubFetch({ ...editable, canDelete: false });
    render(<CommunityWrite editId="42" />);
    expect(await screen.findByText('작성자 또는 관리자만 변경할 수 있습니다.')).toBeInTheDocument();
    expect(screen.queryByLabelText('제목')).toBeNull();
  });

  it('asks a guest to log in and keeps the way back', () => {
    authUser = null;
    render(<CommunityWrite editId="42" />);
    expect(within(screen.getByRole('main')).getByRole('link', { name: '로그인' })).toHaveAttribute('href', '/login?next=%2Fboard%2Fwrite%3Fedit%3D42');
    expect(fetch).not.toHaveBeenCalled();
  });

  it('only treats a numeric ?edit= as edit mode', async () => {
    render(await BoardWritePage({ searchParams: Promise.resolve({ edit: '../x' }) }));
    expect(screen.getByRole('heading', { level: 1, name: '게시글 작성' })).toBeInTheDocument();
  });
});
