// ADR-LITE-049 13 — 6 분류. 기존 라벨(공지·자유·건의)은 그대로, 셋을 더한다(board-api GatewayBoardCategory 와 1:1).
export const BOARD_CATEGORIES = [
  { value: 'NOTICE', label: '공지' },
  { value: 'FREE', label: '자유' },
  { value: 'SUGGESTION', label: '건의' },
  { value: 'STRATEGY', label: '전략·공략' },
  { value: 'SERVER', label: '서버 이야기' },
  { value: 'CREATIVE', label: '창작·일지' },
] as const;
export const BOARD_SORTS = [
  { value: 'latest', label: '최신' },
  { value: 'popular', label: '인기' },
  { value: 'mine', label: '내 글' },
] as const;
export type BoardSort = (typeof BOARD_SORTS)[number]['value'];

export type BoardCategory = (typeof BOARD_CATEGORIES)[number]['value'];

export type BoardPost = {
  readonly id: number;
  readonly category: BoardCategory;
  readonly authorName: string;
  readonly authorPicture?: string | null;
  readonly authorImageServer?: number | null;
  readonly title: string;
  readonly contentHtml: string;
  readonly pinned: boolean;
  readonly canDelete: boolean;
  readonly deleted: boolean;
  readonly createdAt: string;
  readonly updatedAt: string;
  // ADR-LITE-049 13 — 조회수·댓글 수·작성자 대표 장수(서버 배지). 구 응답엔 없다.
  readonly viewCount?: number;
  readonly commentCount?: number;
  readonly authorGeneralName?: string | null;
  readonly authorWorldId?: number | null;
};

export type BoardCategoryCount = { readonly category: BoardCategory; readonly count: number };
export type BoardReportStatus = 'OPEN' | 'HANDLED' | 'DISMISSED';
export type BoardReport = {
  readonly id: number;
  readonly postId: number | null;
  readonly commentId: number | null;
  readonly targetSummary: string | null;
  readonly reporterName: string;
  readonly reason: string;
  readonly status: BoardReportStatus;
  readonly createdAt: string;
  readonly handledAt: string | null;
};

export type BoardComment = {
  readonly id: number;
  readonly authorName: string;
  readonly authorPicture?: string | null;
  readonly authorImageServer?: number | null;
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

export type BoardListOptions = { readonly sort?: BoardSort; readonly q?: string };

/**
 * 목록 조회. 쿼리 계약: `category=&page=&size=` 순서 고정(기존 계약), 분류 전체는 category 생략,
 * 정렬은 latest 가 기본이라 생략, 검색어는 q(공백 제거, 빈 값 생략).
 */
export async function fetchBoardPosts(
  category: BoardCategory | null,
  page: number,
  size = 20,
  options: BoardListOptions = {},
): Promise<BoardPostPage> {
  const params = new URLSearchParams();
  if (category) params.set('category', category);
  params.set('page', String(page));
  params.set('size', String(size));
  if (options.sort && options.sort !== 'latest') params.set('sort', options.sort);
  const q = options.q?.trim();
  if (q) params.set('q', q);
  return parsePage(await request(`/api/board/posts?${params.toString()}`, { cache: 'no-store' }));
}

function isCategoryCount(value: unknown): value is BoardCategoryCount {
  return isRecord(value) && isBoardCategory(value.category) && typeof value.count === 'number';
}

/** 분류별 공개 글 수(6 분류). */
export async function fetchBoardCategoryCounts(): Promise<readonly BoardCategoryCount[]> {
  const body = await request('/api/board/categories', { cache: 'no-store' });
  if (!Array.isArray(body) || !body.every(isCategoryCount)) throw new BoardRequestError(502, '분류 응답이 올바르지 않습니다.');
  return body;
}

function isBoardReport(value: unknown): value is BoardReport {
  return isRecord(value) &&
    typeof value.id === 'number' &&
    typeof value.reporterName === 'string' &&
    typeof value.reason === 'string' &&
    (value.status === 'OPEN' || value.status === 'HANDLED' || value.status === 'DISMISSED') &&
    typeof value.createdAt === 'string';
}
function parseReport(value: unknown): BoardReport {
  if (!isBoardReport(value)) throw new BoardRequestError(502, '신고 응답이 올바르지 않습니다.');
  return value;
}

/** 글 신고 — 로그인 필요, 같은 글에 열린 신고가 있으면 409. */
export async function reportBoardPost(postId: number, reason: string): Promise<BoardReport> {
  return parseReport(await request(`/api/board/posts/${postId}/report`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason }),
  }));
}

export async function reportBoardComment(postId: number, commentId: number, reason: string): Promise<BoardReport> {
  return parseReport(await request(`/api/board/posts/${postId}/comments/${commentId}/report`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason }),
  }));
}

/** 관리자 — 신고 목록(status 없으면 전부). */
export async function fetchBoardReports(status?: BoardReportStatus): Promise<readonly BoardReport[]> {
  const params = new URLSearchParams();
  if (status) params.set('status', status);
  const body = await request(`/api/board/admin/reports${params.size ? `?${params}` : ''}`, { cache: 'no-store' });
  if (!Array.isArray(body) || !body.every(isBoardReport)) throw new BoardRequestError(502, '신고 목록 응답이 올바르지 않습니다.');
  return body;
}

export async function handleBoardReport(reportId: number, status: 'HANDLED' | 'DISMISSED'): Promise<BoardReport> {
  return parseReport(await request(`/api/board/admin/reports/${reportId}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ status }),
  }));
}

export function boardSortLabel(sort: BoardSort): string {
  return BOARD_SORTS.find((item) => item.value === sort)?.label ?? sort;
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
    body: JSON.stringify({ ...input, contentFormat: 'RICH_HTML' }),
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
