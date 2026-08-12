export const BOARD_CATEGORIES = [
  { value: 'NOTICE', label: '공지' },
  { value: 'FREE', label: '자유' },
  { value: 'SUGGESTION', label: '건의' },
] as const;

export type BoardCategory = (typeof BOARD_CATEGORIES)[number]['value'];

export type BoardPost = {
  readonly id: number;
  readonly category: BoardCategory;
  readonly authorName: string;
  readonly title: string;
  readonly contentHtml: string;
  readonly pinned: boolean;
  readonly canDelete: boolean;
  readonly deleted: boolean;
  readonly createdAt: string;
  readonly updatedAt: string;
};

export type BoardComment = {
  readonly id: number;
  readonly authorName: string;
  readonly content: string;
  readonly canDelete: boolean;
  readonly deleted: boolean;
  readonly createdAt: string;
};

export type BoardPostPage = {
  readonly content: readonly BoardPost[];
  readonly page: number;
  readonly size: number;
  readonly totalElements: number;
  readonly totalPages: number;
};

export type BoardPostDetail = {
  readonly post: BoardPost;
  readonly comments: readonly BoardComment[];
};

export class BoardRequestError extends Error {
  readonly name = 'BoardRequestError';

  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

function isBoardCategory(value: unknown): value is BoardCategory {
  return typeof value === 'string' && BOARD_CATEGORIES.some((category) => category.value === value);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function isBoardPost(value: unknown): value is BoardPost {
  return isRecord(value) &&
    typeof value.id === 'number' &&
    isBoardCategory(value.category) &&
    typeof value.authorName === 'string' &&
    typeof value.title === 'string' &&
    typeof value.contentHtml === 'string' &&
    typeof value.pinned === 'boolean' &&
    typeof value.canDelete === 'boolean' &&
    typeof value.deleted === 'boolean' &&
    typeof value.createdAt === 'string' &&
    typeof value.updatedAt === 'string';
}

function isBoardComment(value: unknown): value is BoardComment {
  return isRecord(value) &&
    typeof value.id === 'number' &&
    typeof value.authorName === 'string' &&
    typeof value.content === 'string' &&
    typeof value.canDelete === 'boolean' &&
    typeof value.deleted === 'boolean' &&
    typeof value.createdAt === 'string';
}

function parsePage(value: unknown): BoardPostPage {
  if (!isRecord(value) ||
    !Array.isArray(value.content) ||
    !value.content.every(isBoardPost) ||
    typeof value.page !== 'number' ||
    typeof value.size !== 'number' ||
    typeof value.totalElements !== 'number' ||
    typeof value.totalPages !== 'number') {
    throw new BoardRequestError(502, '게시판 목록 응답이 올바르지 않습니다.');
  }
  return {
    content: value.content,
    page: value.page,
    size: value.size,
    totalElements: value.totalElements,
    totalPages: value.totalPages,
  };
}

function parseDetail(value: unknown): BoardPostDetail {
  if (!isRecord(value) || !isBoardPost(value.post) || !Array.isArray(value.comments) || !value.comments.every(isBoardComment)) {
    throw new BoardRequestError(502, '게시글 응답이 올바르지 않습니다.');
  }
  return { post: value.post, comments: value.comments };
}

function parsePost(value: unknown): BoardPost {
  if (!isBoardPost(value)) throw new BoardRequestError(502, '게시글 응답이 올바르지 않습니다.');
  return value;
}

function parseComment(value: unknown): BoardComment {
  if (!isBoardComment(value)) throw new BoardRequestError(502, '댓글 응답이 올바르지 않습니다.');
  return value;
}

async function readBody(response: Response): Promise<unknown> {
  const text = await response.text();
  if (text === '') return null;
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

async function request(path: string, init?: RequestInit): Promise<unknown> {
  let response: Response;
  try {
    response = await fetch(path, init);
  } catch (error) {
    if (error instanceof Error) throw new BoardRequestError(502, '게시판 서버에 연결할 수 없습니다.');
    throw error;
  }

  const body = await readBody(response);
  if (!response.ok) {
    const message = isRecord(body) && typeof body.message === 'string'
      ? body.message
      : '게시판 요청에 실패했습니다.';
    throw new BoardRequestError(response.status, message);
  }
  return body;
}

export async function fetchBoardPosts(category: BoardCategory, page: number, size = 20): Promise<BoardPostPage> {
  const params = new URLSearchParams({ category, page: String(page), size: String(size) });
  return parsePage(await request(`/api/board/posts?${params.toString()}`, { cache: 'no-store' }));
}

export async function fetchBoardPost(postId: string): Promise<BoardPostDetail> {
  return parseDetail(await request(`/api/board/posts/${encodeURIComponent(postId)}`, { cache: 'no-store' }));
}

export async function createBoardPost(input: {
  readonly category: BoardCategory;
  readonly title: string;
  readonly content: string;
}): Promise<BoardPost> {
  return parsePost(await request('/api/board/posts', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  }));
}

export async function createBoardComment(postId: string, content: string): Promise<BoardComment> {
  return parseComment(await request(`/api/board/posts/${encodeURIComponent(postId)}/comments`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ content }),
  }));
}

export async function deleteBoardPost(postId: number): Promise<void> {
  await request(`/api/board/posts/${postId}`, { method: 'DELETE' });
}

export async function deleteBoardComment(postId: number, commentId: number): Promise<void> {
  await request(`/api/board/posts/${postId}/comments/${commentId}`, { method: 'DELETE' });
}

export async function setBoardPostPinned(postId: number, pinned: boolean): Promise<BoardPost> {
  return parsePost(await request(`/api/board/posts/${postId}/pin`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ pinned }),
  }));
}

export function boardCategoryLabel(category: BoardCategory): string {
  const entry = BOARD_CATEGORIES.find((item) => item.value === category);
  return entry?.label ?? category;
}

export function boardDate(value: string): string {
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return new Intl.DateTimeFormat('ko-KR', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(parsed);
}
