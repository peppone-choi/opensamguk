'use client';

// ── page 16 · 연감 (History) — READ-ONLY per-month records viewer ──────────────
// Consumes api.history(yearMonth?) → HistoryResponse. Mirrors legacy hwe/v_history.php +
// ts/PageHistory.vue + API/Global/GetHistory.php. Title "연감".
//
// 와이어 정합: BE HistoryController가 PageHistory.vue 기대 셰이프({firstYearMonth, lastYearMonth,
// currentYearMonth, serverId, mapName, record})를 emit하고, record로 4섹션을 렌더한다:
//   1) 지도 스냅샷(MapViewer) 2) 국가표(SimpleNationList) 3) 중원 정세(globalHistory) 4) 장수 동향(globalAction).
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
import MapViewer from '../../../components/game/MapViewer';
import { api } from '../../../lib/api';
import { useTurnRefresh } from '../../../hooks/useTurnRefresh';
import type { MapPreviewResponse } from '../../../lib/types';
import type { HistoryRecord, HistoryResponse, SimpleNationObj } from '../../../types/game';

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

function recordOf(value: unknown): Record<string, unknown> | null {
    if (value == null || typeof value !== 'object' || Array.isArray(value)) return null;
    return value as Record<string, unknown>;
}

function numberOf(value: unknown): number | null {
    return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

function archivedMapPreview(record: HistoryRecord, template: MapPreviewResponse | null): MapPreviewResponse | null {
    const snapshot = recordOf(record.map);
    const cityList = snapshot?.cityList;
    const nationList = snapshot?.nationList;
    if (snapshot == null || template == null || !Array.isArray(cityList) || !Array.isArray(nationList)) return null;

    const cityById = new Map<number, readonly unknown[]>();
    for (const tuple of cityList) {
        if (!Array.isArray(tuple)) continue;
        const id = numberOf(tuple[0]);
        if (id != null) cityById.set(id, tuple);
    }

    const capitalByNation = new Map<number, number>();
    const nations = nationList.flatMap((tuple) => {
        if (!Array.isArray(tuple)) return [];
        const id = numberOf(tuple[0]);
        const name = tuple[1];
        const color = tuple[2];
        const capital = numberOf(tuple[3]);
        if (id == null || id === 0 || typeof name !== 'string' || typeof color !== 'string') return [];
        if (capital != null) capitalByNation.set(id, capital);
        return [{ id, name, color }];
    });

    const cities = template.cities.flatMap((city) => {
        const tuple = cityById.get(city.id);
        const level = numberOf(tuple?.[1]);
        const state = numberOf(tuple?.[2]);
        const nationId = numberOf(tuple?.[3]);
        const supply = numberOf(tuple?.[5]);
        if (level == null || state == null || nationId == null || supply == null) return [];
        return [{
            ...city,
            level,
            state,
            nationId,
            supply: supply !== 0,
            isCapital: capitalByNation.get(nationId) === city.id,
        }];
    });

    return {
        ...template,
        serverName: record.serverId || template.serverName,
        startYear: numberOf(snapshot.startYear) ?? template.startYear,
        year: record.year,
        month: record.month,
        turnPhase: null,
        turnPhaseText: null,
        cities,
        nations,
    };
}



// ── SimpleNationList(legacy ts/components/SimpleNationList.vue) — 국가표(국명/국력/장수/속령) ────────
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
                            <td className="os-num" style={{ textAlign: 'right' }}>{(n.power ?? 0).toLocaleString()}</td>
                            <td className="os-num" style={{ textAlign: 'right' }}>{(n.gennum ?? 0).toLocaleString()}</td>
                            <td className="os-num" style={{ textAlign: 'right' }} title={(n.cities ?? []).join(', ')}>{(n.cities ?? []).length}</td>
                        </tr>
                    ))}
                </tbody>
            </table>
        </div>
    );
}

export default function HistoryPage() {
    const [data, setData] = useState<HistoryResponse | null>(null);
    const [mapTemplate, setMapTemplate] = useState<MapPreviewResponse | null>(null);
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

    useEffect(() => {
        let active = true;
        api.mapPreview()
            .then((template) => {
                if (active) setMapTemplate(template);
            })
            .catch(() => {
                if (active) setMapTemplate(null);
            });
        return () => {
            active = false;
        };
    }, []);

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
    const mapSnapshot = record == null ? null : archivedMapPreview(record, mapTemplate);

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
            {error && <p role="alert" style={{ color: 'var(--rust)' }}>{error}</p>}
            {!loading && !error && record === null && (
                <Panel className="record-panel">
                    <EmptyState illustration="records" title="기록이 없습니다." />
                </Panel>
            )}
            {record !== null && (
                <>
                    {/* ── 1) 지도 스냅샷(MapViewer) ─────────────────────────────────── */}
                    <Panel className="record-panel">
                        <SectionHeader title="세계 지도" sub={`${record.year}年 ${record.month}月`} />
                        {mapSnapshot == null ? (
                            <p className="record-empty">지도 스냅샷을 렌더할 수 없습니다.</p>
                        ) : (
                            <MapViewer mapData={mapSnapshot} isDetailMap disallowClick />
                        )}
                    </Panel>
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
