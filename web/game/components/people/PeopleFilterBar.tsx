'use client';

import { ReasonTooltip, Seg } from '@opensamguk/ui';
import type { PeopleScope } from '@/lib/directory-reads';
import { PEOPLE_SCOPES, PEOPLE_SCOPE_LABEL, loadedText } from '@/lib/people-view';
import styles from './people.module.css';

const SCOPES = PEOPLE_SCOPES.map((value) => ({ value, label: PEOPLE_SCOPE_LABEL[value] }));
const SORT_WAITING = '준비 중 — 지금은 등록순으로만 보입니다.';

/**
 * 정렬 · 거르기 단추 — 서버가 정렬 키를 받기 전(계약판 K4-20)에는 점선 「준비 중」(K0 2026-10-01: NOT_DELIVERED 모양).
 * 원장 입력이 아니라 data-input-id 는 붙이지 않는다. 누르면 사유 시트가 열린다.
 */
function SortWaiting({ label }: { readonly label: string }) {
    return (
        <ReasonTooltip reason={SORT_WAITING} className="os-ia">
            {(describedBy) => (
                <button
                    type="button"
                    className="os-button os-ia__button os-button--ghost os-button--disabled"
                    aria-disabled="true"
                    aria-haspopup="dialog"
                    aria-describedby={describedBy}
                    data-waiting="people-sort"
                >
                    {label}
                </button>
            )}
        </ReasonTooltip>
    );
}

export interface PeopleFilterBarProps {
    readonly scope: PeopleScope;
    readonly onScopeChange: (scope: PeopleScope) => void;
    readonly query: string;
    readonly onQueryChange: (q: string) => void;
    readonly loaded: number;
    readonly hasMore: boolean;
    readonly mobile?: boolean;
}

/**
 * 거르기 줄(보드 V31K4People filt · V31K4MPeople) — 범위 셋 · 이름 찾기 · 정렬/거르기(준비 중) · 받은 수.
 * 찾기는 서버가 이름 부분 글자로 자른다 — 초성은 서버 보강(계약판 K4-05 312행) 전까지 자리표시에 적지 않는다.
 */
export function PeopleFilterBar({ scope, onScopeChange, query, onQueryChange, loaded, hasMore, mobile = false }: PeopleFilterBarProps) {
    const search = (
        <input
            type="search"
            className={`os-input ${styles.search}`}
            aria-label="이름 찾기"
            placeholder="이름"
            value={query}
            onChange={(e) => onQueryChange(e.target.value)}
        />
    );
    if (mobile) {
        return (
            <div className={styles.filterMobile}>
                {search}
                <div className={styles.filterRow}>
                    <Seg label="범위" options={SCOPES} value={scope} onChange={onScopeChange} className={styles.grow} />
                    <SortWaiting label="거르기 · 정렬" />
                </div>
                <span className={`os-mono ${styles.muted}`} role="status">{`${loadedText(loaded, hasMore)} · 등록순`}</span>
            </div>
        );
    }
    return (
        <div className={styles.filter}>
            <Seg label="범위" options={SCOPES} value={scope} onChange={onScopeChange} />
            {search}
            <SortWaiting label="정렬 · 거르기" />
            <span className={`os-mono ${styles.muted} ${styles.count}`} role="status">{loadedText(loaded, hasMore)}</span>
        </div>
    );
}
