'use client';

import { Seg } from '@opensamguk/ui';
import { PEOPLE_SORTS, type PeopleDirection, type PeopleScope, type PeopleSort } from '@/lib/directory-reads';
import { PEOPLE_SCOPES, PEOPLE_SCOPE_LABEL, PEOPLE_SORT_LABEL, defaultDirection, directionLabel, loadedText } from '@/lib/people-view';
import styles from './people.module.css';

const SCOPES = PEOPLE_SCOPES.map((value) => ({ value, label: PEOPLE_SCOPE_LABEL[value] }));

/** 정렬 키(서버 14개) · 방향 — 키를 바꾸면 그 키의 첫 방향으로(수치는 높은 쪽부터). 서버가 정렬한다(#1103). */
function SortControls({ sort, direction, onSort }: {
    readonly sort: PeopleSort;
    readonly direction: PeopleDirection;
    readonly onSort: (sort: PeopleSort, direction: PeopleDirection) => void;
}) {
    const flipped: PeopleDirection = direction === 'ASC' ? 'DESC' : 'ASC';
    return (
        <span className={styles.sort}>
            <select className={`os-input ${styles.sortSelect}`} aria-label="정렬" value={sort}
                onChange={(e) => { const next = e.target.value as PeopleSort; onSort(next, defaultDirection(next)); }}>
                {PEOPLE_SORTS.map((k) => <option key={k} value={k}>{PEOPLE_SORT_LABEL[k]}</option>)}
            </select>
            <button type="button" className="os-button" aria-label={`정렬 방향 — 지금 ${directionLabel(sort, direction)}, 누르면 ${directionLabel(sort, flipped)}`}
                onClick={() => onSort(sort, flipped)}>
                {directionLabel(sort, direction)}
            </button>
        </span>
    );
}

export interface PeopleFilterBarProps {
    readonly scope: PeopleScope;
    readonly onScopeChange: (scope: PeopleScope) => void;
    readonly query: string;
    readonly onQueryChange: (q: string) => void;
    readonly sort: PeopleSort;
    readonly direction: PeopleDirection;
    readonly onSortChange: (sort: PeopleSort, direction: PeopleDirection) => void;
    readonly loaded: number;
    readonly hasMore: boolean;
    /** 범위 전체 수(K4-05 `total`). 없으면 null. */
    readonly total?: number | null;
    readonly mobile?: boolean;
}

/**
 * 거르기 줄(보드 V31K4People filt · V31K4MPeople) — 범위 셋 · 이름 찾기(초성 포함, 서버가 자른다) · 정렬 키 · 방향 · 받은 수.
 */
export function PeopleFilterBar({ scope, onScopeChange, query, onQueryChange, sort, direction, onSortChange, loaded, hasMore, total = null, mobile = false }: PeopleFilterBarProps) {
    const search = (
        <input
            type="search"
            className={`os-input ${styles.search}`}
            aria-label="이름 찾기"
            placeholder="이름 · 초성"
            value={query}
            onChange={(e) => onQueryChange(e.target.value)}
        />
    );
    const sortControls = <SortControls sort={sort} direction={direction} onSort={onSortChange} />;
    const order = `${PEOPLE_SORT_LABEL[sort]} · ${directionLabel(sort, direction)}`;
    if (mobile) {
        return (
            <div className={styles.filterMobile}>
                {search}
                <Seg label="범위" options={SCOPES} value={scope} onChange={onScopeChange} className={styles.grow} />
                {sortControls}
                <span className={`os-mono ${styles.muted}`} role="status">{`${loadedText(loaded, hasMore, total)} · ${order}`}</span>
            </div>
        );
    }
    return (
        <div className={styles.filter}>
            <Seg label="범위" options={SCOPES} value={scope} onChange={onScopeChange} />
            {search}
            {sortControls}
            <span className={`os-mono ${styles.muted} ${styles.count}`} role="status">{loadedText(loaded, hasMore, total)}</span>
        </div>
    );
}
