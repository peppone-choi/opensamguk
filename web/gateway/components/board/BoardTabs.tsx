import React from 'react';
import { BOARD_CATEGORIES, type BoardCategory } from '@/lib/board';

export default function BoardTabs({
  active,
  onSelect,
}: {
  readonly active: BoardCategory;
  readonly onSelect: (category: BoardCategory) => void;
}) {
  return (
    <div className="board-tabs" role="tablist" aria-label="게시판 분류">
      {BOARD_CATEGORIES.map((category) => (
        <button
          aria-selected={active === category.value}
          className={`board-tab${active === category.value ? ' active' : ''}`}
          key={category.value}
          onClick={() => onSelect(category.value)}
          role="tab"
          type="button"
        >
          {category.label}
        </button>
      ))}
    </div>
  );
}
