'use client';

// ── page 16 · 연감 (History) — READ-ONLY per-month records viewer ──────────────
// Consumes api.history(yearMonth?) → HistoryResponse. Mirrors legacy hwe/v_history.php +
// ts/PageHistory.vue + API/Global/GetHistory.php. Title "연감".
//
// 와이어 정합: BE HistoryController가 PageHistory.vue 기대 셰이프({firstYearMonth, lastYearMonth,
// currentYearMonth, serverId, mapName, record})를 emit하고, record로 4섹션을 렌더한다:
//   국가표(SimpleNationList) · 중원 정세(globalHistory) · 장수 동향(globalAction). 지도 스냅샷(옛 아이소 MapViewer)은 옛 지도와 함께 지웠다
//   (M2-9, D113 — 이 화면에 새 지도를 따로 넣지 않는다. K5 연감(records/yearbook)이 K5-08 서버와 함께 이 화면을 대신한다).
//
// Single-server only in F4 (cross-server view dropped — spec OQ-8).
// yearMonth = Util::joinYearMonth (year*12 + (month-1)); parseYearMonth = [ym/12, ym%12+1].
//
// EMPTY-SAFE: record === null (empty range / no rows) → empty-state notice, selector still renders the
// [first,last] range. Empty globalHistory/globalAction → empty section bodies. Never crashes.

import { useEffect, useState, useCallback } from 'react';
import { Button, Flag, LogText, Panel, SectionHeader, EmptyState } from '@opensamguk/ui';
import Shell from '../../../components/Shell';
import PageHead from '../../../components/PageHead';
import RecordsTabs from '../../../components/records/RecordsTabs';
import { api } from '../../../lib/api';
import { useTurnRefresh } from '../../../hooks/useTurnRefresh';
import type { HistoryResponse, SimpleNationObj } from '../../../types/game';

// Verbatim from legacy ts/util/parseYearMonth.ts: [(yearMonth/12)|0, yearMonth%12 + 1].
function parseYearMonth(yearMonth: number): [number, number] {
    return [(yearMonth / 12) | 0, (yearMonth % 12) + 1];
}


// record.nations(jsonb 원형: 배열 또는 {key:obj} 맵) → SimpleNationObj 배열로 정규화(날조 없음, 통과만).
function normalizeNations(
    nations: SimpleNationObj[] | Record<string, SimpleNationObj> | null,
): SimpleNationObj[] {
    if (nations == null) return [];
    if (Array.isArray(nations)) return nations;
    return Object.values(nations);
}

function SimpleNationList({ nations }: { nations: SimpleNationObj[] }) {
    return (
        <div className="os-table-wrap">
            <table className="simple-nation-list os-table os-table--nowrap">
                <thead>
                    <tr>
                        <th scope="col" style={{ width: '44%' }}>국명</th>
                        <th scope="col" style={{ width: '23%', textAlign: 'right' }}>국력</th>
                        <th scope="col" style={{ width: '15%', textAlign: 'right' }}>장수</th>
                        <th scope="col" style={{ width: '15%', textAlign: 'right' }}>속령</th>
                    </tr>
                </thead>
                <tbody>
                    {nations.map((n) => (
                        <tr key={n.nation}>
                            <td>
                                {/* 국가색은 깃발에만(ADR-LITE-049) — 밝기 판정 텍스트 배경은 깃발로 대체. */}
                                <span className="nation-list__cell"><Flag color={n.color} />{n.name}</span>
                            </td>
                            <td className="os-num u-right">{(n.power ?? 0).toLocaleString()}</td>
                            <td className="os-num u-right">{(n.gennum ?? 0).toLocaleString()}</td>
                            <td className="os-num u-right"title={(n.cities ?? []).join(', ')}>{(n.cities ?? []).length}</td>
                        </tr>
                    ))}
                </tbody>
            </table>
        </div>
    );
}

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

    // 현재 선택 중인 연월 그대로 재조회(과거 열람 중이면 그 달을 유지) — OPENSAM-196.
    useTurnRefresh(() => {
        fetchData(queryYearMonth);
    });

    const first = data?.firstYearMonth ?? 0;
    const last = data?.lastYearMonth ?? 0;
    const current = data?.currentYearMonth ?? 0;
    const selected = queryYearMonth ?? current;
    const record = data?.record ?? null;
    const nations = normalizeNations(record?.nations ?? null);

    // Clamp + re-fetch when the user steps/selects a month (legacy watch(queryYearMonth)).
    const selectMonth = useCallback(
        (ym: number) => {
            let clamped = ym;
            // 라이브 월(현재 = currentYearMonth = last+1)까지 허용 (legacy watch: yearMonth > last+1 → last+1)
            const upper = current > last ? current : last;
            if (upper > 0 && clamped > upper) clamped = upper;
            if (first > 0 && clamped < first) clamped = first;
            setQueryYearMonth(clamped);
            fetchData(clamped);
        },
        [first, last, current, fetchData],
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
    // 라이브 월 옵션 (legacy generateYearMonthList: last+1=currentYearMonth → "(현재)")
    if (current > last && current > 0) {
        const [year, month] = parseYearMonth(current);
        const tags = [current === selected ? '선택' : '', '현재'].filter(Boolean);
        options.push({ value: current, text: `${year}년 ${month}월 (${tags.join(', ')})` });
    }

    const [selYear, selMonth] = parseYearMonth(selected);
    const upper = current > last ? current : last;
    const atFirst = selected <= first || first === 0;
    const atLast = selected >= upper || last === 0;

    return (
        <Shell>
            <PageHead title="연감" tabs={<RecordsTabs />} chip={`${selYear}年 ${selMonth}月`} />
            {/* ── 연월 선택 (year/month selector) ─────────────────────────────────── */}
            <div className="record-bar" role="group" aria-label="연월 선택">
                <span className="record-bar__label">연월 선택:</span>
                {atFirst
                    ? <Button size="sm" variant="ghost" disabled reason="기록의 첫 달입니다">◀ 이전달</Button>
                    : <Button size="sm" variant="ghost" onClick={() => selectMonth(selected - 1)}>◀ 이전달</Button>}
                <select
                    value={selected}
                    onChange={(e) => selectMonth(Number(e.target.value))}
                    style={{ minWidth: '12rem' }}
                    disabled={options.length === 0}
                    aria-label="연월"
                >
                    {options.length === 0 ? (
                        <option value={selected}>{`${selYear}년 ${selMonth}월`}</option>
                    ) : (
                        options.map((o) => (
                            <option key={o.value} value={o.value}>{o.text}</option>
                        ))
                    )}
                </select>
                {atLast
                    ? <Button size="sm" variant="ghost" disabled reason="가장 최근 달입니다">다음달 ▶</Button>
                    : <Button size="sm" variant="ghost" onClick={() => selectMonth(selected + 1)}>다음달 ▶</Button>}
            </div>

            {loading && <p className="text-muted">로딩 중...</p>}
            {error && <p role="alert" className="page-error">{error}</p>}
            {!loading && !error && record === null && (
                <Panel className="record-panel">
                    <EmptyState illustration="records" title="기록이 없습니다." />
                </Panel>
            )}
            {record !== null && (
                <>
                    <div className="record-grid">
                        {/* ── 2) 국가표(SimpleNationList) ───────────────────────────────── */}
                        <Panel className="record-panel">
                            <SectionHeader title="세력 일람" sub={`${nations.length}국`} />
                            {nations.length === 0 ? (
                                <p className="record-empty">세력 정보가 없습니다.</p>
                            ) : (
                                <SimpleNationList nations={nations} />
                            )}
                        </Panel>
                        {/* ── 3) 중원 정세 (global_history) — LogText(토큰→팔레트 span) ─────────────── */}
                        <Panel className="record-panel">
                            <SectionHeader title="중원 정세" tone="rust" sub={`${record.globalHistory.length}`} />
                            {record.globalHistory.length === 0 ? (
                                <EmptyState illustration="records" title="기록이 없습니다." />
                            ) : (
                                <div className="record-rows">
                                    {record.globalHistory.map((item, idx) => (
                                        <div key={idx} className="record-row"><LogText text={item} /></div>
                                    ))}
                                </div>
                            )}
                        </Panel>
                        {/* ── 4) 장수 동향 (global_action) — LogText(토큰→팔레트 span) ──────────────── */}
                        <Panel className="record-panel">
                            <SectionHeader title="장수 동향" tone="info" sub={`${record.globalAction.length}`} />
                            {record.globalAction.length === 0 ? (
                                <EmptyState illustration="records" title="기록이 없습니다." />
                            ) : (
                                <div className="record-rows">
                                    {record.globalAction.map((item, idx) => (
                                        <div key={idx} className="record-row"><LogText text={item} /></div>
                                    ))}
                                </div>
                            )}
                        </Panel>
                    </div>
                </>
            )}
        </Shell>
    );
}
