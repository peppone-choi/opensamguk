'use client';

// 중원정보 (Global Diplomacy) — F4 page 2. READ-ONLY this wave (no mutation wiring).
// Frozen historical UI reference (ADR-LITE-042; not current product authority): legacy hwe/ts/PageGlobalDiplomacy.vue + hwe/sammo/API/Global/GetDiplomacy.php.
//
// Three sections, verbatim section titles. 색은 레거시 리터럴(blue/magenta/green)에서
// 야전 사령부 팔레트로 사상했다(화면 일체화) — 의미(정보/경고/전장)는 그대로 두고 이름만
// 토큰으로 옮긴 것이며, #fff 텍스트를 채도 높은 바탕에 얹던 대비 문제도 같이 없앤다:
//   외교 현황 (--info)  — the diplomacy matrix (nations × nations) with ★/▲/ㆍ/@ symbols.
//   분쟁 현황 (--rust)  — per-city 분쟁 share% feed (hidden when conflict[] is empty,
//                         exactly as legacy `v-if="diplomacy.conflict.length > 0"`).
//   중원 지도 (--moss)  — reuse <MapViewer/> (same component as the main screen) + a
//                         SimpleNationList-style nation panel.
//
// Symbol/color maps reproduced byte-for-byte from PageGlobalDiplomacy.vue:
//   infomative (a cell that involves the viewer's nation): 0★교전 1▲선포 2ㆍ 7@불가침
//   neutral    (a cell that does not involve the viewer):  0★교전 1▲선포 2(empty) 7"에러"
//   self cell: ＼ · involved-viewer cell background: --rust 틴트(구 #660000)
//   기호별 색은 .gd-state--war/--declared/--pact 로, 팔레트 토큰을 쓴다.
// GetDiplomacy already collapses neutral states 3-7→2 server-side for non-viewer rows,
// so the only states the maps ever see for neutral cells are 0/1/2(/7-error guard).
//
// EMPTY-SAFE: nations [] → empty matrix (header-only); conflict [] → 분쟁 section hidden;
// map unseeded → MapViewer renders its own placeholder. Never crashes.

import { useEffect, useState, useCallback } from 'react';
import PageHead from '../../../components/PageHead';
import Shell from '../../../components/Shell';
import GameCard from '../../../components/GameCard';
import MapViewer from '../../../components/game/MapViewer';
import { api } from '../../../lib/api';
import { BRIGHT_COLOR_THRESHOLD } from '../../../lib/constants';
import { formatCityName } from '../../../lib/utilGame/formatCityName';
import { useTurnRefresh } from '../../../hooks/useTurnRefresh';
import type {
    DiplomacyConflictResponse,
    ConflictNation,
    FrontInfoResponse,
    GameCityConstItem,
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
        case 0: return <span className="gd-state gd-state--war">★</span>;
        case 1: return <span className="gd-state gd-state--declared">▲</span>;
        case 2: return 'ㆍ';
        case 7: return <span className="gd-state gd-state--pact">@</span>;
        default: return null;
    }
}

// Verbatim from PageGlobalDiplomacy.vue neutralStateCharMap (cells not involving the viewer).
function neutralCell(state: number): React.ReactNode {
    switch (state) {
        case 0: return <span className="gd-state gd-state--war">★</span>;
        case 1: return <span className="gd-state gd-state--declared">▲</span>;
        case 2: return '';
        case 7: return '에러';
        default: return '';
    }
}

export default function GlobalDiplomacyPage() {
    const [data, setData] = useState<DiplomacyConflictResponse | null>(null);
    const [currentCityId, setCurrentCityId] = useState<number | null>(null);
    const [cityConst, setCityConst] = useState<GameCityConstItem[]>([]);
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

    // 도시 id → 이름 해석용 cityConst (legacy gameConstStore.cityConst 등가, /api/const).
    // 상수라 1회 로드. 분쟁 현황의 도시 id를 PageGlobalDiplomacy.vue:68처럼 도시명으로 표시.
    useEffect(() => {
        let on = true;
        api.gameConst()
            .then((c) => {
                if (on) setCityConst(c.cityConst ?? []);
            })
            .catch(() => {
                /* graceful: cityConst 미로드 시 도시 id 폴백 라벨 유지 */
            });
        return () => {
            on = false;
        };
    }, []);

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

    // 외교/분쟁 현황만 재조회(OPENSAM-196).
    useTurnRefresh(() => {
        fetchData();
    });

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
            <PageHead title="중원 정보" />

            <div className="control-bar u-row-md gap-md">
                <button onClick={fetchData}>새로고침</button>
            </div>

            {loading && <p className="text-muted">로딩 중...</p>}
            {error && <p className="page-error">{error}</p>}

            {/* ── 외교 현황 (diplomacy matrix) ─────────────────────────────────── */}
            <div
                className="section-title band-title band-title--info">
                외교 현황
            </div>
            <GameCard className="gd-section">
                <div className="u-scroll-x">
                    <table className="game-table gd-matrix">
                        <thead>
                            <tr>
                                <th></th>
                                {nations.map((nation) => (
                                    <th
                                        key={nation.nation}
                                        className="gd-matrix__col"
                                        style={{ color: isBrightColor(nation.color) ? '#000' : '#fff', backgroundColor: nation.color }}
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
                                        className="gd-matrix__row"
                                        style={{ color: isBrightColor(me.color) ? '#000' : '#fff', backgroundColor: me.color }}
                                    >
                                        {me.name}
                                    </th>
                                    {nations.map((you) => {
                                        if (me.nation === you.nation) {
                                            return (
                                                <td key={you.nation} className="u-center">
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
                                                className={`u-center${involvesViewer ? ' gd-matrix__cell--mine' : ''}`}
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
                                <td colSpan={nations.length + 1} className="u-center">
                                    불가침 : <span className="gd-state gd-state--pact">@</span>, 통상 : ㆍ, 선포 :{' '}
                                    <span className="gd-state gd-state--declared">▲</span>, 교전 :{' '}
                                    <span className="gd-state gd-state--war">★</span>
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
                        className="section-title band-title band-title--alert">
                        분쟁 현황
                    </div>
                    <GameCard className="gd-section">
                        <div className="u-stack-sm gd-conflicts">
                            {conflict.map(([cityId, conflictNations]) => (
                                <div key={cityId} className="gd-conflict">
                                    {/* legacy PageGlobalDiplomacy.vue:68 — gameConst.cityConst[cityID].name 으로 도시명 표시.
                                        cityConst 미로드/미존재 시에만 `도시 {id}` 폴백(날조 아님). */}
                                    <div
                                        className="gd-conflict__city"
                                    >
                                        {formatCityName(cityId, cityConst) || `도시 ${cityId}`}
                                    </div>
                                    <div className="gd-conflict__bars">
                                        {Object.entries(conflictNations).map(([nationIdStr, percent]) => {
                                            const nid = Number(nationIdStr);
                                            const nation = nationById.get(nid);
                                            const color = nation?.color ?? '#555555';
                                            const name = nation?.name ?? `세력 ${nid}`;
                                            return (
                                                <div key={nid} className="gd-bar">
                                                    <div
                                                        className="gd-bar__name"
                                                        style={{ color: isBrightColor(color) ? '#000' : '#fff', backgroundColor: color }}
                                                    >
                                                        {name}
                                                    </div>
                                                    <div className="gd-bar__pct">
                                                        {percent.toLocaleString(undefined, { minimumFractionDigits: 1 })}%
                                                    </div>
                                                    <div className="gd-bar__track">
                                                        <div
                                                            className="gd-bar__fill"
                                                            style={{ width: `${percent}%`, backgroundColor: color }}
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
                className="section-title band-title band-title--field">
                중원 지도
            </div>
            <div className="gd-split">
                <div className="gd-split__map">
                    <MapViewer />
                </div>
                <div className="gd-split__list">
                    <GameCard>
                        <div className="u-scroll-x">
                            <table className="game-table u-full">
                                <thead>
                                    <tr>
                                        <th className="gd-w50">국명</th>
                                        <th className="u-right gd-w25">국력</th>
                                        <th className="u-right gd-w25">속령</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {nations.map((n) => (
                                        <tr key={n.nation}>
                                            <td>
                                                <span
                                                    className="gd-nation-tag"
                                                    style={{ color: isBrightColor(n.color) ? '#000' : '#fff', backgroundColor: n.color }}
                                                    title={n.cities.join(', ')}
                                                >
                                                    {n.name}
                                                </span>
                                            </td>
                                            <td className="u-right">{n.power.toLocaleString()}</td>
                                            <td className="u-right" title={n.cities.join(', ')}>
                                                {n.cities.length.toLocaleString()}
                                            </td>
                                        </tr>
                                    ))}
                                    {nations.length === 0 && !loading && (
                                        <tr>
                                            <td colSpan={3} className="u-center text-muted">
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
