'use client';

// 운영 콘솔 「게시물」 — 분류 · 쪽 목록(삭제분 포함) · 고정 · 삭제.
// 상태 · 요청은 useAdminBoard(lib/use-admin-board → lib/admin-board)가 맡고, 이 파일은 그리기만 한다(D105 층).
import React from 'react';
import ConfirmModal from '@/components/ConfirmModal';
import BoardControlTable from '@/components/admin/BoardControlTable';
import { useAdminBoard } from '@/lib/use-admin-board';

export default function BoardControl() {
    const board = useAdminBoard();
    const { deleting } = board;

    return (
        <div className="member-control">
            <BoardControlTable
                category={board.category}
                data={board.data}
                loading={board.loading}
                busy={board.busy}
                notice={board.notice}
                error={board.error}
                onCategoryChange={board.changeCategory}
                onPreviousPage={board.previousPage}
                onNextPage={board.nextPage}
                onPin={board.togglePin}
                onDelete={board.askDelete}
            />

            <ConfirmModal
                open={deleting !== null}
                title="게시물 삭제"
                message={deleting ? <><strong>{deleting.title}</strong> 게시물을 삭제합니다.</> : ''}
                confirmLabel="삭제"
                danger
                busy={board.busy}
                onConfirm={() => {
                    void board.deletePost();
                }}
                onCancel={board.cancelDelete}
            />
        </div>
    );
}
