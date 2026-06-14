'use client';

// 중원정보 (Global Diplomacy) — F4 page 2. READ-ONLY this wave (no mutation wiring).
// Grand-truth: legacy hwe/ts/PageGlobalDiplomacy.vue + hwe/sammo/API/Global/GetDiplomacy.php.
//
// Three sections, verbatim section titles + colors (legacy `.tb-title` backgrounds):
//   외교 현황 (blue)   — the diplomacy matrix (nations × nations) with ★/▲/ㆍ/@ symbols.
//   분쟁 현황 (magenta) — per-city 분쟁 share% feed (hidden when conflict[] is empty,
//                         exactly as legacy `v-if="diplomacy.conflict.length > 0"`).
//   중원 지도 (green)   — reuse <MapViewer/> (same component as the main screen) + a
//                         SimpleNationList-style nation panel.
//
// Symbol/color maps reproduced byte-for-byte from PageGlobalDiplomacy.vue:
//   infomative (a cell that involves the viewer's nation): 0★red 1▲magenta 2ㆍ 7@green
//   neutral    (a cell that does not involve the viewer):  0★red 1▲magenta 2(empty) 7"에러"
//   self cell: ＼ · involved-viewer cell background: #660000
// GetDiplomacy already collapses neutral states 3-7→2 server-side for non-viewer rows,
// so the only states the maps ever see for neutral cells are 0/1/2(/7-error guard).
//
// EMPTY-SAFE: nations [] → empty matrix (header-only); conflict [] → 분쟁 section hidden;
// map unseeded → MapViewer renders its own placeholder. Never crashes.

import { useEffect, useState, useCallback } from 'react';
import Shell from '../../../components/Shell';
import GameCard from '../../../components/GameCard';
import MapViewer from '../../../components/game/MapViewer';
import { api } from '../../../lib/api';
import { BRIGHT_COLOR_THRESHOLD } from '../../../lib/constants';
import type {
    DiplomacyConflictResponse,
    ConflictNation,
    FrontInfoResponse,
} from '../../../lib/types';

// legacy isBrightColor: perceived-luminance threshold (r*.299 + g*.587 + b*.114) > 140 → black text.
function isBrightColor(color: string): boolean {
    const m = /^#?([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(color.trim());
    if (!m) return false;
    const r = parseInt(m[1], 16);
    const g = parseInt(m[2], 16);
    const b = parseInt(m[3], 16);
    return r * 0.299 + g * 0.587 + b * 0.114 > BRIGHT_COLOR_THRESHOLD;
}

// Verbatim from PageGlobalDiplomacy.vue infomativeStateCharMap (cells involving the viewer).
function infomativeCell(state: number): React.ReactNode {
    switch (state) {
        case 0: return <span style={{ color: 'red' }}>★</span>;
        case 1: return <span style={{ color: 'magenta' }}>▲</span>;
        case 2: return 'ㆍ';
        case 7: return <span style={{ color: 'green' }}>@</span>;
        default: return null;
    }
}

// Verbatim from PageGlobalDiplomacy.vue neutralStateCharMap (cells not involving the viewer).
function neutralCell(state: number): React.ReactNode {
    switch (state) {
        case 0: return <span style={{ color: 'red' }}>★</span>;
        case 1: return <span style={{ color: 'magenta' }}>▲</span>;
        case 2: return '';
        case 7: return '에러';
        default: return '';
    }
}

export default function GlobalDiplomacyPage() {
    const [data, setData] = useState<DiplomacyConflictResponse | null>(null);
    const [currentCityId, setCurrentCityId] = useState<number | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string>('');

    const fetchData = useCallback(async () => {
        try {
            const d = await api.diplomacyConflict();
            setData(d);
            setError('');
        } catch {
            setError('데이터를 불러올 수 없습니다.');
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => {
        fetchData();
    }, [fetchData]);

    // current general's city → MapViewer highlight ring (no extra map fetch; MapViewer fetches its own).
    useEffect(() => {
        let on = true;
        api.frontInfo()
            .then((fi: FrontInfoResponse) => {
                if (on) setCurrentCityId(fi.general?.cityId ?? null);
            })
            .catch(() => {
                /* graceful: no highlight ring if front-info unavailable */
            });
        return () => {
            on = false;
        };
    }, []);

    useEffect(() => {
        const es = new EventSource('/api/game/sse/turn');
        es.addEventListener('turnCompleted', () => fetchData());
        es.onerror = () => es.close();
        return () => es.close();
    }, [fetchData]);

    const nations: ConflictNation[] = data?.nations ?? [];
    const conflict = data?.conflict ?? [];
    const diplomacyList = data?.diplomacyList ?? {};
    // P0-19 — 와이어 키는 PHP-verbatim `myNationID`/`nation`(F4Dto.SimpleNationObj 직렬화 그대로).
    const myNationId = data?.myNationID ?? 0;

    // nation(국가 id) → {name,color} for the conflict feed lookups.
    const nationById = new Map<number, ConflictNation>();
    nations.forEach((n) => nationById.set(n.nation, n));

    function cellState(me: number, you: number): number | undefined {
        return diplomacyList[me]?.[you];
    }

    return (
        <Shell>
            <h1 style={{ fontSize: 'var(--text-2xl)', fontWeight: 700, marginBottom: 'var(--space-md)' }}>중원 정보</h1>

            <div className="control-bar" style={{ display: 'flex', gap: 'var(--space-md)', marginBottom: 'var(--space-md)', flexWrap: 'wrap', alignItems: 'center' }}>
                <button onClick={fetchData}>새로고침</button>
            </div>

            {loading && <p style={{ color: 'var(--text-muted)' }}>로딩 중...</p>}
            {error && <p style={{ color: 'var(--crimson)' }}>{error}</p>}

            {/* ── 외교 현황 (diplomacy matrix) ─────────────────────────────────── */}
            <div
                className="section-title"
                style={{ background: 'blue', color: '#fff', textAlign: 'center', fontSize: 'var(--text-lg)', fontWeight: 600, padding: 'var(--space-xs) var(--space-sm)', marginBottom: 'var(--space-sm)' }}
            >
                외교 현황
            </div>
            <GameCard style={{ marginBottom: 'var(--space-xl)' }}>
                <div style={{ overflowX: 'auto' }}>
                    <table className="game-table" style={{ margin: 'auto', minWidth: 400 }}>
                        <thead>
                            <tr>
                                <th></th>
                                {nations.map((nation) => (
                                    <th
                                        key={nation.nation}
                                        style={{
                                            textAlign: 'center',
                                            color: isBrightColor(nation.color) ? '#000' : '#fff',
                                            backgroundColor: nation.color,
                                            minWidth: '1ch',
                                            maxWidth: '3ch',
                                            fontWeight: 'normal',
                                        }}
                                    >
                                        {nation.name}
                                    </th>
                                ))}
                            </tr>
                        </thead>
                        <tbody>
                            {nations.map((me) => (
                                <tr key={me.nation}>
                                    <th
                                        style={{
                                            textAlign: 'right',
                                            paddingRight: '1ch',
                                            paddingLeft: '1ch',
                                            minWidth: '10ch',
                                            color: isBrightColor(me.color) ? '#000' : '#fff',
                                            backgroundColor: me.color,
                                            fontWeight: 'normal',
                                        }}
                                    >
                                        {me.name}
                                    </th>
                                    {nations.map((you) => {
                                        if (me.nation === you.nation) {
                                            return (
                                                <td key={you.nation} style={{ textAlign: 'center' }}>
                                                    ＼
                                                </td>
                                            );
                                        }
                                        const state = cellState(me.nation, you.nation);
                                        const involvesViewer =
                                            me.nation === myNationId || you.nation === myNationId;
                                        return (
                                            <td
                                                key={you.nation}
                                                style={{
                                                    textAlign: 'center',
                                                    ...(involvesViewer ? { backgroundColor: '#660000' } : {}),
                                                }}
                                            >
                                                {state == null
                                                    ? ''
                                                    : involvesViewer
                                                        ? infomativeCell(state)
                                                        : neutralCell(state)}
                                            </td>
                                        );
                                    })}
                                </tr>
                            ))}
                        </tbody>
                        <tfoot>
                            <tr>
                                <td colSpan={nations.length + 1} style={{ textAlign: 'center' }}>
                                    불가침 : <span style={{ color: 'limegreen' }}>@</span>, 통상 : ㆍ, 선포 :{' '}
                                    <span style={{ color: 'magenta' }}>▲</span>, 교전 :{' '}
                                    <span style={{ color: 'red' }}>★</span>
                                </td>
                            </tr>
                        </tfoot>
                    </table>
                </div>
            </GameCard>

            {/* ── 분쟁 현황 (conflict feed) — hidden when no contested cities ──── */}
            {conflict.length > 0 && (
                <>
                    <div
                        className="section-title"
                        style={{ background: 'magenta', color: '#fff', textAlign: 'center', fontSize: 'var(--text-lg)', fontWeight: 600, padding: 'var(--space-xs) var(--space-sm)', marginBottom: 'var(--space-sm)' }}
                    >
                        분쟁 현황
                    </div>
                    <GameCard style={{ marginBottom: 'var(--space-xl)' }}>
                        <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-md)' }}>
                            {conflict.map(([cityId, conflictNations]) => (
                                <div key={cityId} style={{ display: 'flex', gap: 'var(--space-sm)', alignItems: 'flex-start', flexWrap: 'wrap' }}>
                                    {/* legacy resolves the city name from gameConst.cityConst[cityID]; the F4
                                        conflict contract keys by cityId only — render the id-labelled row,
                                        per-nation share% verbatim (toLocaleString minimumFractionDigits:1). */}
                                    <div
                                        style={{
                                            flexBasis: '16ch',
                                            textAlign: 'right',
                                            paddingRight: '1ch',
                                            alignSelf: 'center',
                                            color: 'var(--text-secondary)',
                                        }}
                                    >
                                        도시 {cityId}
                                    </div>
                                    <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 'var(--space-xs)' }}>
                                        {Object.entries(conflictNations).map(([nationIdStr, percent]) => {
                                            const nid = Number(nationIdStr);
                                            const nation = nationById.get(nid);
                                            const color = nation?.color ?? '#555555';
                                            const name = nation?.name ?? `세력 ${nid}`;
                                            return (
                                                <div key={nid} style={{ display: 'flex', gap: 'var(--space-xs)', alignItems: 'center' }}>
                                                    <div
                                                        style={{
                                                            flexBasis: '16ch',
                                                            paddingLeft: '1ch',
                                                            color: isBrightColor(color) ? '#000' : '#fff',
                                                            backgroundColor: color,
                                                        }}
                                                    >
                                                        {name}
                                                    </div>
                                                    <div style={{ flexBasis: '6ch', textAlign: 'right', paddingRight: '0.5ch' }}>
                                                        {percent.toLocaleString(undefined, { minimumFractionDigits: 1 })}%
                                                    </div>
                                                    <div style={{ flex: 1, alignSelf: 'center' }}>
                                                        <div
                                                            style={{
                                                                width: `${percent}%`,
                                                                marginLeft: 0,
                                                                height: '1.2em',
                                                                backgroundColor: color,
                                                            }}
                                                        />
                                                    </div>
                                                </div>
                                            );
                                        })}
                                    </div>
                                </div>
                            ))}
                        </div>
                    </GameCard>
                </>
            )}

            {/* ── 중원 지도 (map + nation list) ────────────────────────────────── */}
            <div
                className="section-title"
                style={{ background: 'green', color: '#fff', textAlign: 'center', fontSize: 'var(--text-lg)', fontWeight: 600, padding: 'var(--space-xs) var(--space-sm)', marginBottom: 'var(--space-sm)' }}
            >
                중원 지도
            </div>
            <div style={{ display: 'flex', gap: 'var(--space-md)', flexWrap: 'wrap', alignItems: 'flex-start' }}>
                <div style={{ flex: '2 1 360px', minWidth: 0 }}>
                    <MapViewer />
                </div>
                <div style={{ flex: '1 1 260px', minWidth: 240 }}>
                    <GameCard>
                        <div style={{ overflowX: 'auto' }}>
                            <table className="game-table" style={{ width: '100%' }}>
                                <thead>
                                    <tr>
                                        <th style={{ width: '50%' }}>국명</th>
                                        <th style={{ width: '25%', textAlign: 'right' }}>국력</th>
                                        <th style={{ width: '25%', textAlign: 'right' }}>속령</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {nations.map((n) => (
                                        <tr key={n.nation}>
                                            <td>
                                                <span
                                                    style={{
                                                        color: isBrightColor(n.color) ? '#000' : '#fff',
                                                        backgroundColor: n.color,
                                                        padding: '0 0.5ch',
                                                    }}
                                                    title={n.cities.join(', ')}
                                                >
                                                    {n.name}
                                                </span>
                                            </td>
                                            <td style={{ textAlign: 'right' }}>{n.power.toLocaleString()}</td>
                                            <td style={{ textAlign: 'right' }} title={n.cities.join(', ')}>
                                                {n.cities.length.toLocaleString()}
                                            </td>
                                        </tr>
                                    ))}
                                    {nations.length === 0 && !loading && (
                                        <tr>
                                            <td colSpan={3} style={{ textAlign: 'center', color: 'var(--text-muted)' }}>
                                                활동 중인 세력이 없습니다.
                                            </td>
                                        </tr>
                                    )}
                                </tbody>
                            </table>
                        </div>
                    </GameCard>
                </div>
            </div>
        </Shell>
    );
}
