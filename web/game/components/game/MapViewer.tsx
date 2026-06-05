'use client';

// MapViewer — the interactive in-game world map (spec §1.1 `.mapView` center region; user chose the
// INTERACTIVE build over a placeholder). Grand-truth: legacy `MapViewer.vue` + `MapCityBasic.vue` +
// `map.ts drawDetailWorldMap()` + `scss/map.scss .map_detail`. This is the DETAIL layout port: each city
// is a `.city_base` 40×30 box centered on its (x,y), and inside it the legacy layers stack — 오오라(aura)
// → 성 아이콘(cast) → 깃발(flag) → 수도 별(capital) → 상태 아이콘(state) → 도시명(name). 좌표·크기는 모두
// SVG가 아닌 HTML 절대배치 px(레거시와 동일)로, 줌/팬을 위해 transform 한 겹으로 감싼다.
//
// Data flow: api.mapPreview() (GET /api/map/preview through the same-origin /api/game proxy — NO bare
// cross-origin fetch) → {serverName,year,month,mapCode,width,height,cities,nations}. The current city +
// nation come from useFrontInfo()'s frontInfo (passed in as props, no extra fetch).
//
// 줌/팬(사용자 명시 "원크기 + 줄/팬"): 94개 도시를 원크기 아이콘으로 깔면 700×500에 겹친다 → 휠=커서기준
// 줌(0.5x~4x), 드래그=팬, 더블클릭=전체 fit 리셋. bg/road + 마커가 같은 transform 레이어 안이라 함께 줌된다.
//
// Interaction model: hover a city → tooltip (도시명 / 【레벨】 / 소속국); click → selects it (현재도시는
// my_city blink, 선택은 ring) and opens MapCityDetail. Command-issuance-from-map is W5 (clean
// onSelectCity seam). Graceful: loading→spinner, unseeded/empty→placeholder, never crash.

import { useEffect, useMemo, useRef, useState } from 'react';
import { api } from '@/lib/api';
import { MAP_CDN } from '@/lib/constants';
import type { MapPreviewCity, MapPreviewResponse } from '@/lib/types';
import { tintFlag, FLAG_FRAMES } from '@/lib/flagTint';
import MapCityDetail from './MapCityDetail';

const NEUTRAL_COLOR = '#555555';
const NEUTRAL_NAME = '공 백 지'; // legacy CityBasicCard nationNamePanel fallback

// city_base 박스 크기 (레거시 $cityBaseWidth/$cityBaseHeight).
const BASE_W = 40;
const BASE_H = 30;

// 레거시 $detailMapCitySizes — (level, areaW, areaH, iconW, iconH, flagRight, flagTop). 정확히 재현.
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
// 표에 없는 레벨(예: 0)도 깨지지 않게 lv3 기준으로 폴백.
function sizeOf(level: number): CitySize {
    return DETAIL_SIZES[level] ?? DETAIL_SIZES[3];
}

// 줌 한계 + 휠 감도.
const MIN_K = 0.5;
const MAX_K = 4;

// season from month (3-5 spring / 6-8 summer / 9-11 fall / else winter) — identical to gateway MapPreview.
function seasonOf(month: number): string {
    if (month >= 3 && month <= 5) return 'spring';
    if (month >= 6 && month <= 8) return 'summer';
    if (month >= 9 && month <= 11) return 'fall';
    return 'winter';
}

export interface MapViewerProps {
    /** Current general's city id — drawn with the legacy `my_city` blink (and detail-panel marker). */
    currentCityId?: number | null;
    /** Selection seam for W5 (command-from-map). Fires on every city click after select. */
    onSelectCity?: (city: MapPreviewCity) => void;
    /**
     * W9 fog overlay — 인게임 GET /api/map의 fog 데이터(없으면 fog 미표시).
     *  - `shownByGeneralCityIds`: 아국 장수 소재 도시(테두리 강조).
     *  - `spyCityRemain`: {cityNo: 정찰 잔여개월}(정찰 배지).
     */
    shownByGeneralCityIds?: ReadonlySet<number>;
    spyCityRemain?: Record<number, number>;
}

export default function MapViewer({
    currentCityId,
    onSelectCity,
    shownByGeneralCityIds,
    spyCityRemain,
}: MapViewerProps) {
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

    // ── 줌/팬 상태: viewBox(700×500) 좌표계 위에 translate(tx,ty) scale(k). 초기=전체 fit(1배, 원점). ──
    const [view, setView] = useState({ tx: 0, ty: 0, k: 1 });
    const canvasRef = useRef<HTMLDivElement | null>(null);
    // 캔버스 실제 픽셀폭 — .map-world의 외곽 스케일(clientWidth/700)을 위해 ResizeObserver로 추적.
    const [canvasW, setCanvasW] = useState(0);
    useEffect(() => {
        const el = canvasRef.current;
        if (!el) return;
        const update = () => setCanvasW(el.clientWidth);
        update();
        const ro = new ResizeObserver(update);
        ro.observe(el);
        return () => ro.disconnect();
        // data 준비 후 canvasRef가 붙으므로 data를 dep에 둠.
    }, [data]);
    // 드래그(팬) 추적 — 마우스다운 지점과 그 순간의 tx/ty를 기억.
    const dragRef = useRef<{ sx: number; sy: number; tx: number; ty: number } | null>(null);
    const [dragging, setDragging] = useState(false);

    // 화면 px → 맵 좌표계 변환 계수(캔버스 실제폭 ÷ 맵 너비 data.width). 휠 커서기준 줌에 쓰인다.
    function mapScale(): number {
        const wRef = data?.width || 1000;
        const cw = canvasRef.current?.clientWidth || canvasW;
        if (!cw) return 1;
        return cw / wRef;
    }

    // 휠 = 커서 위치를 고정점으로 줌. 커서 아래 맵좌표가 줌 전후 같은 화면 위치를 유지하도록 tx/ty 보정.
    function onWheel(e: React.WheelEvent<HTMLDivElement>) {
        e.preventDefault();
        const el = canvasRef.current;
        if (!el) return;
        const rect = el.getBoundingClientRect();
        const ms = mapScale() || 1;
        // 커서의 맵좌표계 위치(px in 700×500 space).
        const cx = (e.clientX - rect.left) / ms;
        const cy = (e.clientY - rect.top) / ms;
        setView((v) => {
            const factor = e.deltaY < 0 ? 1.15 : 1 / 1.15;
            const nk = Math.max(MIN_K, Math.min(MAX_K, v.k * factor));
            if (nk === v.k) return v;
            // 고정점 식: screen = (map*k + t) → t' = cursor_map - (cursor_map - t)/k * nk 의 단순화.
            const ntx = cx - ((cx - v.tx) / v.k) * nk;
            const nty = cy - ((cy - v.ty) / v.k) * nk;
            return { tx: ntx, ty: nty, k: nk };
        });
    }

    function onPointerDown(e: React.PointerEvent<HTMLDivElement>) {
        // 좌클릭(혹은 터치)만 팬. 도시 클릭은 자식에서 stopPropagation 안 하므로 드래그 임계로 구분.
        if (e.button !== 0) return;
        const el = canvasRef.current;
        if (!el) return;
        const ms = mapScale() || 1;
        dragRef.current = {
            sx: e.clientX / ms,
            sy: e.clientY / ms,
            tx: view.tx,
            ty: view.ty,
        };
    }
    function onPointerMove(e: React.PointerEvent<HTMLDivElement>) {
        const d = dragRef.current;
        if (!d) return;
        const ms = mapScale() || 1;
        const dx = e.clientX / ms - d.sx;
        const dy = e.clientY / ms - d.sy;
        // 임계 초과 시 드래그(팬) 시작 — 그 전 작은 이동은 클릭으로 허용.
        if (!dragging && Math.abs(dx) + Math.abs(dy) < 3) return;
        if (!dragging) {
            setDragging(true);
            el_setCapture(e);
        }
        setView((v) => ({ ...v, tx: d.tx + dx, ty: d.ty + dy }));
    }
    function el_setCapture(e: React.PointerEvent<HTMLDivElement>) {
        try {
            (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
        } catch {
            /* setPointerCapture 미지원 환경 무시 */
        }
    }
    function onPointerUp() {
        dragRef.current = null;
        // 다음 tick에 dragging 해제 — 클릭 핸들러가 드래그 직후 select되는 걸 막기 위해.
        if (dragging) setTimeout(() => setDragging(false), 0);
    }
    // 더블클릭 = 전체 fit 리셋.
    function onDoubleClick() {
        setView({ tx: 0, ty: 0, k: 1 });
    }

    const selectedCity = useMemo(
        () => data?.cities.find((c) => c.id === selectedId) ?? null,
        [data, selectedId],
    );
    const hoverCity = useMemo(
        () => data?.cities.find((c) => c.id === hoverId) ?? null,
        [data, hoverId],
    );

    function selectCity(city: MapPreviewCity) {
        // 드래그 종료 직후의 클릭은 select로 처리하지 않음(팬과 충돌 방지).
        if (dragging) return;
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
    const w = data.width || 1000;
    const h = data.height || 714;
    const bg = `${MAP_CDN}/${mapCode}/bg_${seasonOf(data.month || 1)}.jpg`;
    const road = `${MAP_CDN}/${mapCode}/${mapCode}_road.png`;

    return (
        <section className="map-viewer" aria-label="세계 지도">
            <div
                ref={canvasRef}
                className={`map-viewer-canvas${dragging ? ' is-dragging' : ''}`}
                role="application"
                aria-label={`${data.serverName} ${data.year}年 ${data.month}月 세계 지도 (휠 줌, 드래그 이동, 더블클릭 초기화)`}
                onWheel={onWheel}
                onPointerDown={onPointerDown}
                onPointerMove={onPointerMove}
                onPointerUp={onPointerUp}
                onPointerLeave={onPointerUp}
                onDoubleClick={onDoubleClick}
            >
                {/* 맵 좌표계(700×500)를 캔버스 폭에 맞춘 뒤 줌/팬 transform을 한 겹 더. 비율 고정용 wrapper. */}
                <div
                    className="map-world"
                    style={{
                        width: w,
                        height: h,
                        transform: `scale(${(canvasW || w) / w})`,
                    }}
                >
                    <div
                        className="map-zoomlayer"
                        style={{
                            transform: `translate(${view.tx}px, ${view.ty}px) scale(${view.k})`,
                        }}
                    >
                        {/* bg + road — 마커와 함께 줌. 픽셀 끌림 방지 위해 draggable=false. */}
                        {/* eslint-disable-next-line @next/next/no-img-element */}
                        <img className="map-bg" src={bg} alt="" width={w} height={h} draggable={false} />
                        {/* eslint-disable-next-line @next/next/no-img-element */}
                        <img className="map-road" src={road} alt="" width={w} height={h} draggable={false} />

                        {data.cities.map((c) => {
                            const sz = sizeOf(c.level);
                            const owned = c.nationId !== 0;
                            // 미보급(supply=false): devsam은 보급/미보급에 d<color>.gif 별도 에셋을 swap하나
                            // (legacy map.ts:409 `flagType = supply ? 'f' : 'd'`), opensamguk은 깃발을 cloth
                            // PNG 런타임 틴트로 생성해 d-에셋이 없다. 두 번째 오라클 devsam-core2026의
                            // `.supply-off{opacity:0.6}`(MapCityDetail.vue:218)을 그대로 city-base에 적용해
                            // 오오라·성·깃발·이름을 함께 흐리게 한다. devsam은 소유국(nationID>0)만 f/d를 swap하므로
                            // owned 조건도 함께 건다.
                            const unsupplied = owned && c.supply === false;
                            const col = colorOf(c.nationId);
                            const isCurrent = currentCityId != null && c.id === currentCityId;
                            const isSelected = c.id === selectedId;
                            // W9 fog: 아국 장수 소재 도시 강조 + 정찰 잔여개월 배지.
                            const hasOwnGeneral = shownByGeneralCityIds?.has(c.id) ?? false;
                            const spyRemain = spyCityRemain?.[c.id];
                            // city_base 좌상단 = (x-20, y-15) — 중심이 (x,y).
                            const baseLeft = c.x - BASE_W / 2;
                            const baseTop = c.y - BASE_H / 2;
                            // 성 아이콘(city_img) 좌상단 = base 중앙정렬.
                            const imgLeft = (BASE_W - sz.iconW) / 2;
                            const imgTop = (BASE_H - sz.iconH) / 2;
                            // 오오라(city_bg) 좌상단 = base 중앙정렬.
                            const auraLeft = (BASE_W - sz.areaW) / 2;
                            const auraTop = (BASE_H - sz.areaH) / 2;
                            // 상태 아이콘 표시 조건 — event<state>.gif는 1~5만 존재(범위 밖 생략).
                            const showState = c.state > 0 && c.state <= 5;
                            const flagFrameUrl = owned ? flagUrls[col]?.[flagFrame] : undefined;

                            return (
                                <div
                                    key={c.id}
                                    className={`city-base${unsupplied ? ' supply-off' : ''}${hasOwnGeneral ? ' has-own-general' : ''}`}
                                    style={{ left: baseLeft, top: baseTop, width: BASE_W, height: BASE_H }}
                                >
                                    {/* 1) 오오라 — 레거시 b<color>.png(125×125 radial glow)을 CSS radial-gradient로 대체.
                                        소유국=nation색 글로우, 공백지=옅은 회색(약하게). (M-C "오오라 이미지→CSS") */}
                                    <div
                                        className="city-aura"
                                        style={{
                                            left: auraLeft,
                                            top: auraTop,
                                            width: sz.areaW,
                                            height: sz.areaH,
                                            background: owned
                                                ? `radial-gradient(ellipse at center, ${col}66 0%, ${col}22 45%, transparent 72%)`
                                                : 'radial-gradient(ellipse at center, rgba(170,170,170,0.20) 0%, transparent 68%)',
                                        }}
                                    />

                                    {/* 클릭/포커스 타깃 + 깃발/별/상태/이름을 묶는 링크 박스(레거시 .city_link). */}
                                    <button
                                        type="button"
                                        className={`city-link${isSelected ? ' is-selected' : ''}`}
                                        aria-label={`${c.name} 레벨 ${c.level} ${nationNameOf(c.nationId)}`}
                                        onMouseEnter={() => setHoverId(c.id)}
                                        onMouseLeave={() => setHoverId((id) => (id === c.id ? null : id))}
                                        onFocus={() => setHoverId(c.id)}
                                        onBlur={() => setHoverId((id) => (id === c.id ? null : id))}
                                        onClick={() => selectCity(c)}
                                    >
                                        {/* 2) 성 아이콘 cast_<level>.gif — 픽셀아트, 중앙정렬. */}
                                        {/* eslint-disable-next-line @next/next/no-img-element */}
                                        <img
                                            className={`city-cast${isCurrent ? ' my-city' : ''}`}
                                            src={`/icons/cast_${c.level}.gif`}
                                            alt=""
                                            style={{ left: imgLeft, top: imgTop, width: sz.iconW, height: sz.iconH }}
                                            draggable={false}
                                        />

                                        {/* 3) 깃발(소유국만) — flagTint nation색 4프레임, 12×12, 성 아이콘 우상단 기준.
                                            미보급(supply=false)은 d-에셋 대신 city-base의 supply-off 클래스로 흐리게 처리(상단 주석 참고). */}
                                        {owned && flagFrameUrl && (
                                            <span
                                                className="city-flag"
                                                style={{
                                                    left: imgLeft + sz.iconW + sz.flagRight - 12,
                                                    top: imgTop + sz.flagTop,
                                                }}
                                            >
                                                {/* eslint-disable-next-line @next/next/no-img-element */}
                                                <img
                                                    className="city-flag-img"
                                                    src={flagFrameUrl}
                                                    alt=""
                                                    width={12}
                                                    height={12}
                                                    draggable={false}
                                                />
                                                {/* 4) 수도 별 event51.gif — 깃발 우상단(top:0,right:-1). */}
                                                {c.isCapital && (
                                                    // eslint-disable-next-line @next/next/no-img-element
                                                    <img
                                                        className="city-capital"
                                                        src="/icons/event51.gif"
                                                        alt=""
                                                        width={10}
                                                        height={10}
                                                        draggable={false}
                                                    />
                                                )}
                                            </span>
                                        )}

                                        {/* 5) 상태 아이콘 event<state>.gif — base 좌상단(top:5,left:0). */}
                                        {showState && (
                                            // eslint-disable-next-line @next/next/no-img-element
                                            <img
                                                className="city-state"
                                                src={`/icons/event${c.state}.gif`}
                                                alt=""
                                                draggable={false}
                                            />
                                        )}

                                        {/* W9) 정찰 배지 — spyList의 잔여개월(예: "정3"). base 우하단. */}
                                        {spyRemain != null && spyRemain > 0 && (
                                            <span className="city-spy" title={`정찰 잔여 ${spyRemain}개월`}>
                                                {`정${spyRemain}`}
                                            </span>
                                        )}

                                        {/* 6) 도시명 — 레거시 .city_detail_name(left:70%, bottom:-10px, 10px, 반투명 검정 bg).
                                            줌아웃 시 가독성 위해 lv≥5 또는 줌≥1.2x일 때만 표시. */}
                                        {(c.level >= 5 || view.k >= 1.2) && (
                                            <span className="city-name">{c.name}</span>
                                        )}
                                    </button>
                                </div>
                            );
                        })}
                    </div>
                </div>

                {/* HTML hover tooltip (legacy `.city_tooltip`) — fixed top-left of the canvas, no cursor math */}
                {hoverCity && (
                    <div className="map-tooltip" role="status">
                        <div className="map-tooltip-name">{hoverCity.name}</div>
                        <div className="map-tooltip-meta">
                            {`【${hoverCity.level}】 ${nationNameOf(hoverCity.nationId)}`}
                        </div>
                    </div>
                )}

                {/* 줌 안내 + 리셋 버튼(접근성: 키보드로도 초기화 가능). */}
                <div className="map-controls">
                    <span className="map-zoom-level">{Math.round(view.k * 100)}%</span>
                    <button type="button" className="map-reset-btn" aria-label="지도 초기화" onClick={onDoubleClick}>
                        전체 보기
                    </button>
                </div>
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
