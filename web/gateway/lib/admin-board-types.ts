// 운영 콘솔 「게시물」 분류 · 응답 꼴 — 표(BoardControlTable) · API 클라이언트(admin-board) · 훅(use-admin-board)이 함께 쓴다.
// fetch 를 하지 않는 파일이라 화면이 값으로 가져와도 된다(D105 층). 공개 게시판(lib/board.ts, 6분류)과 다른 운영 콘솔 3분류다.
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
    readonly deleted: boolean;
    readonly createdAt: string;
    readonly updatedAt: string;
};

export type BoardPage = {
    readonly content: readonly BoardPost[];
    readonly page: number;
    readonly size: number;
    readonly totalElements: number;
    readonly totalPages: number;
};
