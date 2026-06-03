'use client';

// MapViewer — the interactive in-game world map (spec §1.1 `.mapView` center region; user chose the
// INTERACTIVE build over a placeholder). Grand-truth: legacy `MapViewer.vue` + `MapCityBasic.vue` +
// `state/mapViewer.ts`. Mirrors its structure (che 700×500 abstract map, nation-colored city dots,
// per-city hover tooltip 도시명/소속국, current-city highlight, click→select) but renders as a React 19
// SVG overlay — reusing the PROVEN gateway `MapPreview.tsx` math (viewBox 0 0 700 500, <image> bg+road,
// <circle> dots positioned by the scenario x/y the server already returns).
//
// Data flow: api.mapPreview() (GET /api/map/preview through the same-origin /api/game proxy — NO bare
// cross-origin fetch) → {serverName,year,month,mapCode,width,height,cities,nations}. The current city +
// nation come from useFrontInfo()'s frontInfo (passed in as props, no extra fetch).
//
// Interaction model: hover a dot → tooltip (도시명 / 【레벨】 / 소속국); click a dot → selects it (ring +
// onSelectCity callback) and opens MapCityDetail. Command-issuance-from-map is W5 (left as the clean
// onSelectCity seam). Graceful: loading→spinner, unseeded/empty→placeholder, never crash.

import { useEffect, useMemo, useState } from 'react';
import { api } from '@/lib/api';
import { MAP_CDN } from '@/lib/constants';
import type { MapPreviewCity, MapPreviewResponse } from '@/lib/types';
import { tintFlag, FLAG_FRAMES } from '@/lib/flagTint';
import MapCityDetail from './MapCityDetail';

const NEUTRAL_COLOR = '#555555';
const NEUTRAL_NAME = '공 백 지'; // legacy CityBasicCard nationNamePanel fallback

// season from month (3-5 spring / 6-8 summer / 9-11 fall / else winter) — identical to gateway MapPreview.
function seasonOf(month: number): string {
    if (month >= 3 && month <= 5) return 'spring';
    if (month >= 6 && month <= 8) return 'summer';
    if (month >= 9 && month <= 11) return 'fall';
    return 'winter';
}

export interface MapViewerProps {
    /** Current general's city id — drawn with a highlight ring (legacy `myCity`). */
    currentCityId?: number | null;
    /** Selection seam for W5 (command-from-map). Fires on every city click after select. */
    onSelectCity?: (city: MapPreviewCity) => void;
}

export default function MapViewer({ currentCityId, onSelectCity }: MapViewerProps) {
    const [data, setData] = useState<MapPreviewResponse | null>(null);
    const [failed, setFailed] = useState(false);
    const [hoverId, setHoverId] = useState<number | null>(null);
    const [selectedId, setSelectedId] = useState<number | null>(null);

    useEffect(() => {
        let on = true;
        setData(null);
        setFailed(false);
        api.mapPreview()
            .then((d) => {
                if (on) setData(d);
            })
            .catch(() => {
                if (on) setFailed(true);
            });
        return () => {
            on = false;
        };
    }, []);

    // nationId → {name,color} lookup (neutral = id 0, absent from nations[]).
    const nationById = useMemo(() => {
        const m = new Map<number, { name: string; color: string }>();
        data?.nations.forEach((n) => m.set(n.id, { name: n.name, color: n.color }));
        return m;
    }, [data]);

    const colorOf = (nid: number) => nationById.get(nid)?.color ?? NEUTRAL_COLOR;
    const nationNameOf = (nid: number) => nationById.get(nid)?.name ?? NEUTRAL_NAME;

    // 소유국(공백지 제외) 색별 깃발 dataURL을 1회 틴트 후 캐시. 색마다 한 번만 계산.
    const ownedColors = useMemo(() => {
        const s = new Set<string>();
        data?.cities.forEach((c) => {
            if (c.nationId !== 0) s.add(colorOf(c.nationId));
        });
        return Array.from(s);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [data, nationById]);
    // 색별 4프레임 dataURL 배열.
    const [flagUrls, setFlagUrls] = useState<Record<string, string[]>>({});
    useEffect(() => {
        let on = true;
        Promise.all(
            ownedColors.map((col) => tintFlag(col).then((urls) => [col, urls] as const).catch(() => null)),
        ).then((pairs) => {
            if (!on) return;
            const m: Record<string, string[]> = {};
            pairs.forEach((p) => { if (p) m[p[0]] = p[1]; });
            setFlagUrls(m);
        });
        return () => { on = false; };
    }, [ownedColors.join(',')]);
    // 깃발 4프레임 나부낌 — 맵 공용 프레임 카운터(setInterval) 하나로 전 깃발 동기 애니.
    const [flagFrame, setFlagFrame] = useState(0);
    useEffect(() => {
        const t = setInterval(() => setFlagFrame((f) => (f + 1) % FLAG_FRAMES), 180);
        return () => clearInterval(t);
    }, []);

    const selectedCity = useMemo(
        () => data?.cities.find((c) => c.id === selectedId) ?? null,
        [data, selectedId],
    );
    const hoverCity = useMemo(
        () => data?.cities.find((c) => c.id === hoverId) ?? null,
        [data, hoverId],
    );

    function selectCity(city: MapPreviewCity) {
        setSelectedId(city.id);
        onSelectCity?.(city);
    }

    // unseeded / failed / empty world → placeholder (never crash).
    if (failed || (data && data.cities.length === 0)) {
        return (
            <section className="map-viewer" aria-label="세계 지도">
                <div className="map-viewer-ph">지도 데이터 준비 중입니다.</div>
            </section>
        );
    }
    if (!data) {
        return (
            <section className="map-viewer" aria-label="세계 지도">
                <div className="map-viewer-ph">
                    <div className="spinner" />
                </div>
            </section>
        );
    }

    const mapCode = data.mapCode || 'che';
    const w = data.width || 700;
    const h = data.height || 500;
    const bg = `${MAP_CDN}/${mapCode}/bg_${seasonOf(data.month || 1)}.jpg`;
    const road = `${MAP_CDN}/${mapCode}/${mapCode}_road.png`;

    return (
        <section className="map-viewer" aria-label="세계 지도">
            <div className="map-viewer-canvas">
                <svg
                    viewBox={`0 0 ${w} ${h}`}
                    preserveAspectRatio="xMidYMid meet"
                    role="img"
                    aria-label={`${data.serverName} ${data.year}年 ${data.month}月 세계 지도`}
                >
                    <image href={bg} x={0} y={0} width={w} height={h} />
                    <image href={road} x={0} y={0} width={w} height={h} opacity={0.5} />
                    {data.cities.map((c) => {
                        const isCurrent = currentCityId != null && c.id === currentCityId;
                        const isSelected = c.id === selectedId;
                        const isHover = c.id === hoverId;
                        const r = c.level >= 7 ? 6 : 4;
                        return (
                            <g key={c.id}>
                                {isCurrent && (
                                    <circle
                                        cx={c.x}
                                        cy={c.y}
                                        r={r + 4}
                                        fill="none"
                                        stroke="#ffd54f"
                                        strokeWidth={2}
                                        className="map-current-ring"
                                    />
                                )}
                                {isSelected && (
                                    <circle
                                        cx={c.x}
                                        cy={c.y}
                                        r={r + 2.5}
                                        fill="none"
                                        stroke="#ffffff"
                                        strokeWidth={1.5}
                                    />
                                )}
                                <circle
                                    cx={c.x}
                                    cy={c.y}
                                    r={isHover ? r + 1.5 : r}
                                    fill={colorOf(c.nationId)}
                                    stroke="#0a0a0a"
                                    strokeWidth={1}
                                    className="map-city-dot"
                                    tabIndex={0}
                                    role="button"
                                    aria-label={`${c.name} 레벨 ${c.level} ${nationNameOf(c.nationId)}`}
                                    onMouseEnter={() => setHoverId(c.id)}
                                    onMouseLeave={() => setHoverId((id) => (id === c.id ? null : id))}
                                    onFocus={() => setHoverId(c.id)}
                                    onBlur={() => setHoverId((id) => (id === c.id ? null : id))}
                                    onClick={() => selectCity(c)}
                                    onKeyDown={(e) => {
                                        if (e.key === 'Enter' || e.key === ' ') {
                                            e.preventDefault();
                                            selectCity(c);
                                        }
                                    }}
                                >
                                    <title>{`${c.name} 【${c.level}】 ${nationNameOf(c.nationId)}`}</title>
                                </circle>
                                {/* 소유국 깃발(공백지 제외) — devsam 깃발 4프레임을 nation 색으로 캔바스 틴트, 도트 위. */}
                                {c.nationId !== 0 && flagUrls[colorOf(c.nationId)]?.[flagFrame] && (
                                    <image
                                        href={flagUrls[colorOf(c.nationId)][flagFrame]}
                                        x={c.x - 6.5}
                                        y={c.y - r - 14}
                                        width={13}
                                        height={13}
                                        className="map-flag"
                                        pointerEvents="none"
                                    />
                                )}
                                {c.level >= 7 && (
                                    <text
                                        x={c.x}
                                        y={c.y - r - 3}
                                        className="map-city-label"
                                        textAnchor="middle"
                                    >
                                        {c.name}
                                    </text>
                                )}
                            </g>
                        );
                    })}
                </svg>

                {/* HTML hover tooltip (legacy `.city_tooltip`) — fixed top-left of the canvas, no cursor math */}
                {hoverCity && (
                    <div className="map-tooltip" role="status">
                        <div className="map-tooltip-name">{hoverCity.name}</div>
                        <div className="map-tooltip-meta">
                            {`【${hoverCity.level}】 ${nationNameOf(hoverCity.nationId)}`}
                        </div>
                    </div>
                )}
            </div>

            <div className="map-viewer-cap">{`${data.serverName} · ${data.year}年 ${data.month}月`}</div>

            {/* Click-detail panel (MapCityDetail) — opens when a city is selected. */}
            {selectedCity && (
                <MapCityDetail
                    city={selectedCity}
                    nationName={nationNameOf(selectedCity.nationId)}
                    nationColor={colorOf(selectedCity.nationId)}
                    isCurrent={currentCityId != null && selectedCity.id === currentCityId}
                    onClose={() => setSelectedId(null)}
                />
            )}
        </section>
    );
}
