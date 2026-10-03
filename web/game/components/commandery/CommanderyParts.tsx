'use client';

import Link from 'next/link';
import type { ReactNode } from 'react';
import { Chip, Seg, type InputAvailability } from '@opensamguk/ui';
import { HelpedInputAction } from '@/components/campaign/HelpedInputAction';
import {
    COMMANDERY_SORT_LABEL,
    COUNTY_WARNING_LABEL,
    VISIBILITY_LABEL,
    type CommanderyCountyRow,
    type CommanderySort,
    type CommanderySummary,
} from '@/lib/commandery-view';
import type { PolicyRow } from '@/lib/territory-view';
import styles from './commandery.module.css';

export type CommanderyScope = 'COMMANDERY' | 'NATION';
const SCOPES = [{ value: 'COMMANDERY' as const, label: '이 군' }, { value: 'NATION' as const, label: '우리 세력 전체' }];
const SORTS = (Object.keys(COMMANDERY_SORT_LABEL) as CommanderySort[]).map((value) => ({ value, label: COMMANDERY_SORT_LABEL[value] }));

/** 7지표 열 자리 — K4-11 보강(indicators) 전까지 한 줄로 알린다. */
export const INDICATOR_WAITING = '호구 · 전답 · 시장 · 치안 · 민심 · 방비 · 성벽 — 서버가 곧 줍니다.';

export interface CommanderyHeaderProps {
    readonly title: string;
    /** 군을 모르면(영지 탭 「군 내정 현황」 첫 화면) 「이 군」 범위를 감추고 우리 세력 전체만 보인다. */
    readonly hasCommandery: boolean;
    readonly scope: CommanderyScope;
    readonly onScopeChange: (scope: CommanderyScope) => void;
    readonly scout: InputAvailability | null;
    readonly onScout: () => void;
    /** 「지도에서 군 고르기」 고리 — 이웃 군 칩은 서버 보강 뒤. */
    readonly mapPick?: ReactNode;
}

export function CommanderyHeader({ title, hasCommandery, scope, onScopeChange, scout, onScout, mapPick }: CommanderyHeaderProps) {
    return (
        <header className={styles.head}>
            <h2 className={`os-serif ${styles.name}`}>{title}</h2>
            {hasCommandery ? <Seg label="범위" options={SCOPES} value={scope} onChange={onScopeChange} /> : null}
            <span className={styles.headActions}>
                {mapPick}
                {hasCommandery ? <HelpedInputAction inputId="action.scout" availability={scout} label="첩보 — 명령 목록에 넣기" variant="ghost" onAct={onScout} /> : null}
            </span>
        </header>
    );
}

function Warnings({ row }: { readonly row: CommanderyCountyRow }) {
    if (row.warnings.length === 0) return <span className={styles.muted}>—</span>;
    return <span className={styles.chips}>{row.warnings.map((w) => <Chip key={w} tone="rust">{COUNTY_WARNING_LABEL[w]}</Chip>)}</span>;
}

export interface CommanderyTableProps {
    readonly rows: readonly CommanderyCountyRow[];
    readonly sort: CommanderySort;
    readonly onSortChange: (sort: CommanderySort) => void;
    readonly countyHref: (cityId: number) => string;
    readonly mobile?: boolean;
}

/**
 * 현 표(보드 V31K4Commandery) — 현(현 상세 고리) · 시야 · 현령 · 방침(+ 출처) · 월 세입 · 공사 · 경고, 행 44.
 * 7지표 열은 K4-11 보강 전까지 표 위 한 줄. 모바일은 현 카드.
 */
export function CommanderyTable({ rows, sort, onSortChange, countyHref, mobile = false }: CommanderyTableProps) {
    const head = (
        <div className={styles.tableHead}>
            <Seg label="정렬" options={SORTS} value={sort} onChange={onSortChange} scroll />
            <span className={styles.muted} data-waiting="county-indicators">{INDICATOR_WAITING}</span>
        </div>
    );
    if (rows.length === 0) return <p className={styles.muted} role="status">다스리는 현이 없습니다.</p>;
    if (mobile) {
        return (
            <div className={styles.block}>
                {head}
                <ul className={styles.cards} aria-label="현">
                    {rows.map((r) => (
                        <li key={r.cityId}>
                            <Link href={countyHref(r.cityId)} className={styles.card}>
                                <span className={styles.chips}>
                                    <span className="os-serif" style={{ fontWeight: 700 }}>{r.name}</span>
                                    <Chip>{VISIBILITY_LABEL[r.visibility] ?? '알 수 없음'}</Chip>
                                </span>
                                <span className={styles.muted}>{`현령 ${r.magistrate ?? '—'} · 방침 ${r.policy ?? '—'}`}</span>
                                <span className={`os-mono ${styles.muted}`}>{r.income ?? '세입 ?'}</span>
                                <Warnings row={r} />
                            </Link>
                        </li>
                    ))}
                </ul>
            </div>
        );
    }
    return (
        <div className={styles.block}>
            {head}
            <div className="os-table-wrap">
                <table className="os-table os-table--nowrap">
                    <thead>
                        <tr>
                            <th scope="col">현</th><th scope="col">시야</th><th scope="col">현령</th><th scope="col">방침</th>
                            <th scope="col">월 세입</th><th scope="col">공사</th><th scope="col">경고</th>
                        </tr>
                    </thead>
                    <tbody>
                        {rows.map((r) => (
                            <tr key={r.cityId} className={styles.tr} data-city-id={r.cityId}>
                                <td><Link href={countyHref(r.cityId)} className={styles.rowLink}>{r.name}</Link></td>
                                <td><Chip>{VISIBILITY_LABEL[r.visibility] ?? '알 수 없음'}</Chip></td>
                                <td>{r.magistrate ?? '—'}</td>
                                <td>{r.policy ?? '—'}{r.policySource ? <span className={styles.muted}>{` · ${r.policySource}`}</span> : null}</td>
                                <td className="os-mono">{r.income ?? '?'}</td>
                                <td>{r.work ?? <span className={styles.muted}>—</span>}</td>
                                <td><Warnings row={r} /></td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            </div>
        </div>
    );
}

/** 군 요약(4줄) — 빈 현령 · 고립 · 자재 부족 수 + 민심 위험 · 적 군단은 준비 중. */
export function CommanderySummaryCard({ summary }: { readonly summary: CommanderySummary }) {
    return (
        <ul className={styles.summary} aria-label="군 요약">
            <li>{`빈 현령 ${summary.noMagistrate}곳`}</li>
            <li>{`고립 ${summary.isolated}곳`}</li>
            <li>{`자재 부족 ${summary.materialShort}곳`}</li>
            <li data-waiting="trust-enemy">민심 위험 · 적 군단 <Chip tone="info">준비 중</Chip></li>
        </ul>
    );
}

export interface CommanderyPolicyCardProps {
    /** 군 방침 줄(commanderyPolicyRows). 군주가 아니면 서버가 주지 않는다 → null. */
    readonly row: PolicyRow | null;
    readonly availability: InputAvailability | null;
    readonly onChange: () => void;
}

/** 군 방침(policy.set scope COMMANDERY — 소속 현 전체, 군주만). 서버가 받는데 옛 화면에 없던 입력. */
export function CommanderyPolicyCard({ row, availability, onChange }: CommanderyPolicyCardProps) {
    return (
        <section className={styles.block} aria-label="군 방침">
            <h3 className={styles.sub}>군 방침</h3>
            {row ? (
                <span className={styles.chips}>
                    <span>{row.now ?? '방침 없음'}</span>
                    {row.since ? <span className={styles.muted}>{`${row.since}부터`}</span> : null}
                    {row.pending ? <Chip tone="bronze">{`대기 — 다음 턴부터 ${row.pending}`}</Chip> : null}
                </span>
            ) : null}
            <HelpedInputAction inputId="policy.set" availability={availability} label="군 방침 바꾸기" onAct={onChange} block />
        </section>
    );
}
