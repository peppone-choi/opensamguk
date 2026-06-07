'use client';

// MapViewer — 인게임 세계 지도(메인화면 §1.1 `.mapView` 중앙 영역).
// 로비/로그인 맵(gateway `MapPreview.tsx`)과 "보이는 모양"을 동일하게 맞춘 정적 마커 맵이다.
// 단 하나의 인터랙션만 둔다: 도시 마커 클릭 → 해당 도시 정보 페이지로 이동.
//
// 비주얼 정본 = gateway `components/MapPreview.tsx` + `app/globals.css`(.map-preview*/.city-*).
// 마커 DOM/CSS 구조를 MapPreview와 동일하게 맞춘다:
//   city-base(40×30) → 오오라(소유국만) + city-img 컨테이너(성 cast / 상태 / 깃발+수도별 / 도시명).
// 깃발은 city-img 안에서 상대 오프셋(right: flagRight / top: flagTop)으로 배치한다(MapPreview 패턴).
//
// 로비와 다르게 game-main은 줌/팬/안개(fog)/현재도시 blink 등 인게임 chrome을 모두 제거해
// 로비와 픽셀 단위로 같은 정적 모습을 낸다. 데이터 소스는 그대로 game-api(`api.mapPreview()`).
//
// 클릭 인터랙션: 도시 마커는 키보드 접근 가능한 <button> — 클릭 시 `/game/city?id=<id>`로 이동한다.

import { useEffect, useMemo, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { api } from '@/lib/api';
import { MAP_CDN } from '@/lib/constants';
import type { MapPreviewResponse } from '@/lib/types';
import { tintFlag, FLAG_FRAMES } from '@/lib/flagTint';

const NEUTRAL_COLOR = '#555555';
const NEUTRAL_NAME = '공 백 지'; // legacy CityBasicCard nationNamePanel fallback

// city_base 박스 크기 (레거시 $cityBaseWidth/$cityBaseHeight).
const BASE_W = 40;
const BASE_H = 30;

// 레거시 $detailMapCitySizes — (level, areaW, areaH, iconW, iconH, flagRight, flagTop). MapPreview와 동일.
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
// 로그인/로비/메인 3개 맵의 모양을 동일하게 맞추는 단일 노브 — 값은 gateway MapPreview.ICON_SCALE와 일치.
// 아우라(areaW/areaH)·깃발 위치는 레거시 비율 유지, cast 아이콘(iconW/iconH)만 ICON_SCALE로 줄인다.
const ICON_SCALE = 0.72;
// 깃발/수도별 아이콘 픽셀 — 도시 cast 아이콘과 같은 ICON_SCALE로 축소(레거시 기본 12/10 → 사용자 요청 축소).
const FLAG_PX = Math.round(12 * ICON_SCALE); // 12 → 9
const STAR_PX = Math.round(10 * ICON_SCALE); // 10 → 7
// 표에 없는 레벨(예: 0)도 깨지지 않게 lv3 기준으로 폴백.
function sizeOf(level: number): CitySize {
    const base = DETAIL_SIZES[level] ?? DETAIL_SIZES[3];
    return {
        ...base,
        iconW: Math.round(base.iconW * ICON_SCALE),
        iconH: Math.round(base.iconH * ICON_SCALE),
    };
}

// season from month (3-5 spring / 6-8 summer / 9-11 fall / else winter) — gateway MapPreview와 동일.
function seasonOf(month: number): string {
    if (month >= 3 && month <= 5) return 'spring';
    if (month >= 6 && month <= 8) return 'summer';
    if (month >= 9 && month <= 11) return 'fall';
    return 'winter';
}

export default function MapViewer() {
    const router = useRouter();
    const [data, setData] = useState<MapPreviewResponse | null>(null);
    const [failed, setFailed] = useState(false);
    const [hoverId, setHoverId] = useState<number | null>(null);

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
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [ownedColors.join(',')]);
    // 깃발 4프레임 나부낌 — 맵 공용 프레임 카운터(setInterval) 하나로 전 깃발 동기 애니.
    const [flagFrame, setFlagFrame] = useState(0);
    useEffect(() => {
        const t = setInterval(() => setFlagFrame((f) => (f + 1) % FLAG_FRAMES), 180);
        return () => clearInterval(t);
    }, []);

    // 맵 좌표계(native 700×500)를 캔버스 폭에 맞추는 외곽 스케일 — ResizeObserver로 실제 폭 추적(gateway와 동일).
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

    // 도시 마커 클릭 = 해당 도시 정보 페이지로 이동(유일한 인터랙션).
    function goCity(cityId: number) {
        router.push(`/game/city?id=${cityId}`);
    }

    // 미시드 / 실패 / 빈 세계 → placeholder (크래시 없음).
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
            <div ref={canvasRef} className="map-viewer-canvas">
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
                            <button
                                type="button"
                                key={c.id}
                                className={`city-base${unsupplied ? ' supply-off' : ''}`}
                                style={{ left: baseLeft, top: baseTop, width: BASE_W, height: BASE_H }}
                                aria-label={`${c.name} 레벨 ${c.level} ${nationNameOf(c.nationId)}`}
                                onMouseEnter={() => setHoverId(c.id)}
                                onMouseLeave={() => setHoverId((id) => (id === c.id ? null : id))}
                                onFocus={() => setHoverId(c.id)}
                                onBlur={() => setHoverId((id) => (id === c.id ? null : id))}
                                onClick={() => goCity(c.id)}
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

                                    {/* 3) 깃발(소유국만) — 레거시 {right:flagRight;top:flagTop} 아이콘 기준.
                                        깃발/수도별 아이콘도 도시 아이콘과 같은 ICON_SCALE로 축소(사용자 요청 — 도시 줄인 비율만큼). */}
                                    {owned && flagFrameUrl && (
                                        <span className="city-flag" style={{ right: sz.flagRight, top: sz.flagTop }}>
                                            {/* eslint-disable-next-line @next/next/no-img-element */}
                                            <img className="city-flag-img" src={flagFrameUrl} alt="" width={FLAG_PX} height={FLAG_PX} draggable={false} />
                                            {/* 4) 수도 별 event51.gif — 깃발 우상단. */}
                                            {c.isCapital && (
                                                // eslint-disable-next-line @next/next/no-img-element
                                                <img className="city-capital" src="/icons/event51.gif" alt="" width={STAR_PX} height={STAR_PX} draggable={false} />
                                            )}
                                        </span>
                                    )}

                                    {/* 6) 도시명(city_detail_name) — 레거시 {left:70%;bottom:-10px} 아이콘 기준, 항상 표시. */}
                                    <span className="city-name">{c.name}</span>
                                </div>
                            </button>
                        );
                    })}
                </div>

                {/* hover 도시정보 툴팁(레거시 .city_tooltip) — 캔버스 좌상단 고정(MapPreview의 경량 hover 툴팁과 동급). */}
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
        </section>
    );
}
