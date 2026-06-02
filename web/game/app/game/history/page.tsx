'use client';

// ── page 16 · 연감 (History) — READ-ONLY per-month records viewer ──────────────
// Consumes api.history(yearMonth?) → HistoryResponse (foundation phase). Mirrors
// legacy hwe/v_history.php + ts/PageHistory.vue + API/Global/GetHistory.php.
// Title "연감".
//
// Single-server only in F4 (cross-server view dropped — spec OQ-8): the legacy
// server dropdown / GetCurrentHistory live-tail is intentionally omitted; this page
// browses one server's ng_history range [firstYearMonth, lastYearMonth].
//
// yearMonth = Util::joinYearMonth (year*12 + (month-1)); parseYearMonth = [ym/12, ym%12+1]
// — reproduced from legacy ts/util/parseYearMonth.ts byte-for-byte so the dropdown
// labels ("{year}년 {month}월 (선택)") and the ◀ 이전달 / 다음달 ▶ steps match.
//
// Two record sections, verbatim titles (PageHistory.vue): 중원 정세 (global_history),
// 장수 동향 (global_action). Both are server-formatted color/tag log strings rendered
// as HTML, exactly as the legacy v-html="formatLog(item)".
//
// EMPTY-SAFE: record === null (empty range / no ng_history rows) → an empty-state
// notice, the selector still renders the [first,last] range. Empty globalHistory /
// globalAction arrays → empty section bodies. Never crashes.

import { useEffect, useState, useCallback } from 'react';
import Shell from '../../../components/Shell';
import GameCard from '../../../components/GameCard';
import { api } from '../../../lib/api';
import type { HistoryResponse } from '../../../types/game';

// Verbatim from legacy ts/util/parseYearMonth.ts: [(yearMonth/12)|0, yearMonth%12 + 1].
function parseYearMonth(yearMonth: number): [number, number] {
    return [(yearMonth / 12) | 0, (yearMonth % 12) + 1];
}

const sectionBarStyle: React.CSSProperties = {
    textAlign: 'center',
    border: '0.5px solid var(--border-medium)',
    background: 'var(--bg-elevated)',
    padding: 'var(--space-xs) var(--space-sm)',
    fontWeight: 600,
    fontSize: 'var(--text-sm)',
    marginBottom: 'var(--space-sm)',
    marginTop: 'var(--space-lg)',
};

const logRowStyle: React.CSSProperties = {
    fontSize: 'var(--text-sm)',
    lineHeight: 1.7,
    padding: 'var(--space-xs) 0',
    borderBottom: '1px solid var(--border-subtle)',
};

export default function HistoryPage() {
    const [data, setData] = useState<HistoryResponse | null>(null);
    // selected yearMonth (null = use server currentYearMonth on first load)
    const [queryYearMonth, setQueryYearMonth] = useState<number | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string>('');

    const fetchData = useCallback(async (yearMonth: number | null) => {
        setLoading(true);
        try {
            const d = await api.history(yearMonth ?? undefined);
            setData(d);
            // On first load (no explicit selection yet), adopt the server's current month.
            setQueryYearMonth((prev) => (prev == null ? d.currentYearMonth : prev));
            setError('');
        } catch {
            setError('데이터를 불러올 수 없습니다.');
        } finally {
            setLoading(false);
        }
    }, []);

    // initial load (server resolves currentYearMonth)
    useEffect(() => {
        fetchData(null);
    }, [fetchData]);

    const first = data?.firstYearMonth ?? 0;
    const last = data?.lastYearMonth ?? 0;
    const current = data?.currentYearMonth ?? 0;
    const selected = queryYearMonth ?? current;
    const record = data?.record ?? null;

    // Clamp + re-fetch when the user steps/selects a month (legacy watch(queryYearMonth)).
    const selectMonth = useCallback(
        (ym: number) => {
            let clamped = ym;
            if (last > 0 && clamped > last) clamped = last;
            if (first > 0 && clamped < first) clamped = first;
            setQueryYearMonth(clamped);
            fetchData(clamped);
        },
        [first, last, fetchData],
    );

    // Build the dropdown options across [first, last] (verbatim "{year}년 {month}월 (선택)").
    const options: { value: number; text: string }[] = [];
    if (last >= first && last > 0) {
        for (let ym = first; ym <= last; ym += 1) {
            const [year, month] = parseYearMonth(ym);
            const info = ym === selected ? ' (선택)' : '';
            options.push({ value: ym, text: `${year}년 ${month}월${info}` });
        }
    }

    const [selYear, selMonth] = parseYearMonth(selected);

    return (
        <Shell>
            <h1 style={{ fontSize: 'var(--text-2xl)', fontWeight: 700, marginBottom: 'var(--space-md)' }}>연감</h1>

            {/* ── 연월 선택 (year/month selector) ─────────────────────────────────── */}
            <div
                className="control-bar"
                style={{ display: 'flex', gap: 'var(--space-sm)', marginBottom: 'var(--space-md)', flexWrap: 'wrap', alignItems: 'center' }}
            >
                <span style={{ fontSize: 'var(--text-sm)', color: 'var(--text-secondary)' }}>연월 선택:</span>
                <button onClick={() => selectMonth(selected - 1)} disabled={selected <= first || first === 0}>
                    ◀ 이전달
                </button>
                <select
                    value={selected}
                    onChange={(e) => selectMonth(Number(e.target.value))}
                    style={{ minWidth: '12rem' }}
                    disabled={options.length === 0}
                >
                    {options.length === 0 ? (
                        <option value={selected}>{`${selYear}년 ${selMonth}월`}</option>
                    ) : (
                        options.map((o) => (
                            <option key={o.value} value={o.value}>{o.text}</option>
                        ))
                    )}
                </select>
                <button onClick={() => selectMonth(selected + 1)} disabled={selected >= last || last === 0}>
                    다음달 ▶
                </button>
            </div>

            {loading && <p style={{ color: 'var(--text-muted)' }}>로딩 중...</p>}
            {error && <p style={{ color: 'var(--crimson)' }}>{error}</p>}

            {!loading && !error && record === null && (
                <GameCard>
                    <p style={{ color: 'var(--text-muted)', textAlign: 'center', margin: 0 }}>기록이 없습니다.</p>
                </GameCard>
            )}

            {record !== null && (
                <>
                    {/* ── 중원 정세 (global_history) ──────────────────────────────── */}
                    <div style={sectionBarStyle}>중원 정세</div>
                    <GameCard>
                        {record.globalHistory.length === 0 ? (
                            <p style={{ color: 'var(--text-muted)', textAlign: 'center', margin: 0 }}>기록이 없습니다.</p>
                        ) : (
                            <div>
                                {record.globalHistory.map((item, idx) => (
                                    <div key={idx} style={logRowStyle} dangerouslySetInnerHTML={{ __html: item }} />
                                ))}
                            </div>
                        )}
                    </GameCard>

                    {/* ── 장수 동향 (global_action) ───────────────────────────────── */}
                    <div style={sectionBarStyle}>장수 동향</div>
                    <GameCard>
                        {record.globalAction.length === 0 ? (
                            <p style={{ color: 'var(--text-muted)', textAlign: 'center', margin: 0 }}>기록이 없습니다.</p>
                        ) : (
                            <div>
                                {record.globalAction.map((item, idx) => (
                                    <div key={idx} style={logRowStyle} dangerouslySetInnerHTML={{ __html: item }} />
                                ))}
                            </div>
                        )}
                    </GameCard>
                </>
            )}
        </Shell>
    );
}
