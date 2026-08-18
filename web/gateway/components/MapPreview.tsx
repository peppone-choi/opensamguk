'use client';

import { useEffect, useMemo, useRef, useState } from 'react';
import { MAP_CDN, ICON_CDN } from '../lib/constants';
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
 *
 * W0-6 능력 표면 미러(두 맵뷰어 불변식 — 데이터 소스만 다르고 기능·겉보기 동일):
 *   - mapData      : 외부 주입 시 self-fetch 생략(인게임 MapViewer 동일 시멘틱, 레거시 PageHistory.vue:23-33).
 *   - disallowClick: 타입만 미러 — 로비 프리뷰엔 클릭 자체가 없어 no-op(인게임은 도시 페이지 라우팅 차단).
 *   - currentCityId: 내(현재) 도시 blink — 레거시 .my_city(map.scss:231-262). 로비는 보통 미전달(데이터 차이).
 *   - live/showMe  : 타입만 미러 — 게이트웨이 프록시는 캐시 preview 만 노출(라이브 /api/map 은 인게임 전용).
 *   - refreshKey   : 값 변경 시 재조회(레거시 refreshCounter watch — PageFront.vue:516).
 *   - 계절 경계(P1-061)·state 캡 해제(P0-36 FE측)·도시명 표기 토글(P1-062)은 인게임 MapViewer 와 동일 적용.
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
// 인게임 MapViewer와 동일하게 cast 아이콘(iconW/iconH)만 ICON_SCALE로 줄인다(아우라/깃발 비율 유지).
// 로그인/로비/메인 3개 맵의 모양을 동일하게 맞추는 단일 노브 — 값은 MapViewer.ICON_SCALE와 일치시킬 것.
const ICON_SCALE = 0.72;
// 깃발/수도별 아이콘도 도시 아이콘과 같은 ICON_SCALE로 축소(인게임 MapViewer와 일치 — 사용자 요청).
const FLAG_PX = Math.round(12 * ICON_SCALE); // 12 → 9
const STAR_PX = Math.round(10 * ICON_SCALE); // 10 → 7
const STATE_ICON_SCALE = 0.54;
const STATE_PX = Math.round(15 * STATE_ICON_SCALE);
function sizeOf(level: number): CitySize {
    const base = DETAIL_SIZES[level] ?? DETAIL_SIZES[3];
    return {
        ...base,
        iconW: Math.round(base.iconW * ICON_SCALE),
        iconH: Math.round(base.iconH * ICON_SCALE),
    };
}

// 치소 등급 라벨 (레거시 defs/index.ts CityLevelText) — lv 4 = 이민족 전용 "이", 한족 군 치소 lv 5 "소".
const LEVEL_TEXT: Record<number, string> = {
    1: '수', 2: '진', 3: '관', 4: '이', 5: '소', 6: '중', 7: '대', 8: '특',
};
function levelText(level: number): string {
    return LEVEL_TEXT[level] ?? String(level);
}

// CDN 베이스맵 코드 — 시나리오가 맵을 특정 못한 경우(mapCode="scenario") che 베이스로 폴백.
// 2026-08-17: pokemon_v1 은 옵션 IP 퍼지로 제거했다 — 이미지 레포의 game/map/pokemon_v1/ 타일이
// 이미 404 이고 소비 시나리오도 0이다. 목록에서 빠지면 cdnMapCode() 가 che 로 폴백한다.
const CDN_MAPS = new Set(['che', 'chess', 'cr', 'ludo_rathowm', 'miniche', 'miniche_b', 'miniche_clean']);
const MINICHE_MAPS = new Set(['miniche', 'miniche_b', 'miniche_clean']);
function cdnMapCode(mc: string): string {
    if (MINICHE_MAPS.has(mc)) return 'che';
    return CDN_MAPS.has(mc) ? mc : 'che';
}
function cdnRoadName(mc: string): string {
    return MINICHE_MAPS.has(mc) ? 'miniche_road.png' : `${cdnMapCode(mc)}_road.png`;
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
export interface MapData {
    serverName: string;
    /** 시나리오 시작 연도 — W0-2b(MapPreviewDto.startYear, optional). 초반 3년 색상/기술등급 툴팁(P1-060) 소비 예정. */
    startYear?: number;
    year: number;
    month: number;
    turnPhase?: number | null;
    turnPhaseText?: string | null;
    mapCode: string;
    width: number;
    height: number;
    cities: MapCity[];
    nations: MapNation[];
}

// 계절 경계 — 레거시 MapViewer.vue:306-319 getMapSeasonClassName 패러티(P1-061):
// month<=3 봄 / <=6 여름 / <=9 가을 / 나머지(10~12) 겨울. 인게임 MapViewer.seasonOf 와 동일 공식.
export function seasonOf(month: number): string {
    if (month <= 3) return 'spring';
    if (month <= 6) return 'summer';
    if (month <= 9) return 'fall';
    return 'winter';
}

// 레거시 state/mapViewer.ts 패러티 — 토글 상태는 localStorage('yes'/'no')에 영속(인게임과 동일 키).
const LS_HIDE_CITYNAME = 'sam.hideMapCityName';

// W0-6 능력 표면 — 인게임 MapViewer(MapViewerProps)와 동일한 prop 세트(두 맵뷰어 불변식).
// 로비엔 클릭/라이브 데이터가 없어 disallowClick/live/showMe 는 타입만 유지된다(상단 주석 참조).
export interface MapPreviewProps {
    serverId?: string;
    serverName?: string;
    /** 외부 주입 맵 데이터 — 주입 시 self-fetch 생략(레거시 PageHistory.vue:23-33 :map-data 주입). */
    mapData?: MapData | null;
    /** 타입 미러 — 로비 프리뷰는 클릭이 원래 없음(인게임 MapViewer 에서만 동작). */
    disallowClick?: boolean;
    /** 내(현재) 도시 blink — 레거시 is-my-city → .my_city(map.scss:231-262). */
    currentCityId?: number | null;
    /** 타입 미러 — 게이트웨이 프록시는 캐시 preview 만 노출(라이브 머지는 인게임 MapViewer 전용). */
    live?: boolean;
    /** 타입 미러 — live 미배선이므로 로비에선 효과 없음(func_map.php:78-95 showMe 시멘틱). */
    showMe?: 0 | 1;
    /** 재조회 트리거 — 값이 바뀌면 다시 fetch(레거시 refreshCounter watch). */
    refreshKey?: number;
}

export default function MapPreview({
    serverId = 'main',
    serverName,
    mapData,
    // disallowClick/live/showMe — 능력 표면 미러용(로비에선 미사용, 인게임 MapViewer 시그니처와 동일 유지).
    disallowClick: _disallowClick,
    currentCityId,
    live: _live,
    showMe: _showMe,
    refreshKey = 0,
}: MapPreviewProps = {}) {
    const [data, setData] = useState<MapData | null>(null);
    const [failed, setFailed] = useState(false);
    const [hoverId, setHoverId] = useState<number | null>(null);
    const [cursor, setCursor] = useState({ x: 0, y: 0 });

    useEffect(() => {
        // 외부 주입 — self-fetch 생략(인게임 MapViewer 와 동일 시멘틱).
        if (mapData != null) {
            setData(mapData);
            setFailed(false);
            return;
        }
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
    }, [serverId, mapData, refreshKey]);

    // ── P1-062 도시명 표기 토글(레거시 MapViewer.vue:30-40 + state/mapViewer.ts) ──
    // SSR 안전: 초기값은 effect 에서 localStorage 로 복원. 두번-탭 토글은 클릭이 없는 로비엔 미노출.
    const [hideCityName, setHideCityName] = useState(false);
    useEffect(() => {
        setHideCityName(window.localStorage.getItem(LS_HIDE_CITYNAME) === 'yes');
    }, []);
    function toggleHideCityName() {
        setHideCityName((v) => {
            window.localStorage.setItem(LS_HIDE_CITYNAME, v ? 'no' : 'yes');
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
    const roadName = cdnRoadName(data.mapCode || 'che');
    const w = data.width || 700;
    const h = data.height || 500;
    const bg = `${MAP_CDN}/${mapCode}/bg_${seasonOf(data.month || 1)}.jpg`;
    const road = `${MAP_CDN}/${mapCode}/${roadName}`;

    return (
        <div className={`map-preview${hideCityName ? ' hide-cityname' : ''}`} aria-label="서버 지도 프리뷰">
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
                        // P0-36 FE측: 레거시 MapCityDetail.vue:44 는 state>0 이면 표시(상한 캡 없음 —
                        // 재해/사건 코드 6~9 도 event<state>.gif 로 렌더). 옛 <=5 캡 제거(인게임과 동일).
                        const showState = (c.state ?? 0) > 0;
                        const flagFrameUrl = owned ? flagUrls[col]?.[flagFrame] : undefined;
                        const isMyCity = currentCityId != null && currentCityId === c.id;

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
                                    <div className={`city-filler${isMyCity ? ' my-city' : ''}`} />
                                    {/* 2) 성 아이콘 — city_img를 채움(픽셀아트). 자작 에셋 `public/city/cast_<level>.png`
                                        (tools/assets/build_city_icons.py). CDN `game/cast_*.gif`는 devsam/image 파생이라
                                        권리 UNKNOWN이었다 — 깃발과 같은 이유로 교체했다. 두 앱 public/ 에 같은 파일을 두므로
                                        공유 도메인에서 절대경로 `/city/...`가 어느 앱으로 라우팅돼도 해석된다. */}
                                    {/* eslint-disable-next-line @next/next/no-img-element */}
                                    <img className="city-cast" src={`/city/cast_${c.level}.png`} alt="" draggable={false} />

                                    {/* 5) 상태 아이콘 event<state>.gif — 레거시 {top:5;left:0} 아이콘 기준. */}
                                    {showState && (
                                        // eslint-disable-next-line @next/next/no-img-element
                                        <img
                                            className="city-state"
                                            src={`${ICON_CDN}/event${c.state}.gif`}
                                            alt=""
                                            width={STATE_PX}
                                            height={STATE_PX}
                                            draggable={false}
                                        />
                                    )}

                                    {/* 3) 깃발(소유국만) — 레거시 {right:flagRight;top:flagTop} 아이콘 기준. */}
                                    {owned && flagFrameUrl && (
                                        <span className="city-flag" style={{ right: sz.flagRight, top: sz.flagTop }}>
                                            {/* eslint-disable-next-line @next/next/no-img-element */}
                                            <img className="city-flag-img" src={flagFrameUrl} alt="" width={FLAG_PX} height={FLAG_PX} draggable={false} />
                                            {/* 4) 수도 별 event51.gif — 깃발 우상단. */}
                                            {c.isCapital && (
                                                // eslint-disable-next-line @next/next/no-img-element
                                                <img className="city-capital" src={`${ICON_CDN}/event51.gif`} alt="" width={STAR_PX} height={STAR_PX} draggable={false} />
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

                {/* 토글 버튼 스택(레거시 map_button_stack — MapViewer.vue:30-40, map.scss:37-58 우하단).
                    라벨 뒤 " 끄기/켜기" 접미는 CSS ::after(레거시 동일). 두번-탭 토글은 클릭 없는 로비엔 미노출. */}
                <div className="map-btn-stack">
                    <button
                        type="button"
                        className={`map-toggle-cityname${hideCityName ? ' active' : ''}`}
                        aria-pressed={hideCityName}
                        onClick={toggleHideCityName}
                    >
                        도시명 표기
                    </button>
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
                    {/* 레거시 city_tooltip 2줄 구조: 1줄=【지역 | 등급】 도시명(CityBasicCard.vue), 2줄=국가명만(map.ts nation_name) */}
                    <div className="map-preview-tooltip-name">
                        {`【${CITY_REGIONS[String(hoverCity.id)] ?? ''} | ${levelText(hoverCity.level)}】 ${hoverCity.name}`}
                    </div>
                    {hoverCity.nationId !== 0 && (
                        <div className="map-preview-tooltip-meta">{nationNameOf(hoverCity.nationId)}</div>
                    )}
                </div>
            )}
            <div className="map-preview-cap">
                {`${serverName ?? data.serverName} · ${data.year}년 ${data.month}월${data.turnPhaseText ? ` ${data.turnPhaseText}` : ''}`}
            </div>
        </div>
    );
}
