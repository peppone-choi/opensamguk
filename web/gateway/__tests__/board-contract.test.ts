import { afterEach, describe, expect, it, vi } from 'vitest';
import { fetchBoardPost } from '@/lib/board';

const completePost = {
  id: 42,
  category: 'FREE',
  authorName: '글쓴이',
  title: '제목',
  contentHtml: '본문',
  pinned: false,
  canDelete: false,
  deleted: false,
  createdAt: '2026-08-12T09:00:00Z',
  updatedAt: '2026-08-12T09:00:00Z',
};

const completeComment = {
  id: 4,
  authorName: '댓글 작성자',
  content: '댓글',
  canDelete: false,
  deleted: false,
  createdAt: '2026-08-12T10:00:00Z',
};

function detailResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}

describe('gateway board DTO contract', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('rejects a detailed response that omits the server-authoritative post permission', async () => {
    const { canDelete: _permission, ...postWithoutPermission } = completePost;
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(detailResponse({ post: postWithoutPermission, comments: [completeComment] })));

    await expect(fetchBoardPost('42')).rejects.toMatchObject({
      status: 502,
      message: '게시글 응답이 올바르지 않습니다.',
    });
  });

  it('rejects a detailed response that omits the server-authoritative comment permission', async () => {
    const { canDelete: _permission, ...commentWithoutPermission } = completeComment;
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(detailResponse({ post: completePost, comments: [commentWithoutPermission] })));

    await expect(fetchBoardPost('42')).rejects.toMatchObject({
      status: 502,
      message: '게시글 응답이 올바르지 않습니다.',
    });
  });
});
