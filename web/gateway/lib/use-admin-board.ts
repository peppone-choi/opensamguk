'use client';

// 운영 콘솔 「게시물」 훅 — 분류 · 쪽 읽기(늦게 온 응답은 버림), 고정, 삭제 확인 · 삭제(D105 층: 화면 → 이 훅 → lib/admin-board).
// BoardControl 에 있던 상태 · 처리를 그대로 옮겼다. 요청은 lib/admin-board 만 부르고, 실패 문구도 그쪽이 정한다.
import { useCallback, useEffect, useRef, useState } from 'react';
import { deleteAdminBoardPost, fetchAdminBoardPage, toggleAdminBoardPin } from './admin-board';
import type { BoardCategory, BoardPage, BoardPost } from './admin-board-types';

export function useAdminBoard() {
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
            const result = await fetchAdminBoardPage(nextCategory, nextPage);
            if (latestLoad.current !== requestId) return;
            if (!result.ok) {
                setData(null);
                setError(result.message);
                return;
            }
            setData(result.value);
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
            const result = await toggleAdminBoardPin(post);
            if (!result.ok) {
                setError(result.message);
                return;
            }
            const body = result.value;
            setData((current) =>
                current
                    ? { ...current, content: current.content.map((item) => (item.id === body.id ? body : item)) }
                    : current,
            );
            setNotice(body.pinned ? '게시물을 고정했습니다.' : '게시물 고정을 해제했습니다.');
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
            const result = await deleteAdminBoardPost(postId);
            if (!result.ok) {
                setError(result.message);
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
        } finally {
            setBusy(false);
            setDeleting(null);
        }
    }

    return {
        category,
        data,
        loading,
        busy,
        notice,
        error,
        deleting,
        changeCategory(nextCategory: BoardCategory) {
            setCategory(nextCategory);
            setPage(0);
        },
        previousPage: () => setPage((current) => Math.max(0, current - 1)),
        nextPage: () => setPage((current) => current + 1),
        togglePin,
        askDelete: setDeleting,
        cancelDelete: () => setDeleting(null),
        deletePost,
    };
}
