'use client';

import { useEffect, useMemo, useState } from 'react';
import Shell from '../../../components/Shell';
import GameTable from '../../../components/GameTable';
import { api } from '../../../lib/api';
import { formatNumber } from '../../../lib/format';
import { matchesQuery } from '../../../lib/chosung';
import type { GeneralListItem, GeneralListResponse } from '../../../types/game';

// 전체 장수 (page 14) + 세력 장수 (page 9-P0) fold.
//  - Consumes the foundation read contract api.generalsList() → GeneralListResponse (public,
//    permission=0 fields). Legacy: Nation/GeneralList + Global/GeneralList column-projected list.
//  - 세력 장수 fold = the nation filter (전체 / 무소속 / per-nation) over the SAME public contract;
//    no separate endpoint, the public list already carries nationId/nationName/nationColor.
//  - READ-ONLY this wave (no mutation wiring). Verbatim Korean headers from GeneralList.vue.
//  - EMPTY-SAFE: empty generals array renders an empty table, never crashes.

// NPC name color — verbatim port of legacy utilGame/getNPCColor.ts (npc type → CSS color).
function npcColor(npc: number): string | undefined {
    if (npc === 6) return 'mediumaquamarine';
    if (npc === 5) return 'darkcyan';
    if (npc === 4) return 'deepskyblue';
    if (npc >= 2) return 'cyan';
    if (npc === 1) return 'skyblue';
    return undefined;
}

// 무소속 (nationId 0) is rendered as a neutral-color group; everyone else by their nation.
const NO_NATION = 0;

export default function GeneralsPage() {
    const [data, setData] = useState<GeneralListItem[]>([]);
    const [search, setSearch] = useState('');
    const [nationFilter, setNationFilter] = useState<number | 'all'>('all');
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState('');

    const fetchData = () => {
        setLoading(true);
        setError('');
        api
            .generalsList()
            .then((res: GeneralListResponse) => {
                // EMPTY-SAFE: a fresh seed with no rows returns { generals: [] } (200), not a 500.
                setData(Array.isArray(res?.generals) ? res.generals : []);
            })
            .catch(() => setError('장수 목록을 불러올 수 없습니다.'))
            .finally(() => setLoading(false));
    };

    useEffect(fetchData, []);

    // 세력 목록 (fold) — distinct nations present in the public list, name+color preserved.
    // Insertion order matters (do NOT re-sort by id); first-seen order mirrors the list order.
    const nations = useMemo(() => {
        const seen = new Map<number, { id: number; name: string; color: string }>();
        for (const g of data) {
            if (!seen.has(g.nationId)) {
                seen.set(g.nationId, {
                    id: g.nationId,
                    name: g.nationId === NO_NATION ? '무소속' : g.nationName || '무소속',
                    color: g.nationColor || 'var(--text-muted)',
                });
            }
        }
        return Array.from(seen.values());
    }, [data]);

    const filtered = useMemo(() => {
        const byNation = nationFilter === 'all' ? data : data.filter((g) => g.nationId === nationFilter);
        const term = search.trim();
        if (!term) return byNation;
        return byNation.filter(
            (g) =>
                matchesQuery(g.name, term) ||
                matchesQuery(g.nationName || '', term) ||
                matchesQuery(g.officerLevelText || '', term) ||
                g.leadership.toString().includes(term) ||
                g.strength.toString().includes(term) ||
                g.intel.toString().includes(term),
        );
    }, [data, nationFilter, search]);

    if (loading) {
        return (
            <Shell>
                <h1 style={{ fontSize: 'var(--text-2xl)', marginBottom: 'var(--space-lg)' }}>전체 장수</h1>
                <p style={{ color: 'var(--text-muted)' }}>로딩 중...</p>
            </Shell>
        );
    }

    if (error) {
        return (
            <Shell>
                <h1 style={{ fontSize: 'var(--text-2xl)', marginBottom: 'var(--space-lg)' }}>전체 장수</h1>
                <p style={{ color: 'var(--crimson)' }}>{error}</p>
                <button
                    onClick={fetchData}
                    style={{
                        marginTop: 'var(--space-md)',
                        background: 'var(--bg-hover)',
                        color: 'var(--text-primary)',
                        fontSize: 'var(--text-sm)',
                        padding: 'var(--space-xs) var(--space-sm)',
                    }}
                >
                    다시 시도
                </button>
            </Shell>
        );
    }

    // Verbatim legacy column headers (GeneralList.vue normal view):
    //   장수명 · 국가 · 통솔/무력/지력 (능력치) · 관직 · 명성 · 자금(금/쌀) · 도시 · 병력
    const headers = ['장수명', '국가', '통솔', '무력', '지력', '명성', '계급', '금', '쌀', '병력'];
    const rows = filtered.map((g) => [
        <span key={`n-${g.generalId}`} style={{ color: npcColor(g.npc) }}>
            {g.name}
        </span>,
        <span key={`nat-${g.generalId}`} style={{ color: g.nationColor || 'var(--text-muted)' }}>
            {g.nationId === NO_NATION ? '무소속' : g.nationName || '무소속'}
        </span>,
        formatNumber(g.leadership),
        formatNumber(g.strength),
        formatNumber(g.intel),
        formatNumber(g.experience),
        formatNumber(g.dedication),
        formatNumber(g.gold),
        formatNumber(g.rice),
        formatNumber(g.crew),
    ]);

    return (
        <Shell>
            <h1 style={{ fontSize: 'var(--text-2xl)', marginBottom: 'var(--space-lg)' }}>전체 장수</h1>

            <div
                style={{
                    display: 'flex',
                    gap: 'var(--space-sm)',
                    flexWrap: 'wrap',
                    alignItems: 'center',
                    marginBottom: 'var(--space-md)',
                }}
            >
                {/* 세력 장수 fold — nation filter over the public list. */}
                <select
                    value={nationFilter === 'all' ? 'all' : String(nationFilter)}
                    onChange={(e) => setNationFilter(e.target.value === 'all' ? 'all' : Number(e.target.value))}
                    style={{
                        background: 'var(--bg-hover)',
                        color: 'var(--text-primary)',
                        fontSize: 'var(--text-sm)',
                        padding: 'var(--space-xs) var(--space-sm)',
                    }}
                >
                    <option value="all">전체</option>
                    {nations.map((n) => (
                        <option key={n.id} value={n.id}>
                            {n.name}
                        </option>
                    ))}
                </select>

                <input
                    type="text"
                    placeholder="장수 검색 (이름, 국가, 관직, 통/무/지)"
                    value={search}
                    onChange={(e) => setSearch(e.target.value)}
                    style={{
                        flex: '1 1 220px',
                        minWidth: '180px',
                        background: 'var(--bg-hover)',
                        color: 'var(--text-primary)',
                        fontSize: 'var(--text-sm)',
                        padding: 'var(--space-xs) var(--space-sm)',
                    }}
                />

                <span style={{ color: 'var(--text-muted)', fontSize: 'var(--text-sm)' }}>
                    {formatNumber(filtered.length)} / {formatNumber(data.length)}명
                </span>
            </div>

            {filtered.length === 0 ? (
                <p style={{ color: 'var(--text-muted)' }}>
                    {data.length === 0 ? '등록된 장수가 없습니다.' : '검색 결과가 없습니다.'}
                </p>
            ) : (
                <GameTable headers={headers} rows={rows} />
            )}
        </Shell>
    );
}
