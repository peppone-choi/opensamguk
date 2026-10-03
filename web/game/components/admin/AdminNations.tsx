'use client';

// 세력 개요(P-A03 NS1–NS34) — GET /api/admin/nations. 보드 V31K5GameAdminNations 의 열 그대로:
// 세력 · 현 · 소속 인물 · 창고 금 · 창고 쌀 · 병력 · 호구. 정렬은 열 머리를 눌러서(삼모 정렬 select 는 뺐다).
// `stockTotal` 은 수도 창고가 아니라 다스리는 모든 城 창고의 합이다 — 열 이름도 「창고 합」(K4 세력 화면과 같은 말).
// null 은 「못 읽음」이다 — 0 으로 바꾸지 않고 「—」로 적고, 정렬에서는 늘 뒤로 보낸다.

import { useCallback, useMemo, useState } from 'react';
import { Flag, Panel, SectionHeader, StatusView } from '@opensamguk/ui';
import { campaignPartialNotice } from '@/components/campaign/GameStates';
import { api } from '@/lib/api';
import type { AdminNationDirectory } from '@/lib/admin-reads';
import type { NationSummary } from '@/lib/directory-reads';
import { formatNumber } from '@/lib/format';
import { httpStatusOf } from '@/lib/records-reads';
import { useAdminRead } from './GameAdminScreen';
import styles from './game-admin.module.css';

const DASH = '—';
const num = (n: number | null | undefined) => (n == null ? DASH : formatNumber(n));

type SortKey = 'county' | 'retinue' | 'money' | 'grain' | 'troops' | 'population';
type Sort = { readonly key: SortKey; readonly dir: 'desc' | 'asc' } | null;

/** 병력 = 성 병력 + 부곡. 하나라도 못 읽었으면 null(합을 지어내지 않는다). */
export function troopsOf(n: NationSummary): number | null {
    if (n.troops == null || n.troops.city == null || n.troops.bugok == null) return null;
    return n.troops.city + n.troops.bugok;
}

const COLUMNS: readonly { readonly key: SortKey; readonly label: string; readonly value: (n: NationSummary) => number | null }[] = [
    { key: 'county', label: '현', value: (n) => n.countyCount },
    { key: 'retinue', label: '소속 인물', value: (n) => n.retinueCount },
    { key: 'money', label: '창고 합 금', value: (n) => n.stockTotal?.money ?? null },
    { key: 'grain', label: '창고 합 쌀', value: (n) => n.stockTotal?.grain ?? null },
    { key: 'troops', label: '병력', value: troopsOf },
    { key: 'population', label: '호구', value: (n) => n.population },
];

export function sortNations(rows: readonly NationSummary[], sort: Sort): readonly NationSummary[] {
    if (!sort) return rows;
    const column = COLUMNS.find((c) => c.key === sort.key)!;
    const sign = sort.dir === 'desc' ? -1 : 1;
    // 같은 값 · null 끼리는 서버 순서(세력 id) 그대로 둔다.
    return rows
        .map((row, index) => ({ row, index, value: column.value(row) }))
        .sort((a, b) => {
            if (a.value === null || b.value === null) {
                if (a.value === b.value) return a.index - b.index;
                return a.value === null ? 1 : -1;
            }
            return a.value === b.value ? a.index - b.index : (a.value - b.value) * sign;
        })
        .map((e) => e.row);
}

export default function AdminNations() {
    const [seq, setSeq] = useState(0);
    const reload = useCallback(() => setSeq((n) => n + 1), []);
    const read = useCallback((signal: AbortSignal) => api.admin.nations(signal), []);
    const load = useAdminRead<AdminNationDirectory>(read, seq);
    const [sort, setSort] = useState<Sort>(null);
    const rows = useMemo(() => (load.state === 'ready' ? sortNations(load.data.nations, sort) : []), [load, sort]);

    if (load.state === 'loading') return <StatusView kind="loading" rows={4} />;
    if (load.state === 'error') {
        if (httpStatusOf(load.error) === 403) {
            return <StatusView kind="denied" title="관리자 권한이 필요합니다." howTo="운영자 계정으로 다시 로그인하면 볼 수 있습니다." />;
        }
        return <StatusView kind="error" title="세력 개요를 지금 읽을 수 없습니다" body="잠시 뒤 다시 해 보세요." onRetry={reload} />;
    }
    const d = load.data;
    if (d.status !== 'READY' && d.status !== 'PARTIAL') {
        return (
            <StatusView kind="error" title="세력 개요를 지금 읽을 수 없습니다" body="돌고 있는 월드나 행정 구역 자료를 서버가 찾지 못했습니다."
                errorCode={d.status} onRetry={reload} />
        );
    }
    if (d.nations.length === 0) return <StatusView kind="empty" title="세력이 없습니다" body="세력이 생기면 이 표에 보입니다." />;

    const press = (key: SortKey) => setSort((s) => (s?.key === key && s.dir === 'desc' ? { key, dir: 'asc' } : { key, dir: 'desc' }));
    const partial = campaignPartialNotice('PARTIAL');
    return (
        <Panel className={styles.panel}>
            <SectionHeader title="세력 개요" sub={`세력 ${formatNumber(d.nations.length)} · 열 머리를 눌러 정렬`} />
            {d.status === 'PARTIAL' && partial ? <p className={styles.notice} role="note">{partial}</p> : null}
            <div className={styles.tableWrap}>
                <table className={styles.table}>
                    <caption className="sr-only">세력 개요 — 창고 합은 다스리는 모든 성 창고의 합, 병력은 성 병력과 부곡의 합</caption>
                    <thead>
                        <tr>
                            <th scope="col" className={styles.nameCol}>세력</th>
                            {COLUMNS.map((c) => {
                                const on = sort?.key === c.key ? sort.dir : null;
                                return (
                                    <th key={c.key} scope="col" className={styles.num} aria-sort={on === 'desc' ? 'descending' : on === 'asc' ? 'ascending' : 'none'}>
                                        <button type="button" className={styles.sortButton} onClick={() => press(c.key)}>
                                            {c.label}
                                            <span className={styles.sortMark} aria-hidden="true">{on === 'desc' ? '▼' : on === 'asc' ? '▲' : ''}</span>
                                        </button>
                                    </th>
                                );
                            })}
                        </tr>
                    </thead>
                    <tbody>
                        {rows.map((n, i) => (
                            <tr key={n.nation?.id ?? `row-${i}`}>
                                <th scope="row" className={styles.nameCol}>
                                    <span className={styles.nation}>
                                        {n.nation ? <Flag color={n.nation.color} size={16} label={`${n.nation.name} 깃발`} /> : null}
                                        <span>{n.nation?.name ?? DASH}</span>
                                    </span>
                                </th>
                                {COLUMNS.map((c) => <td key={c.key} className={styles.num}>{num(c.value(n))}</td>)}
                            </tr>
                        ))}
                    </tbody>
                </table>
            </div>
        </Panel>
    );
}
