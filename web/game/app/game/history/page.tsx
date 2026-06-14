'use client';

// ── page 16 · 연감 (History) — READ-ONLY per-month records viewer ──────────────
// Consumes api.history(yearMonth?) → HistoryResponse. Mirrors legacy hwe/v_history.php +
// ts/PageHistory.vue + API/Global/GetHistory.php. Title "연감".
//
// 와이어 정합: BE HistoryController가 PageHistory.vue 기대 셰이프({firstYearMonth, lastYearMonth,
// currentYearMonth, serverId, mapName, record})를 emit하고, record로 4섹션을 렌더한다:
//   1) 지도 스냅샷(MapViewer) 2) 국가표(SimpleNationList) 3) 중원 정세(globalHistory) 4) 장수 동향(globalAction).
//
// 색/태그 로그: globalHistory/globalAction 항목은 utilGame.formatLog()로 <R><B><1> 등 마크업을 HTML로
// 변환해 렌더(레거시 v-html="formatLog(item)"와 동형). BLOCKED: yearbook_history에 global_history/
// global_action 컬럼이 없어 두 배열은 항상 비어 있다(서버 BLOCKED, 날조 없음) → 빈 섹션 안내.
//
// 지도 스냅샷 주의: web/game MapViewer는 라이브 /api/map을 self-fetch하는 공유 컴포넌트라(두 맵뷰어 불변식)
// record.map 월별 스냅샷을 props로 받지 못한다. 따라서 "지도" 섹션은 현재 라이브 지도를 보여준다(월별
// 과거 스냅샷 렌더는 MapViewer 확장 필요 — 백로그). 데이터(record.map)는 BE가 전달하므로 향후 정합 가능.
//
// Single-server only in F4 (cross-server view dropped — spec OQ-8).
// yearMonth = Util::joinYearMonth (year*12 + (month-1)); parseYearMonth = [ym/12, ym%12+1].
//
// EMPTY-SAFE: record === null (empty range / no rows) → empty-state notice, selector still renders the
// [first,last] range. Empty globalHistory/globalAction → empty section bodies. Never crashes.

import { useEffect, useState, useCallback } from 'react';
import Shell from '../../../components/Shell';
import GameCard from '../../../components/GameCard';
import MapViewer from '../../../components/game/MapViewer';
import { api } from '../../../lib/api';
import { BRIGHT_COLOR_THRESHOLD } from '../../../lib/constants';
import { formatLog } from '../../../lib/utilGame';
import type { HistoryResponse, SimpleNationObj } from '../../../types/game';

// Verbatim from legacy ts/util/parseYearMonth.ts: [(yearMonth/12)|0, yearMonth%12 + 1].
function parseYearMonth(yearMonth: number): [number, number] {
    return [(yearMonth / 12) | 0, (yearMonth % 12) + 1];
}

// legacy isBrightColor: perceived-luminance threshold (r*.299 + g*.587 + b*.114) > 140 → black text.
// 출처: legacy/devsam-core/hwe/ts/util/isBrightColor.ts (canonical threshold > 140).
function isBrightColor(hex?: string): boolean {
    if (!hex) return false;
    const m = /^#?([0-9a-f]{6})$/i.exec(hex.trim());
    if (!m) return false;
    const v = parseInt(m[1], 16);
    const r = (v >> 16) & 0xff;
    const g = (v >> 8) & 0xff;
    const b = v & 0xff;
    return r * 0.299 + g * 0.587 + b * 0.114 > BRIGHT_COLOR_THRESHOLD;
}

// record.nations(jsonb 원형: 배열 또는 {key:obj} 맵) → SimpleNationObj 배열로 정규화(날조 없음, 통과만).
function normalizeNations(
    nations: SimpleNationObj[] | Record<string, SimpleNationObj> | null,
): SimpleNationObj[] {
    if (nations == null) return [];
    if (Array.isArray(nations)) return nations;
    return Object.values(nations);
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

// ── SimpleNationList(legacy ts/components/SimpleNationList.vue) — 국가표(국명/국력/장수/속령) ────────
function SimpleNationList({ nations }: { nations: SimpleNationObj[] }) {
    return (
        <table className="simple-nation-list" style={{ width: '100%', fontSize: 'var(--text-sm)' }}>
            <thead>
                <tr style={{ background: 'var(--bg-elevated)' }}>
                    <th style={{ width: '44%', textAlign: 'left' }}>국명</th>
                    <th style={{ width: '23%', textAlign: 'right' }}>국력</th>
                    <th style={{ width: '15%', textAlign: 'right' }}>장수</th>
                    <th style={{ width: '15%', textAlign: 'right' }}>속령</th>
                </tr>
            </thead>
            <tbody>
                {nations.map((n) => (
                    <tr key={n.nation}>
                        <td style={{ textAlign: 'left' }}>
                            <span style={{ color: isBrightColor(n.color) ? '#000' : '#fff', backgroundColor: n.color, padding: '0 4px' }}>
                                {n.name}
                            </span>
                        </td>
                        <td style={{ textAlign: 'right' }}>{(n.power ?? 0).toLocaleString()}</td>
                        <td style={{ textAlign: 'right' }}>{(n.gennum ?? 0).toLocaleString()}</td>
                        <td style={{ textAlign: 'right' }} title={(n.cities ?? []).join(', ')}>{(n.cities ?? []).length}</td>
                    </tr>
                ))}
            </tbody>
        </table>
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
                    {/* ── 1) 지도 스냅샷(MapViewer) ─────────────────────────────────── */}
                    <div style={sectionBarStyle}>세계 지도</div>
                    <GameCard>
                        <MapViewer />
                    </GameCard>

                    {/* ── 2) 국가표(SimpleNationList) ───────────────────────────────── */}
                    <div style={sectionBarStyle}>세력 일람</div>
                    <GameCard>
                        {nations.length === 0 ? (
                            <p style={{ color: 'var(--text-muted)', textAlign: 'center', margin: 0 }}>세력 정보가 없습니다.</p>
                        ) : (
                            <SimpleNationList nations={nations} />
                        )}
                    </GameCard>

                    {/* ── 3) 중원 정세 (global_history) — formatLog 적용 ─────────────── */}
                    <div style={sectionBarStyle}>중원 정세</div>
                    <GameCard>
                        {record.globalHistory.length === 0 ? (
                            <p style={{ color: 'var(--text-muted)', textAlign: 'center', margin: 0 }}>기록이 없습니다.</p>
                        ) : (
                            <div>
                                {record.globalHistory.map((item, idx) => (
                                    <div key={idx} style={logRowStyle} dangerouslySetInnerHTML={{ __html: formatLog(item) }} />
                                ))}
                            </div>
                        )}
                    </GameCard>

                    {/* ── 4) 장수 동향 (global_action) — formatLog 적용 ──────────────── */}
                    <div style={sectionBarStyle}>장수 동향</div>
                    <GameCard>
                        {record.globalAction.length === 0 ? (
                            <p style={{ color: 'var(--text-muted)', textAlign: 'center', margin: 0 }}>기록이 없습니다.</p>
                        ) : (
                            <div>
                                {record.globalAction.map((item, idx) => (
                                    <div key={idx} style={logRowStyle} dangerouslySetInnerHTML={{ __html: formatLog(item) }} />
                                ))}
                            </div>
                        )}
                    </GameCard>
                </>
            )}
        </Shell>
    );
}
