'use client';

import { Chip, Portrait } from '@opensamguk/ui';
import { RETINUE_FILTER_LABEL, RETINUE_SORT_LABEL, type RetinueFilter, type RetinueRow, type RetinueSort } from '@/lib/retinue-view';
import styles from './retinue.module.css';

const SORTS = Object.keys(RETINUE_SORT_LABEL) as RetinueSort[];
const FILTERS = Object.keys(RETINUE_FILTER_LABEL) as RetinueFilter[];
const TONE = { moss: 'moss', rust: 'rust', neutral: 'neutral' } as const;

/** 결속 칩 글자 — 「향당 · 패국 초현」. 본관 현을 못 풀었으면 이름만. */
export function bondText(b: { readonly label: string; readonly nativeCountyName: string | null }): string {
    return b.nativeCountyName ? `${b.label} · ${b.nativeCountyName}` : b.label;
}

/** 자리 한 줄 — 배치 원장 값만(설계서 P-R01 「자리 출처 교체」). */
export function postText(row: RetinueRow): string {
    const { active, pending } = row.post;
    const now = active ?? '미배치';
    return pending ? `${now} → ${pending}` : now;
}

export interface RetinueListProps {
    readonly rows: readonly RetinueRow[];
    readonly sort: RetinueSort;
    readonly onSortChange: (sort: RetinueSort) => void;
    /** 고른 인물(데스크톱 가운데 상세). 모바일은 누르면 인물 상세 화면으로 간다 — 고름 표시 없음. */
    readonly selectedId?: number | null;
    readonly onSelect: (row: RetinueRow) => void;
    readonly mobile?: boolean;
    /**
     * 많은 부(3190 주공 등)의 찾기 · 거르기 줄. 넘기면 보인다 — 언제 보일지는 화면이 정한다.
     * `total` = 거르기 전 인물 수(「n명 중 m」).
     */
    readonly search?: {
        readonly query: string;
        readonly onQueryChange: (q: string) => void;
        readonly filter: RetinueFilter;
        readonly onFilterChange: (f: RetinueFilter) => void;
        readonly total: number;
    };
}

/** 부의 인물 목록(보드 person_list_row) — 정렬 칩 + 줄 목록. 줄 전체가 누를 영역(데스크톱 68 · 모바일 72). */
export function RetinueList({ rows, sort, onSortChange, selectedId = null, onSelect, mobile = false, search }: RetinueListProps) {
    return (
        <div className={styles.list}>
            {search ? (
                <div className={styles.search}>
                    <input
                        type="search"
                        className="os-input"
                        aria-label="이름 찾기"
                        placeholder="이름 · 초성"
                        value={search.query}
                        onChange={(e) => search.onQueryChange(e.target.value)}
                    />
                    <div className="os-seg os-seg--scroll" role="radiogroup" aria-label="거르기">
                        {FILTERS.map((f) => (
                            <button
                                key={f}
                                type="button"
                                role="radio"
                                aria-checked={f === search.filter}
                                className={['os-seg__item', f === search.filter ? 'os-seg__item--on' : ''].filter(Boolean).join(' ')}
                                onClick={() => search.onFilterChange(f)}
                            >
                                {RETINUE_FILTER_LABEL[f]}
                            </button>
                        ))}
                    </div>
                    <span className={styles.muted} role="status">{`${search.total}명 중 ${rows.length}`}</span>
                </div>
            ) : null}
            <div className={`os-seg os-seg--scroll ${styles.sort}`} role="radiogroup" aria-label="정렬">
                {SORTS.map((s) => (
                    <button
                        key={s}
                        type="button"
                        role="radio"
                        aria-checked={s === sort}
                        className={['os-seg__item', s === sort ? 'os-seg__item--on' : ''].filter(Boolean).join(' ')}
                        onClick={() => onSortChange(s)}
                    >
                        {RETINUE_SORT_LABEL[s]}
                    </button>
                ))}
            </div>
            <div role="listbox" aria-label="부의 인물" className={styles.rows}>
                {rows.map((row) => {
                    const sel = !mobile && row.retainerId === selectedId;
                    return (
                        <button
                            key={row.retainerId}
                            type="button"
                            role="option"
                            aria-selected={sel}
                            className={`os-opt ${styles.row}`}
                            data-mobile={mobile || undefined}
                            data-retainer-id={row.retainerId}
                            onClick={() => onSelect(row)}
                        >
                            <Portrait picture={row.picture} imageServer={row.imageServer} size="card-44" alt="" />
                            <span className={styles.rowText}>
                                <span className="os-opt__name-row">
                                    <span className="os-opt__name">{row.name}</span>
                                    {row.departureOrder != null ? <Chip tone="rust">{`이탈 ${row.departureOrder}순위`}</Chip> : null}
                                </span>
                                <span className={styles.chips}>
                                    {row.bonds.length > 0
                                        ? row.bonds.map((b, i) => <Chip key={i} tone="bronze">{bondText(b)}</Chip>)
                                        : <Chip>결속 없음</Chip>}
                                    <Chip tone={TONE[row.loyaltyTone]}>{`충성 ${row.loyalty}`}</Chip>
                                </span>
                                <span className="os-opt__sub">{`자리 ${postText(row)}`}</span>
                            </span>
                            <span className={styles.cost}>
                                <span className={styles.costLabel}>코스트</span>
                                <span className="os-mono">{row.cost ?? '—'}</span>
                            </span>
                        </button>
                    );
                })}
            </div>
        </div>
    );
}
