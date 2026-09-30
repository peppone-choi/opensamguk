'use client';

// 명령 목록 — 분류 탭 + 찾기(이름 · 초성 · 옛 이름). 행 = 이름 · 한 줄 효과 · 오른쪽 상태.
// 순별 가능 여부 일괄 조회(U-02 · K6-01)가 서버에 없으므로 행에는 「준비 중」(PLANNED)만 싣는다.
// 가능 · 불가는 명령을 고르면 인자 패널이 서버 옵션으로 보인다.
import { useId } from 'react';
import { FLOW_CATEGORIES, type FlowCommand } from '@/lib/command-flow/catalog';
import styles from './CommandFlow.module.css';

export type ListCategory = (typeof FLOW_CATEGORIES)[number];

export interface CommandListProps {
    readonly commands: readonly FlowCommand[];
    readonly category: ListCategory;
    readonly query: string;
    readonly selected: string | null;
    readonly onCategory: (c: ListCategory) => void;
    readonly onQuery: (q: string) => void;
    readonly onSelect: (inputId: string) => void;
}

export default function CommandList({ commands, category, query, selected, onCategory, onQuery, onSelect }: CommandListProps) {
    const searchId = useId();
    return (
        <div className={styles.list}>
            <div className={styles.tabs} role="tablist" aria-label="명령 분류">
                {FLOW_CATEGORIES.map((c) => (
                    <button
                        key={c}
                        type="button"
                        role="tab"
                        className={styles.tab}
                        aria-selected={c === category}
                        onClick={() => onCategory(c)}
                    >
                        {c}
                    </button>
                ))}
            </div>
            <label htmlFor={searchId} className="os-sr-only">명령 찾기</label>
            <input
                id={searchId}
                className={`os-inset ${styles.search}`}
                type="search"
                inputMode="search"
                placeholder="명령 찾기 — 초성도 됩니다"
                value={query}
                onChange={(e) => onQuery(e.target.value)}
            />
            {commands.length === 0 ? (
                <p className={styles.listEmpty} role="status">「{query}」에 맞는 명령이 없습니다.</p>
            ) : (
                <ul className={styles.rows} aria-label="명령">
                    {commands.map((c) => (
                        <li key={c.inputId}>
                            <button
                                type="button"
                                className={styles.row}
                                data-input-id={c.inputId}
                                aria-current={c.inputId === selected}
                                onClick={() => onSelect(c.inputId)}
                            >
                                <span style={{ minWidth: 0 }}>
                                    <span className={styles.rowName}>{c.name}</span>
                                    <span className={styles.rowBlurb}>{c.blurb}</span>
                                </span>
                                {c.delivery === 'PLANNED' ? <span className={styles.planned}>준비 중</span> : null}
                            </button>
                        </li>
                    ))}
                </ul>
            )}
        </div>
    );
}
