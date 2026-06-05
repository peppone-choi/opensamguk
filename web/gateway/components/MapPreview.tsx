'use client';

import { useEffect, useMemo, useRef, useState } from 'react';
import { MAP_CDN } from '../lib/constants';
import { tintFlag, FLAG_FRAMES } from '../lib/flagTint';
import cityRegionsData from '../config/cityRegions.json';

// 도시 id → 지역명(지리 속성, 소유 무관). 맵에 지역 라벨을 지역별 도시 중심점에 표시.
const CITY_REGIONS = cityRegionsData.regions as Record<string, string>;

/**
 * 서버 세계지도 프리뷰 (서버마다 10분 캐싱) — 로비용 정적 마커 맵.
 *
 * 흐름(결정 로그 §9): game-engine 10분 스냅샷 → game-api `GET /api/map/preview`(10분 캐시) →
 * 게이트웨이 route handler `/api/server-map/[id]`가 해당 서버 game-api로 서버사이드 프록시 →
 * 여기서 opensamguk-images CDN 추상 게임맵(che bg/road) 위에 인게임 MapViewer와 동일한 정적 마커
 * (오오라 glow + 성 아이콘 cast_<lv>.gif + 국가색 깃발 4프레임 + 수도 별 event51)를 렌더한다.
 * 좌표 = php 정본 native 700×500 좌표계(서버가 응답 width/height/cities에 포함). map-world를 캔버스
 * 폭에 맞춰 transform: scale()로 균일 확대 → 좌표·아이콘·폰트가 php 비율 그대로 1000폭으로 커진다.
 *
 * 인게임 MapViewer와 다른 점(로비 프리뷰 = glance용): 줌/팬/클릭 상세 없음. hover 시에만 도시 정보
 * 툴팁(레거시 .city_tooltip = 도시명 + 소속국, 레벨 추가)을 커서 위치에 띄운다. 공백지(nationId=0)는
 * 회색 오오라 + 깃발 없음으로 렌더(인게임과 동일) — 시드에 공백지가 있으면 그대로 보인다.
 */

const NEUTRAL_COLOR = '#555555';
const NEUTRAL_NAME = '공 백 지';

// city_base 박스 크기 (레거시 $cityBaseWidth/$cityBaseHeight).
const BASE_W = 40;
const BASE_H = 30;

// 레거시 $detailMapCitySizes — (level, areaW, areaH, iconW, iconH, flagRight, flagTop). 인게임 MapViewer와 동일.
interface CitySize {
    areaW: number;
    areaH: number;
    iconW: number;
    iconH: number;
    flagRight: number;
    flagTop: number;
}
const DETAIL_SIZES: Record<number, CitySize> = {
    1: { areaW: 48, areaH: 45, iconW: 16, iconH: 15, flagRight: -8, flagTop: -4 },
    2: { areaW: 60, areaH: 42, iconW: 20, iconH: 14, flagRight: -8, flagTop: -4 },
    3: { areaW: 42, areaH: 42, iconW: 14, iconH: 14, flagRight: -8, flagTop: -4 },
    4: { areaW: 60, areaH: 45, iconW: 20, iconH: 15, flagRight: -6, flagTop: -3 },
    5: { areaW: 72, areaH: 48, iconW: 24, iconH: 16, flagRight: -6, flagTop: -4 },
    6: { areaW: 78, areaH: 54, iconW: 26, iconH: 18, flagRight: -6, flagTop: -4 },
    7: { areaW: 84, areaH: 60, iconW: 28, iconH: 20, flagRight: -6, flagTop: -4 },
    8: { areaW: 96, areaH: 72, iconW: 32, iconH: 24, flagRight: -6, flagTop: -3 },
};
function sizeOf(level: number): CitySize {
    return DETAIL_SIZES[level] ?? DETAIL_SIZES[3];
}

// 치소 등급 라벨 (레거시 defs/index.ts CityLevelText) — lv 4 = 이민족 전용 "이", 한족 군 치소 lv 5 "소".
const LEVEL_TEXT: Record<number, string> = {
    1: '수', 2: '진', 3: '관', 4: '이', 5: '소', 6: '중', 7: '대', 8: '특',
};
function levelText(level: number): string {
    return LEVEL_TEXT[level] ?? String(level);
}

// CDN 베이스맵 코드 — 시나리오가 맵을 특정 못한 경우(mapCode="scenario") che 베이스로 폴백.
const CDN_MAPS = new Set(['che', 'chess', 'cr', 'miniche']);
function cdnMapCode(mc: string): string {
    return CDN_MAPS.has(mc) ? mc : 'che';
}

interface MapCity {
    id: number;
    name: string;
    level: number;
    nationId: number;
    x: number;
    y: number;
    state?: number;
    supply?: boolean;
    isCapital?: boolean;
}
interface MapNation {
    id: number;
    name: string;
    color: string;
}
interface MapData {
    serverName: string;
    year: number;
    month: number;
    mapCode: string;
    width: number;
    height: number;
    cities: MapCity[];
    nations: MapNation[];
}

function seasonOf(month: number): string {
    if (month >= 3 && month <= 5) return 'spring';
    if (month >= 6 && month <= 8) return 'summer';
    if (month >= 9 && month <= 11) return 'fall';
    return 'winter';
}

export default function MapPreview({ serverId = 'main', serverName }: { serverId?: string; serverName?: string }) {
    const [data, setData] = useState<MapData | null>(null);
    const [failed, setFailed] = useState(false);
    const [hoverId, setHoverId] = useState<number | null>(null);
    const [cursor, setCursor] = useState({ x: 0, y: 0 });

    useEffect(() => {
        let on = true;
        setData(null);
        setFailed(false);
        fetch(`/api/server-map/${serverId}`, { cache: 'no-store' })
            .then((r) => (r.ok ? r.json() : Promise.reject(new Error(String(r.status)))))
            .then((d: MapData) => {
                if (on) setData(d);
            })
            .catch(() => {
                if (on) setFailed(true);
            });
        return () => {
            on = false;
        };
    }, [serverId]);

    // nationId → {name,color} 룩업 (공백지 = id 0, nations[]에 없음).
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
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [ownedColors.join(',')]);
    // 깃발 4프레임 나부낌 — 공용 프레임 카운터 하나로 전 깃발 동기 애니.
    const [flagFrame, setFlagFrame] = useState(0);
    useEffect(() => {
        const t = setInterval(() => setFlagFrame((f) => (f + 1) % FLAG_FRAMES), 180);
        return () => clearInterval(t);
    }, []);

    // 맵 좌표계(native 700×500)를 캔버스 폭에 맞추는 외곽 스케일 — ResizeObserver로 실제 폭 추적.
    const canvasRef = useRef<HTMLDivElement | null>(null);
    const [canvasW, setCanvasW] = useState(0);
    useEffect(() => {
        const el = canvasRef.current;
        if (!el) return;
        const update = () => setCanvasW(el.clientWidth);
        update();
        const ro = new ResizeObserver(update);
        ro.observe(el);
        return () => ro.disconnect();
    }, [data]);

    const hoverCity = useMemo(
        () => data?.cities.find((c) => c.id === hoverId) ?? null,
        [data, hoverId],
    );


    // 미시드/실패/빈 세계 → placeholder
    if (failed || (data && data.cities.length === 0)) {
        return (
            <div className="map-preview" aria-label="서버 지도 프리뷰">
                <div className="map-preview-ph">맵 프리뷰 (준비 중)</div>
            </div>
        );
    }
    if (!data) {
        return (
            <div className="map-preview" aria-label="서버 지도 프리뷰">
                <div className="map-preview-ph">
                    <div className="spinner" />
                </div>
            </div>
        );
    }

    const mapCode = cdnMapCode(data.mapCode || 'che');
    const w = data.width || 700;
    const h = data.height || 500;
    const bg = `${MAP_CDN}/${mapCode}/bg_${seasonOf(data.month || 1)}.jpg`;
    const road = `${MAP_CDN}/${mapCode}/${mapCode}_road.png`;

    return (
        <div className="map-preview" aria-label="서버 지도 프리뷰">
            <div
                ref={canvasRef}
                className="map-preview-canvas"
                style={{ aspectRatio: `${w} / ${h}` }}
                onMouseMove={(e) => {
                    const r = canvasRef.current?.getBoundingClientRect();
                    if (r) setCursor({ x: e.clientX - r.left, y: e.clientY - r.top });
                }}
                onMouseLeave={() => setHoverId(null)}
            >
                {/* 맵 좌표계(data.width×height)를 캔버스 폭에 맞춘 정적 스케일 레이어(줌/팬 없음). */}
                <div
                    className="map-world"
                    style={{ width: w, height: h, transform: `scale(${(canvasW || w) / w})` }}
                >
                    {/* eslint-disable-next-line @next/next/no-img-element */}
                    <img className="map-bg" src={bg} alt="" width={w} height={h} draggable={false} />
                    {/* eslint-disable-next-line @next/next/no-img-element */}
                    <img className="map-road" src={road} alt="" width={w} height={h} draggable={false} />

                    {data.cities.map((c) => {
                        const sz = sizeOf(c.level);
                        const owned = c.nationId !== 0;
                        const unsupplied = owned && c.supply === false;
                        const col = colorOf(c.nationId);
                        const baseLeft = c.x - BASE_W / 2;
                        const baseTop = c.y - BASE_H / 2;
                        const imgLeft = (BASE_W - sz.iconW) / 2;
                        const imgTop = (BASE_H - sz.iconH) / 2;
                        const auraLeft = (BASE_W - sz.areaW) / 2;
                        const auraTop = (BASE_H - sz.areaH) / 2;
                        const showState = (c.state ?? 0) > 0 && (c.state ?? 0) <= 5;
                        const flagFrameUrl = owned ? flagUrls[col]?.[flagFrame] : undefined;

                        return (
                            <div
                                key={c.id}
                                className={`city-base${unsupplied ? ' supply-off' : ''}`}
                                style={{ left: baseLeft, top: baseTop, width: BASE_W, height: BASE_H }}
                                onMouseEnter={() => setHoverId(c.id)}
                                onMouseLeave={() => setHoverId((id) => (id === c.id ? null : id))}
                            >
                                {/* 1) 오오라(city_bg) — 소유국만(레거시 b<color>.png radial glow). 공백지=오오라 없음. city_img의 형제. */}
                                {owned && (
                                    <div
                                        className="city-aura"
                                        style={{
                                            left: auraLeft,
                                            top: auraTop,
                                            width: sz.areaW,
                                            height: sz.areaH,
                                            background: `radial-gradient(ellipse at center, ${col}cc 0%, ${col}66 40%, ${col}22 58%, transparent 74%)`,
                                        }}
                                    />
                                )}

                                {/* 아이콘 컨테이너(city_img) — 깃발/상태/이름이 모두 이 안에서 아이콘 기준 위치(레거시 DOM 구조). */}
                                <div
                                    className="city-img"
                                    style={{ left: imgLeft, top: imgTop, width: sz.iconW, height: sz.iconH }}
                                >
                                    {/* 2) 성 아이콘 cast_<level>.gif — city_img를 채움(픽셀아트). */}
                                    {/* eslint-disable-next-line @next/next/no-img-element */}
                                    <img className="city-cast" src={`/icons/cast_${c.level}.gif`} alt="" draggable={false} />

                                    {/* 5) 상태 아이콘 event<state>.gif — 레거시 {top:5;left:0} 아이콘 기준. */}
                                    {showState && (
                                        // eslint-disable-next-line @next/next/no-img-element
                                        <img className="city-state" src={`/icons/event${c.state}.gif`} alt="" draggable={false} />
                                    )}

                                    {/* 3) 깃발(소유국만) — 레거시 {right:flagRight;top:flagTop} 아이콘 기준. */}
                                    {owned && flagFrameUrl && (
                                        <span className="city-flag" style={{ right: sz.flagRight, top: sz.flagTop }}>
                                            {/* eslint-disable-next-line @next/next/no-img-element */}
                                            <img className="city-flag-img" src={flagFrameUrl} alt="" width={12} height={12} draggable={false} />
                                            {/* 4) 수도 별 event51.gif — 깃발 우상단. */}
                                            {c.isCapital && (
                                                // eslint-disable-next-line @next/next/no-img-element
                                                <img className="city-capital" src="/icons/event51.gif" alt="" width={10} height={10} draggable={false} />
                                            )}
                                        </span>
                                    )}

                                    {/* 6) 도시명(city_detail_name) — 레거시 {left:70%;bottom:-10px} 아이콘 기준, 항상 표시. */}
                                    <span className="city-name">{c.name}</span>
                                </div>
                            </div>
                        );
                    })}
                </div>
            </div>

            {/* hover 도시정보 툴팁(레거시 .city_tooltip) — 커서 추종. canvas(overflow:hidden) 밖 .map-preview에 두어
                경계에서 잘리지 않게. 좌표는 canvas 기준(canvas가 .map-preview 좌상단이라 그대로 사용). */}
            {hoverCity && (
                <div
                    className="map-preview-tooltip"
                    role="status"
                    style={{
                        left: Math.min(cursor.x + 12, (canvasW || w) - 130),
                        top: cursor.y + 16,
                    }}
                >
                    <div className="map-preview-tooltip-name">{hoverCity.name}</div>
                    <div className="map-preview-tooltip-meta">
                        {`【${CITY_REGIONS[String(hoverCity.id)] ?? ''} ${levelText(hoverCity.level)}】 ${nationNameOf(hoverCity.nationId)}`}
                    </div>
                </div>
            )}
            <div className="map-preview-cap">{`${serverName ?? data.serverName} · ${data.year}年 ${data.month}月`}</div>
        </div>
    );
}
