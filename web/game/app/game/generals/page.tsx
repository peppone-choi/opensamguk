'use client';

import { useEffect, useMemo, useState } from 'react';
import Shell from '../../../components/Shell';
import GameTable from '../../../components/GameTable';
import { api } from '../../../lib/api';
import { formatNumber } from '../../../lib/format';
import { matchesQuery } from '../../../lib/chosung';
import type { PublicGeneral } from '../../../types/game';

// 전체 장수 (page 14) + 세력 장수 (page 9-P0) fold.
//  - Consumes api.generalsList() → PublicGeneral[] (백엔드 GeneralsController, bare 배열).
//    공개·permission=0 surface. Legacy: Nation/GeneralList + Global/GeneralList column-projected list.
//  - 세력 장수 fold = the nation filter (전체 / 무소속 / per-nation) over the SAME public contract;
//    no separate endpoint, the public list already carries nationId/nationName/nationColor.
//  - 명성/계급은 레거시처럼 **레벨 버킷**으로 표시·정렬한다(GeneralList.vue): 명성 = "Lv {explevel}
//    ({honorText})" / sort by explevel, 계급 = "{dedLevelText} ({bill})" / sort by dedlevel. raw exp/ded가
//    아니라 버킷을 쓰는 이유는 레거시가 버킷 경계에서 동일하게 묶기 때문(경계마다 발산 방지).
//  - READ-ONLY this wave (no mutation wiring). Verbatim Korean headers from GeneralList.vue.
//  - EMPTY-SAFE: empty array renders an empty table, never crashes.

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

// 컬럼 정렬 키 — 각 헤더가 어떤 PublicGeneral 필드로 정렬되는지 매핑.
//  - 'name'      장수명: 레거시 GeneralList.vue 그대로 npc 우선 → name.localeCompare (텍스트).
//  - 'nation'    국가  : 국가명 localeCompare (텍스트). 레거시는 국가를 독립 컬럼으로 두지 않으나,
//                        opensamguk 테이블이 국가 컬럼을 노출하므로 자연스러운 텍스트 정렬로 둔다.
//  - 'explevel'  명성  : 레거시 GeneralList.vue는 explevel 버킷으로 정렬(raw experience 아님).
//  - 'dedlevel'  계급  : 레거시는 dedlevel 버킷으로 정렬(raw dedication 아님).
//  - 그 외        통/무/지/병력: 모두 숫자 정렬(레거시 sortableNumber).
// (금/쌀 컬럼은 미인증 공개 surface OQ-5라 PublicGeneral에 없으므로 노출하지 않는다 — 자금은 인증
//  세력 장수 목록(/api/nation/general-list)에서만 본다.)
type SortKey =
    | 'name'
    | 'nation'
    | 'leadership'
    | 'strength'
    | 'intel'
    | 'explevel'
    | 'dedlevel'
    | 'crew';

// 헤더 라벨 ↔ 정렬 키. null = 정렬 불가(없음). 레거시 헤더 순서/문자열 그대로.
const COLUMNS: { label: string; key: SortKey; text?: boolean }[] = [
    { label: '장수명', key: 'name', text: true },
    { label: '국가', key: 'nation', text: true },
    { label: '통솔', key: 'leadership' },
    { label: '무력', key: 'strength' },
    { label: '지력', key: 'intel' },
    { label: '명성', key: 'explevel' },
    { label: '계급', key: 'dedlevel' },
    { label: '병력', key: 'crew' },
];

export default function GeneralsPage() {
    const [data, setData] = useState<PublicGeneral[]>([]);
    const [search, setSearch] = useState('');
    const [nationFilter, setNationFilter] = useState<number | 'all'>('all');
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState('');
    // 정렬 상태 — sortKey가 null이면 서버 제공 순서(레거시 AG-Grid: 미클릭 시 rowData 원순서) 그대로.
    const [sortKey, setSortKey] = useState<SortKey | null>(null);
    const [sortDesc, setSortDesc] = useState(false);

    // 헤더 클릭 = 정렬 토글. 레거시 sortingOrder 모사:
    //  - 텍스트(장수명·국가): 첫 클릭 asc → desc 토글  (sortingOrder ["asc","desc",null])
    //  - 숫자 컬럼          : 첫 클릭 desc → asc 토글  (sortingOrder ["desc","asc",null])
    function toggleSort(key: SortKey, text?: boolean) {
        if (sortKey === key) {
            setSortDesc((d) => !d);
        } else {
            setSortKey(key);
            setSortDesc(!text); // 숫자=내림차순 우선, 텍스트=오름차순 우선
        }
    }

    const fetchData = () => {
        setLoading(true);
        setError('');
        api
            .generalsList()
            .then((res: PublicGeneral[]) => {
                // EMPTY-SAFE: 응답은 bare 배열. 빈 seed면 [] (200), 500 아님.
                setData(Array.isArray(res) ? res : []);
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

    // 정렬 — 필터 결과 위에 적용. Array.prototype.sort는 V8/모던 JS에서 stable →
    // 레거시 AG-Grid의 stable 정렬과 동일(동률 시 원순서 유지). sortKey=null이면 원순서 그대로.
    const sorted = useMemo(() => {
        if (sortKey === null) return filtered;
        const dir = sortDesc ? -1 : 1;
        const arr = [...filtered];
        arr.sort((a, b) => {
            if (sortKey === 'name') {
                // 레거시 GeneralList.vue name 컬럼 comparator 그대로: npc 우선, 그다음 name.localeCompare.
                const npcDiff = a.npc - b.npc;
                const cmp = npcDiff !== 0 ? npcDiff : a.name.localeCompare(b.name);
                return cmp * dir;
            }
            if (sortKey === 'nation') {
                const an = a.nationId === NO_NATION ? '무소속' : a.nationName || '무소속';
                const bn = b.nationId === NO_NATION ? '무소속' : b.nationName || '무소속';
                return an.localeCompare(bn) * dir;
            }
            // 숫자 컬럼 — 레거시 sortableNumber comparator (a-b).
            return (a[sortKey] - b[sortKey]) * dir;
        });
        return arr;
    }, [filtered, sortKey, sortDesc]);

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

    // 공개 surface 컬럼(GeneralList.vue normal view 중 permission=0 부분):
    //   장수명 · 국가 · 통솔/무력/지력 · 명성(Lv 버킷) · 계급(품관 버킷) · 병력
    // (자금 금/쌀은 OQ-5로 공개 surface에서 제외 — 인증 세력 목록에서만 노출.)
    // 헤더를 클릭 가능한 정렬 토글로 렌더(GameTable이 헤더 셀에 ReactNode를 그대로 렌더).
    const headers = COLUMNS.map((col) => (
        <button
            key={col.key}
            type="button"
            onClick={() => toggleSort(col.key, col.text)}
            aria-sort={sortKey === col.key ? (sortDesc ? 'descending' : 'ascending') : 'none'}
            title={`${col.label} 정렬`}
            style={{
                background: 'transparent',
                border: 'none',
                padding: 0,
                margin: 0,
                cursor: 'pointer',
                color: sortKey === col.key ? 'var(--gold)' : 'inherit',
                font: 'inherit',
                fontWeight: 600,
                display: 'inline-flex',
                alignItems: 'center',
                gap: '2px',
                whiteSpace: 'nowrap',
            }}
        >
            {col.label}
            <span aria-hidden style={{ fontSize: '0.85em', opacity: sortKey === col.key ? 1 : 0.25 }}>
                {sortKey === col.key ? (sortDesc ? '▼' : '▲') : '↕'}
            </span>
        </button>
    )) as unknown as string[]; // GameTable headers prop은 string[]이지만 런타임은 ReactNode를 그대로 렌더.

    const rows = sorted.map((g) => [
        <span key={`n-${g.generalId}`} style={{ color: npcColor(g.npc) }}>
            {g.name}
        </span>,
        <span key={`nat-${g.generalId}`} style={{ color: g.nationColor || 'var(--text-muted)' }}>
            {g.nationId === NO_NATION ? '무소속' : g.nationName || '무소속'}
        </span>,
        formatNumber(g.leadership),
        formatNumber(g.strength),
        formatNumber(g.intel),
        // 명성 = 레벨 버킷 "Lv {explevel}" + 칭호(honorText) 둘째 줄(레거시 GeneralList.vue:651 valueGetter).
        <span key={`exp-${g.generalId}`} style={{ display: 'inline-flex', flexDirection: 'column', lineHeight: 1.2 }}>
            <span>Lv {formatNumber(g.explevel)}</span>
            {g.honorText && (
                <small style={{ color: 'var(--text-muted)', fontSize: 'var(--text-xs)' }}>({g.honorText})</small>
            )}
        </span>,
        // 계급 = "{dedLevelText}" + 봉록(bill) 둘째 줄(레거시 GeneralList.vue:666 valueGetter).
        <span key={`ded-${g.generalId}`} style={{ display: 'inline-flex', flexDirection: 'column', lineHeight: 1.2 }}>
            <span>{g.dedLevelText}</span>
            <small style={{ color: 'var(--text-muted)', fontSize: 'var(--text-xs)' }}>({formatNumber(g.bill)})</small>
        </span>,
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
