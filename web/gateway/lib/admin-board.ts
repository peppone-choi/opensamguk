// 운영 콘솔 「게시물」 API 클라이언트 — /api/board/posts(관리자는 삭제분까지 본다).
// 화면은 이 파일을 직접 부르지 않고 useAdminBoard(lib/use-admin-board.ts)를 쓴다(D105 층: 화면 → 훅 → API 클라이언트).
// 실패는 던지지 않고 { ok: false, message } 로 돌려준다 — 서버 문구가 있으면 그대로, 없으면 칸마다 정한 기본 문구.
import { BOARD_CATEGORIES, type BoardCategory, type BoardPage, type BoardPost } from './admin-board-types';

const ADMIN_BOARD_PAGE_SIZE = 20;

export type AdminBoardResult<T> = { readonly ok: true; readonly value: T } | { readonly ok: false; readonly message: string };

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null;
}

function isBoardCategory(value: unknown): value is BoardCategory {
    return BOARD_CATEGORIES.some((category) => category.value === value);
}

function isBoardPost(value: unknown): value is BoardPost {
    return (
        isRecord(value) &&
        typeof value.id === 'number' &&
        isBoardCategory(value.category) &&
        typeof value.authorName === 'string' &&
        typeof value.title === 'string' &&
        typeof value.contentHtml === 'string' &&
        typeof value.pinned === 'boolean' &&
        typeof value.deleted === 'boolean' &&
        typeof value.createdAt === 'string' &&
        typeof value.updatedAt === 'string'
    );
}

function isBoardPage(value: unknown): value is BoardPage {
    return (
        isRecord(value) &&
        Array.isArray(value.content) &&
        value.content.every(isBoardPost) &&
        typeof value.page === 'number' &&
        typeof value.size === 'number' &&
        typeof value.totalElements === 'number' &&
        typeof value.totalPages === 'number'
    );
}

async function responseMessage(response: Response, fallback: string): Promise<string> {
    const body: unknown = await response.json().catch(() => null);
    return isRecord(body) && typeof body.message === 'string' && body.message.length > 0
        ? body.message
        : fallback;
}

/** 분류 · 쪽의 게시물(삭제분 포함). */
export async function fetchAdminBoardPage(category: BoardCategory, page: number): Promise<AdminBoardResult<BoardPage>> {
    try {
        const query = new URLSearchParams({
            category,
            page: String(page),
            size: String(ADMIN_BOARD_PAGE_SIZE),
            // 어드민은 삭제분까지 봐야 조치 이력을 확인할 수 있다(공개 피드에서는 안 보인다).
            includeDeleted: 'true',
        });
        const response = await fetch(`/api/board/posts?${query}`, { cache: 'no-store' });
        if (!response.ok) return { ok: false, message: await responseMessage(response, '게시물 목록을 불러오지 못했습니다.') };
        const body: unknown = await response.json();
        if (!isBoardPage(body)) return { ok: false, message: '게시물 목록 형식이 올바르지 않습니다.' };
        return { ok: true, value: body };
    } catch {
        return { ok: false, message: '게시물 목록을 불러오지 못했습니다.' };
    }
}

/** 고정 뒤집기 — 서버가 돌려준 글. */
export async function toggleAdminBoardPin(post: BoardPost): Promise<AdminBoardResult<BoardPost>> {
    try {
        const response = await fetch(`/api/board/posts/${post.id}/pin`, {
            method: 'PATCH',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ pinned: !post.pinned }),
        });
        if (!response.ok) return { ok: false, message: await responseMessage(response, '게시물 고정 상태를 변경하지 못했습니다.') };
        const body: unknown = await response.json();
        if (!isBoardPost(body)) return { ok: false, message: '게시물 고정 상태를 받지 못했습니다.' };
        return { ok: true, value: body };
    } catch {
        return { ok: false, message: '게시물 고정 상태를 변경하지 못했습니다.' };
    }
}

/** 삭제 — 204 일 때만 성공. */
export async function deleteAdminBoardPost(postId: number): Promise<AdminBoardResult<null>> {
    try {
        const response = await fetch(`/api/board/posts/${postId}`, { method: 'DELETE' });
        if (response.status !== 204) return { ok: false, message: await responseMessage(response, '게시물을 삭제하지 못했습니다.') };
        return { ok: true, value: null };
    } catch {
        return { ok: false, message: '게시물을 삭제하지 못했습니다.' };
    }
}
