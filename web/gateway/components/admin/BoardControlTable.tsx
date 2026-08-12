'use client';

import React from 'react';

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

type BoardControlTableProps = {
    readonly category: BoardCategory;
    readonly data: BoardPage | null;
    readonly loading: boolean;
    readonly busy: boolean;
    readonly notice: string | null;
    readonly error: string | null;
    readonly onCategoryChange: (category: BoardCategory) => void;
    readonly onPreviousPage: () => void;
    readonly onNextPage: () => void;
    readonly onPin: (post: BoardPost) => void;
    readonly onDelete: (post: BoardPost) => void;
};

function categoryLabel(category: BoardCategory): string {
    return BOARD_CATEGORIES.find((entry) => entry.value === category)?.label ?? category;
}

function BoardRows({
    posts,
    busy,
    onPin,
    onDelete,
}: {
    readonly posts: readonly BoardPost[];
    readonly busy: boolean;
    readonly onPin: (post: BoardPost) => void;
    readonly onDelete: (post: BoardPost) => void;
}) {
    if (posts.length === 0) {
        return (
            <tr>
                <td colSpan={5}>게시물이 없습니다.</td>
            </tr>
        );
    }

    return posts.map((post) => (
        <tr key={post.id}>
            <td>{categoryLabel(post.category)}</td>
            <td>{post.deleted ? '삭제된 게시물' : post.title}</td>
            <td>{post.authorName}</td>
            <td>{post.pinned ? '고정됨' : '-'}</td>
            <td>
                {post.deleted ? (
                    <span>삭제됨</span>
                ) : (
                    <div className="member-cmd-group" role="group" aria-label={`${post.title} 관리`}>
                        <button
                            type="button"
                            className="btn-ghost member-cmd-btn"
                            disabled={busy}
                            onClick={() => onPin(post)}
                        >
                            {post.pinned ? '고정 해제' : '고정'}
                        </button>
                        <button
                            type="button"
                            className="btn-danger member-cmd-btn"
                            disabled={busy}
                            onClick={() => onDelete(post)}
                        >
                            삭제
                        </button>
                    </div>
                )}
            </td>
        </tr>
    ));
}

export default function BoardControlTable({
    category,
    data,
    loading,
    busy,
    notice,
    error,
    onCategoryChange,
    onPreviousPage,
    onNextPage,
    onPin,
    onDelete,
}: BoardControlTableProps) {
    return (
        <>
            <div className="member-toolbar">
                <label className="field">
                    <span>게시판 분류</span>
                    <select
                        aria-label="게시판 분류"
                        value={category}
                        disabled={busy}
                        onChange={(event) => {
                            const selected = BOARD_CATEGORIES.find((entry) => entry.value === event.target.value);
                            if (selected) onCategoryChange(selected.value);
                        }}
                    >
                        {BOARD_CATEGORIES.map((entry) => (
                            <option key={entry.value} value={entry.value}>
                                {entry.label}
                            </option>
                        ))}
                    </select>
                </label>
            </div>

            {notice ? <p className="member-notice" role="status">{notice}</p> : null}
            {error ? <p className="deploy-result fail" role="alert">{error}</p> : null}

            <h3 className="lobby-section-title">게시물 관리</h3>
            {loading ? (
                <div className="center-inline">
                    <div className="spinner" />
                </div>
            ) : data ? (
                <>
                    <div className="game-table-wrap">
                        <table className="game-table member-table">
                            <thead>
                                <tr>
                                    <th scope="col">분류</th>
                                    <th scope="col">제목</th>
                                    <th scope="col">작성자</th>
                                    <th scope="col">고정</th>
                                    <th scope="col">명령</th>
                                </tr>
                            </thead>
                            <tbody>
                                <BoardRows posts={data.content} busy={busy} onPin={onPin} onDelete={onDelete} />
                            </tbody>
                        </table>
                    </div>
                    {data.totalPages > 1 ? (
                        <div className="member-scrub" aria-label="게시물 페이지 이동">
                            <button
                                type="button"
                                className="btn-ghost"
                                disabled={busy || data.page === 0}
                                onClick={onPreviousPage}
                            >
                                이전 페이지
                            </button>
                            <span>{data.page + 1} / {data.totalPages}</span>
                            <button
                                type="button"
                                className="btn-ghost"
                                disabled={busy || data.page >= data.totalPages - 1}
                                onClick={onNextPage}
                            >
                                다음 페이지
                            </button>
                        </div>
                    ) : null}
                </>
            ) : null}
        </>
    );
}
