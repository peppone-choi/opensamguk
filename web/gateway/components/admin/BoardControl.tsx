'use client';

import React, { useCallback, useEffect, useRef, useState } from 'react';
import ConfirmModal from '@/components/ConfirmModal';
import BoardControlTable, {
    BOARD_CATEGORIES,
    type BoardCategory,
    type BoardPage,
    type BoardPost,
} from '@/components/admin/BoardControlTable';

const PAGE_SIZE = 20;

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

export default function BoardControl() {
    const [category, setCategory] = useState<BoardCategory>('NOTICE');
    const [page, setPage] = useState(0);
    const [data, setData] = useState<BoardPage | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [notice, setNotice] = useState<string | null>(null);
    const [deleting, setDeleting] = useState<BoardPost | null>(null);
    const [busy, setBusy] = useState(false);
    const latestLoad = useRef(0);

    const load = useCallback(async (nextCategory: BoardCategory, nextPage: number) => {
        const requestId = latestLoad.current + 1;
        latestLoad.current = requestId;
        setLoading(true);
        setError(null);
        try {
            const query = new URLSearchParams({
                category: nextCategory,
                page: String(nextPage),
                size: String(PAGE_SIZE),
            });
            const response = await fetch(`/api/proxy/board/posts?${query}`, { cache: 'no-store' });
            if (!response.ok) {
                const message = await responseMessage(response, '게시물 목록을 불러오지 못했습니다.');
                if (latestLoad.current !== requestId) return;
                setData(null);
                setError(message);
                return;
            }
            const body: unknown = await response.json();
            if (latestLoad.current !== requestId) return;
            if (!isBoardPage(body)) {
                setData(null);
                setError('게시물 목록 형식이 올바르지 않습니다.');
                return;
            }
            setData(body);
        } catch {
            if (latestLoad.current !== requestId) return;
            setData(null);
            setError('게시물 목록을 불러오지 못했습니다.');
        } finally {
            if (latestLoad.current === requestId) setLoading(false);
        }
    }, []);

    useEffect(() => {
        void load(category, page);
        return () => {
            latestLoad.current += 1;
        };
    }, [category, load, page]);

    async function togglePin(post: BoardPost) {
        setBusy(true);
        setError(null);
        setNotice(null);
        try {
            const response = await fetch(`/api/proxy/board/posts/${post.id}/pin`, {
                method: 'PATCH',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ pinned: !post.pinned }),
            });
            if (!response.ok) {
                setError(await responseMessage(response, '게시물 고정 상태를 변경하지 못했습니다.'));
                return;
            }
            const body: unknown = await response.json();
            if (!isBoardPost(body)) {
                setError('게시물 고정 상태를 받지 못했습니다.');
                return;
            }
            setData((current) =>
                current
                    ? { ...current, content: current.content.map((item) => (item.id === body.id ? body : item)) }
                    : current,
            );
            setNotice(body.pinned ? '게시물을 고정했습니다.' : '게시물 고정을 해제했습니다.');
        } catch {
            setError('게시물 고정 상태를 변경하지 못했습니다.');
        } finally {
            setBusy(false);
        }
    }

    async function deletePost() {
        if (!deleting) return;
        const postId = deleting.id;
        setBusy(true);
        setError(null);
        setNotice(null);
        try {
            const response = await fetch(`/api/proxy/board/posts/${postId}`, { method: 'DELETE' });
            if (response.status !== 204) {
                setError(await responseMessage(response, '게시물을 삭제하지 못했습니다.'));
                return;
            }
            const previousPage = data && data.content.length === 1 && data.page > 0 ? data.page - 1 : null;
            setData((current) => {
                if (!current) return current;
                const totalElements = Math.max(0, current.totalElements - 1);
                return {
                    ...current,
                    content: current.content.filter((post) => post.id !== postId),
                    totalElements,
                    totalPages: totalElements === 0 ? 0 : Math.ceil(totalElements / current.size),
                };
            });
            if (previousPage !== null) setPage(previousPage);
            setNotice('게시물을 삭제했습니다.');
        } catch {
            setError('게시물을 삭제하지 못했습니다.');
        } finally {
            setBusy(false);
            setDeleting(null);
        }
    }

    return (
        <div className="member-control">
            <BoardControlTable
                category={category}
                data={data}
                loading={loading}
                busy={busy}
                notice={notice}
                error={error}
                onCategoryChange={(nextCategory) => {
                    setCategory(nextCategory);
                    setPage(0);
                }}
                onPreviousPage={() => setPage((current) => Math.max(0, current - 1))}
                onNextPage={() => setPage((current) => current + 1)}
                onPin={togglePin}
                onDelete={setDeleting}
            />

            <ConfirmModal
                open={deleting !== null}
                title="게시물 삭제"
                message={deleting ? <><strong>{deleting.title}</strong> 게시물을 삭제합니다.</> : ''}
                confirmLabel="삭제"
                danger
                busy={busy}
                onConfirm={() => {
                    void deletePost();
                }}
                onCancel={() => setDeleting(null)}
            />
        </div>
    );
}
