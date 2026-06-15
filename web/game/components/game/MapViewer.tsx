'use client';

// MapViewer — 인게임 세계 지도(메인화면 §1.1 `.mapView` 중앙 영역).
// 로비/로그인 맵(gateway `MapPreview.tsx`)과 "보이는 모양"을 동일하게 맞춘 정적 마커 맵이다.
//
// W0-6 prop widen(PAGE_PARITY_AUDIT_2026-06-10.md §5) — 레거시 `hwe/ts/components/MapViewer.vue` 패러티:
//   - mapData    : 외부 주입 시 self-fetch 생략(레거시 Vue 뷰어는 mapData가 required prop —
//                  MapViewer.vue:241-244 — 이고 페이지가 주입: PageHistory.vue:23-33 /
//                  PageCachedMap.vue:5-16). 주입 시 기본 클릭 비활성(두 페이지 모두 :disallow-click="true").
//   - disallowClick : 클릭(도시 페이지 이동) 차단 — 레거시 MapViewer.vue:225 prop → 392-394 clickable=0.
//                  명시값이 mapData 기본값을 이긴다(PageFront.vue:47 은 주입+클릭 허용).
//   - currentCityId : 내(현재) 도시 blink 마커 — 레거시 is-my-city(MapViewer.vue:62,76) →
//                  MapCityDetail.vue:34 `.my_city`(map.scss:231-262 outline 점멸).
//   - live/showMe : 10분 캐시 preview 대신 GetMap 패러티 `/api/map`(neutralView:0)을 추가 조회해
//                  소유/상태/연월/내도시를 라이브로 머지 — 레거시 PageFront.vue:516-529
//                  GetMap({neutralView:0, showMe:1}). showMe → myCity 포함(func_map.php:78-95).
//   - refreshKey : 값 변경 시 재조회 — 레거시 refreshCounter watch(PageFront.vue:516).
//
// P1-062: '도시명 표기'·'두번 탭 해 도시 이동' 클라 토글 — 레거시 MapViewer.vue:30-53 +
// state/mapViewer.ts(localStorage 'sam.hideMapCityName'/'sam.toggleSingleTap') + map.scss:37-58.
//
// 비주얼 정본 = gateway `components/MapPreview.tsx` + `app/globals.css`(.map-preview*/.city-*).
// 두 맵뷰어 불변식: MapPreview(web/gateway)와 데이터 소스만 다르고 기능·겉보기 동일 — 여기를 고치면
// MapPreview도 함께 고친다(+ 양쪽 tsc).

import { useEffect, useMemo, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { api } from '@/lib/api';
import { MAP_CDN } from '@/lib/constants';
import type { MapPreviewResponse, WorldMapResponse } from '@/lib/types';
import { tintFlag, FLAG_FRAMES } from '@/lib/flagTint';
import cityRegionsData from '@/config/cityRegions.json';

// 도시 id → 지역명(지리 속성, 소유 무관). 툴팁에 지역 라벨 표시. gateway MapPreview와 동일 자산.
const CITY_REGIONS = cityRegionsData.regions as Record<string, string>;

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

// 치소 등급 라벨 (레거시 defs/index.ts CityLevelText) — lv 4 = 이민족 전용 "이", 한족 군 치소 lv 5 "소". gateway MapPreview와 동일.
const LEVEL_TEXT: Record<number, string> = {
    1: '수', 2: '진', 3: '관', 4: '이', 5: '소', 6: '중', 7: '대', 8: '특',
};
function levelText(level: number): string {
    return LEVEL_TEXT[level] ?? String(level);
}

// CDN 베이스맵 코드 — 시나리오가 맵을 특정 못한 경우(mapCode="scenario") che 베이스로 폴백. gateway MapPreview와 동일.
const CDN_MAPS = new Set(['che', 'chess', 'cr', 'miniche']);
function cdnMapCode(mc: string): string {
    return CDN_MAPS.has(mc) ? mc : 'che';
}

// 계절 경계 — 레거시 MapViewer.vue:306-319 getMapSeasonClassName 패러티(P1-061):
// month<=3 봄 / <=6 여름 / <=9 가을 / 나머지(10~12) 겨울. gateway MapPreview와 동일 공식.
export function seasonOf(month: number): string {
    if (month <= 3) return 'spring';
    if (month <= 6) return 'summer';
    if (month <= 9) return 'fall';
    return 'winter';
}

// 레거시 state/mapViewer.ts 패러티 — 토글 상태는 localStorage('yes'/'no')에 영속.
const LS_HIDE_CITYNAME = 'sam.hideMapCityName';
const LS_SINGLE_TAP = 'sam.toggleSingleTap';

// W0-6 prop surface — 모든 prop 은 optional(기존 호출부 `<MapViewer />` 무수정 컴파일).
// gateway MapPreview 와 동일한 능력 표면을 유지한다(두 맵뷰어 불변식).
export interface MapViewerProps {
    /** 외부 주입 맵 데이터 — 주입 시 self-fetch 생략(레거시 PageHistory.vue:23-33 :map-data 주입). */
    mapData?: MapPreviewResponse | null;
    /** 클릭(도시 페이지 이동) 차단 — 레거시 MapViewer.vue:225/392-394. 기본값: mapData 주입 시 true. */
    disallowClick?: boolean;
    /** 내(현재) 도시 blink — 레거시 is-my-city → .my_city(map.scss:231-262). */
    currentCityId?: number | null;
    /** 라이브 모드 — GetMap 패러티 /api/map(neutralView:0)을 머지(레거시 PageFront.vue:516-529). */
    live?: boolean;
    /** GetMap showMe 인자(func_map.php:78-95 — 1이면 myCity 포함). live=true일 때만 사용. 기본 1. */
    showMe?: 0 | 1;
    /** 재조회 트리거 — 값이 바뀌면 다시 fetch(레거시 refreshCounter watch). */
    refreshKey?: number;
}

// 라이브 머지 — preview(좌표/이름 베이스)에 /api/map 라이브 tuple 을 id 로 덮어쓴다.
// cityList tuple = [city, level, state, nation, region, supply](func_map.php:144-148),
// nationList tuple = [id, name, color, capital]. isCapital 은 레거시 mergeNationInfo
// (MapViewer.vue:367-385 — 소유국 tuple 의 capital == city.id)와 동일 판정.
function mergeLive(
    preview: MapPreviewResponse,
    wm: WorldMapResponse,
): { data: MapPreviewResponse; myCity: number | null } {
    const tupleById = new Map<number, number[]>();
    wm.cityList.forEach((t) => tupleById.set(t[0], t));
    const nationTupleById = new Map<number, (number | string)[]>();
    wm.nationList.forEach((t) => nationTupleById.set(t[0] as number, t));

    const cities = preview.cities.map((c) => {
        const t = tupleById.get(c.id);
        if (!t) return c; // 라이브에 없는 도시는 preview 그대로(좌표 없는 도시는 어차피 미렌더).
        const [, level, state, nationId, , supply] = t;
        return {
            ...c,
            level,
            state,
            nationId,
            supply: supply !== 0,
            isCapital: (nationTupleById.get(nationId)?.[3] ?? -1) === c.id,
        };
    });
    const nations = wm.nationList.map((t) => ({
        id: t[0] as number,
        name: t[1] as string,
        color: t[2] as string,
    }));
    return {
        data: { ...preview, startYear: wm.startYear, year: wm.year, month: wm.month, cities, nations },
        myCity: wm.myCity,
    };
}

export default function MapViewer({
    mapData,
    disallowClick,
    currentCityId,
    live = false,
    showMe = 1,
    refreshKey = 0,
}: MapViewerProps = {}) {
    const router = useRouter();
    const [data, setData] = useState<MapPreviewResponse | null>(null);
    const [failed, setFailed] = useState(false);
    const [hoverId, setHoverId] = useState<number | null>(null);
    const [cursor, setCursor] = useState({ x: 0, y: 0 });
    // 라이브(showMe) 응답의 myCity — currentCityId prop 미지정 시 blink 대상.
    const [liveMyCity, setLiveMyCity] = useState<number | null>(null);

    // 클릭 게이트 — 명시 disallowClick 이 우선, 기본값은 "mapData 주입 시 비활성"(감사 P0-22 시멘틱:
    // 레거시 주입 페이지 PageHistory/PageCachedMap 은 모두 disallow-click=true. PageFront 처럼
    // 주입+클릭을 원하면 disallowClick={false}를 명시한다).
    const clickEnabled = !(disallowClick ?? mapData != null);

    useEffect(() => {
        // 외부 주입 — self-fetch 생략(레거시 Vue 뷰어 동작: 페이지가 fetch, 뷰어는 렌더만).
        if (mapData != null) {
            setData(mapData);
            setFailed(false);
            setLiveMyCity(null);
            return;
        }
        let on = true;
        setData(null);
        setFailed(false);
        const load = async (): Promise<{ data: MapPreviewResponse; myCity: number | null }> => {
            const preview = await api.mapPreview();
            if (!live) return { data: preview, myCity: null };
            try {
                // GetMap({neutralView:0, showMe}) 패러티 — 실패 시 preview 폴백(graceful).
                const wm = await api.worldMap(0, showMe);
                return mergeLive(preview, wm);
            } catch {
                return { data: preview, myCity: null };
            }
        };
        load()
            .then((r) => {
                if (!on) return;
                setData(r.data);
                setLiveMyCity(r.myCity);
            })
            .catch(() => {
                if (on) setFailed(true);
            });
        return () => {
            on = false;
        };
    }, [mapData, live, showMe, refreshKey]);

    // 내(현재) 도시 — 명시 prop > 라이브 myCity(레거시 drawableMap.myCity, MapViewer.vue:62,76).
    const effectiveMyCity = currentCityId ?? liveMyCity;

    // ── P1-062 클라 토글 2종(레거시 MapViewer.vue:30-53 + state/mapViewer.ts) ──
    // SSR 안전: 초기값은 effect 에서 localStorage 로 복원한다.
    const [hideCityName, setHideCityName] = useState(false);
    const [singleTap, setSingleTap] = useState(false);
    const [touchDevice, setTouchDevice] = useState(false);
    useEffect(() => {
        setHideCityName(window.localStorage.getItem(LS_HIDE_CITYNAME) === 'yes');
        setSingleTap(window.localStorage.getItem(LS_SINGLE_TAP) === 'yes');
        // 레거시 deviceType != 'mouseOnly'(detect-it) — 터치 가능 기기에서만 탭 토글 노출.
        setTouchDevice(navigator.maxTouchPoints > 0 || window.matchMedia('(any-pointer: coarse)').matches);
    }, []);
    function toggleHideCityName() {
        setHideCityName((v) => {
            window.localStorage.setItem(LS_HIDE_CITYNAME, v ? 'no' : 'yes');
            return !v;
        });
    }
    function toggleSingleTap() {
        setSingleTap((v) => {
            window.localStorage.setItem(LS_SINGLE_TAP, v ? 'no' : 'yes');
            return !v;
        });
    }

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

    // ── 터치 두번-탭 이동(레거시 MapViewer.vue:439-457 cityClick touchState 머신) ──
    // 마우스: 즉시 이동. 터치 + singleTap off: 첫 탭=선택(툴팁), 같은 도시 둘째 탭=이동.
    const lastPointerType = useRef<string>('mouse');
    const touchArmedId = useRef<number | null>(null);

    // 도시 마커 클릭 = 해당 도시 정보 페이지로 이동(클릭 게이트/터치 상태 머신 통과 시).
    function onCityClick(cityId: number) {
        if (!clickEnabled) return; // 레거시 clickable=0(MapViewer.vue:392-394)
        if (lastPointerType.current === 'touch') {
            if (touchArmedId.current !== null && touchArmedId.current !== cityId) {
                touchArmedId.current = null; // 다른 도시 탭 → 선택 초기화(레거시 441-444)
            }
            if (touchArmedId.current === null) {
                touchArmedId.current = cityId;
                setHoverId(cityId);
                if (!singleTap) return; // 두번-탭 모드: 첫 탭은 선택만(레거시 450-452)
            }
        }
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

    const mapCode = cdnMapCode(data.mapCode || 'che');
    const w = data.width || 700;
    const h = data.height || 500;
    const bg = `${MAP_CDN}/${mapCode}/bg_${seasonOf(data.month || 1)}.jpg`;
    const road = `${MAP_CDN}/${mapCode}/${mapCode}_road.png`;

    return (
        <section className={`map-viewer${hideCityName ? ' hide-cityname' : ''}`} aria-label="세계 지도">
            <div
                ref={canvasRef}
                className="map-viewer-canvas"
                style={{ aspectRatio: `${w} / ${h}` }}
                onMouseMove={(e) => {
                    const r = canvasRef.current?.getBoundingClientRect();
                    if (r) setCursor({ x: e.clientX - r.left, y: e.clientY - r.top });
                }}
                onMouseLeave={() => setHoverId(null)}
                onClick={() => {
                    // 빈 영역 탭 → 터치 선택 해제(레거시 clickOutside, MapViewer.vue:459-466).
                    touchArmedId.current = null;
                }}
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
                        // P0-36 FE측: 레거시 MapCityDetail.vue:44 는 state>0 이면 표시(상한 캡 없음 —
                        // 재해/사건 코드 6~9 도 event<state>.gif 로 렌더). 옛 <=5 캡 제거.
                        const showState = (c.state ?? 0) > 0;
                        const flagFrameUrl = owned ? flagUrls[col]?.[flagFrame] : undefined;
                        const isMyCity = effectiveMyCity != null && effectiveMyCity === c.id;

                        return (
                            <button
                                type="button"
                                key={c.id}
                                className={`city-base${unsupplied ? ' supply-off' : ''}${clickEnabled ? '' : ' city-noclick'}`}
                                style={{ left: baseLeft, top: baseTop, width: BASE_W, height: BASE_H }}
                                aria-label={`${c.name} 레벨 ${c.level} ${nationNameOf(c.nationId)}`}
                                onMouseEnter={() => setHoverId(c.id)}
                                onMouseLeave={() => setHoverId((id) => (id === c.id ? null : id))}
                                onFocus={() => setHoverId(c.id)}
                                onBlur={() => setHoverId((id) => (id === c.id ? null : id))}
                                onPointerDown={(e) => {
                                    // 직전 포인터 종류 기록 — click 핸들러에서 터치/마우스 분기(레거시 cursorType).
                                    lastPointerType.current = e.pointerType || 'mouse';
                                }}
                                onClick={(e) => {
                                    e.stopPropagation(); // 캔버스 clickOutside 와 분리(레거시 silent)
                                    onCityClick(c.id);
                                }}
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

                                {/* 아이콘 컨테이너(city_img) — 깃발/상태/이름이 모두 이 안에서 아이콘 기준 위치(레거시 DOM 구조).
                                    내 도시는 my-city(레거시 .my_city — map.scss:231-262 outline 점멸 링). */}
                                <div
                                    className={`city-img${isMyCity ? ' my-city' : ''}`}
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

                                    {/* 6) 도시명(city_detail_name) — 레거시 {left:70%;bottom:-10px} 아이콘 기준.
                                        hide-cityname(도시명 표기 토글) 시 CSS 로 숨김(레거시 map.scss:149-151). */}
                                    <span className="city-name">{c.name}</span>
                                </div>
                            </button>
                        );
                    })}
                </div>

                {/* 토글 버튼 스택(레거시 map_button_stack — MapViewer.vue:30-53, map.scss:37-58 우하단).
                    라벨 뒤 " 끄기/켜기" 접미는 CSS ::after(레거시 동일). */}
                <div className="map-btn-stack">
                    <button
                        type="button"
                        className={`map-toggle-cityname${hideCityName ? ' active' : ''}`}
                        aria-pressed={hideCityName}
                        onClick={(e) => {
                            e.stopPropagation();
                            toggleHideCityName();
                        }}
                    >
                        도시명 표기
                    </button>
                    {touchDevice && (
                        <button
                            type="button"
                            className={`map-toggle-singletap${singleTap ? ' active' : ''}`}
                            aria-pressed={singleTap}
                            onClick={(e) => {
                                e.stopPropagation();
                                toggleSingleTap();
                            }}
                        >
                            두번 탭 해 도시 이동
                        </button>
                    )}
                </div>
            </div>

            {/* hover 도시정보 툴팁(레거시 .city_tooltip) — 커서 추종. canvas(overflow:hidden) 밖 .map-viewer에 두어
                경계에서 잘리지 않게(gateway MapPreview와 동일 구조·내용). */}
            {hoverCity && (
                <div
                    className="map-tooltip"
                    role="status"
                    style={{
                        left: Math.min(cursor.x + 12, (canvasW || w) - 130),
                        top: cursor.y + 16,
                    }}
                >
                    {/* 레거시 city_tooltip 2줄 구조: 1줄=【지역 | 등급】 도시명(CityBasicCard.vue), 2줄=국가명만(map.ts nation_name) */}
                    <div className="map-tooltip-name">
                        {`【${CITY_REGIONS[String(hoverCity.id)] ?? ''} | ${levelText(hoverCity.level)}】 ${hoverCity.name}`}
                    </div>
                    <div className="map-tooltip-meta">{nationNameOf(hoverCity.nationId)}</div>
                </div>
            )}
            <div className="map-viewer-cap">{`${data.serverName} · ${data.year}年 ${data.month}月`}</div>
        </section>
    );
}
