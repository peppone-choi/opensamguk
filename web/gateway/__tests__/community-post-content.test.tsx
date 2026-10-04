import { render, screen } from '@testing-library/react';
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import BoardPostPage from '@/app/board/posts/[postId]/page';

// 커뮤니티 글 본문은 서버(board-api)가 정리한 HTML 을 화면에서 한 번 더 정리해 그린다 — 서버 허용 목록과 같은 태그만, 속성 없이.
vi.mock('next/link', () => ({ default: ({ href, children, ...rest }: React.AnchorHTMLAttributes<HTMLAnchorElement> & { href: string }) => <a href={href} {...rest}>{children}</a> }));
vi.mock('next/navigation', () => ({ useParams: () => ({ postId: '42' }), useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }) }));
vi.mock('@/lib/auth-context', () => {
  const session = () => ({ user: null, loading: false, refresh: vi.fn(), logout: vi.fn() });
  return { useAuth: session, useAuthOptional: session };
});

const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
const withContent = (contentHtml: string) => ({
  post: { id: 42, category: 'STRATEGY', authorName: '북풍', title: '본문 정리', contentHtml, pinned: false, canDelete: false, deleted: false,
    createdAt: '2026-09-29T13:41:00Z', updatedAt: '2026-09-29T13:41:00Z', viewCount: 1, commentCount: 0 },
  comments: [],
});

async function content(html: string): Promise<HTMLElement> {
  vi.stubGlobal('fetch', vi.fn(async () => json(withContent(html))));
  const { container } = render(<BoardPostPage />);
  await screen.findByRole('heading', { level: 1, name: '본문 정리' });
  return container.querySelector('.gw31-post__content') as HTMLElement;
}

describe('커뮤니티 글 본문 — 허용 태그만 그린다', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('편집기가 내는 태그는 그대로 그린다(양성 대조)', async () => {
    const el = await content('<h2>소제목</h2><p><strong>굵게</strong> <em>기울임</em> <s>취소선</s></p><ul><li>하나</li></ul><blockquote>인용</blockquote><pre><code>x</code></pre>');
    for (const tag of ['h2', 'p', 'strong', 'em', 's', 'ul', 'li', 'blockquote', 'pre', 'code']) expect(el.querySelector(tag), tag).not.toBeNull();
    expect(el).toHaveTextContent('굵게 기울임 취소선');
  });

  it('허용 밖 태그와 모든 속성은 그리지 않는다', async () => {
    const el = await content('<p style="color:red" class="x" onclick="void 0">글</p><img src="x.png"><a href="https://example.com">고리</a><iframe src="https://example.com"></iframe><svg></svg><script>void 0</script>');
    for (const tag of ['img', 'a', 'iframe', 'svg', 'script']) expect(el.querySelector(tag), tag).toBeNull();
    const p = el.querySelector('p') as HTMLElement;
    expect(p.getAttributeNames()).toEqual([]);
    expect(el).toHaveTextContent('글');
  });
});
