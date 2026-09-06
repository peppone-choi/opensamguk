import React from 'react';
import { BOARD_CATEGORIES, type BoardCategory, type BoardCategoryCount } from '@/lib/board';

/** 분류 칩 6 + 전체. 카운트는 /api/board/categories 가 왔을 때만 붙는다(없으면 라벨만 — 날조 없음). */
export default function BoardTabs({
  active,
  onSelect,
  counts,
  showAll = false,
}: {
  readonly active: BoardCategory | null;
  readonly onSelect: (category: BoardCategory | null) => void;
  readonly counts?: readonly BoardCategoryCount[];
  readonly showAll?: boolean;
}) {
  const countOf = (value: BoardCategory) => counts?.find((item) => item.category === value)?.count;
  const total = counts ? counts.reduce((sum, item) => sum + item.count, 0) : undefined;
  return (
    <div className="board-tabs" role="group" aria-label="게시판 분류">
      {showAll && (
        <button
          aria-pressed={active === null}
          className={`board-tab${active === null ? ' active' : ''}`}
          onClick={() => onSelect(null)}
          type="button"
        >
          전체{total != null && <span className="board-tab-count os-num">{total.toLocaleString()}</span>}
        </button>
      )}
      {BOARD_CATEGORIES.map((category) => {
        const count = countOf(category.value);
        return (
          <button
            aria-pressed={active === category.value}
            className={`board-tab${active === category.value ? ' active' : ''}`}
            key={category.value}
            onClick={() => onSelect(category.value)}
            type="button"
          >
            {category.label}
            {count != null && <span className="board-tab-count os-num">{count.toLocaleString()}</span>}
          </button>
        );
      })}
    </div>
  );
}
