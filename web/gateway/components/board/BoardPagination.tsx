import React from 'react';

export default function BoardPagination({
  page,
  totalPages,
  onPage,
}: {
  readonly page: number;
  readonly totalPages: number;
  readonly onPage: (page: number) => void;
}) {
  if (totalPages <= 1) return null;

  return (
    <nav aria-label="게시글 페이지" className="board-pagination">
      <button disabled={page === 0} onClick={() => onPage(page - 1)} type="button">이전</button>
      <span aria-current="page">{page + 1} / {totalPages}</span>
      <button disabled={page + 1 >= totalPages} onClick={() => onPage(page + 1)} type="button">다음</button>
    </nav>
  );
}
