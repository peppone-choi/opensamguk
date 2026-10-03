'use client';

// 작전실 천하 형세의 새 지도(탑다운). 제품 화면 교체 스위치(NEXT_PUBLIC_TOPDOWN_SCREENS)와 서버 bakeId가
// 둘 다 있을 때만 WarRoomMap이 이것을 그린다. 세력색은 preview의 구역 점유, 초점 · 내 위치는 bake 장소 표의 城 칸.
// 지도 위 조작은 보드 V31WarRoom · V31MWarRoom 자리다: 위 오른쪽 「지도 레이어」 · 「범례」, 왼쪽 아래 보기 단추(주 · 군 · 현 · + · − · 내 위치로).
// 아직 옮기지 않은 것: 안개(郡 단위 시야 → 구역 대응), 부대 겹층(K2-08 서버 칸 · 경로 대기).
import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import { useViewportClass } from '@opensamguk/ui';
import {
    DEFAULT_LAYERS,
    DEFAULT_ZOOM,
    LegendSwatch,
    MapLayerButtons,
    MapViewBar,
    MyLocationLayer,
    TopdownMap,
    cityCell,
    loadBakePlaces,
    worldFromPreview,
    type Camera,
    type CorpsMarker,
    type HitResult,
    type MapLayerPanel,
    type MapLayers,
    type MyLocation,
    type PendingLayer,
    type PlacesData,
    type TopdownMapHandle,
    type TopdownSource,
    type ViewLevel,
} from '@opensamguk/ui/map/topdown';
import type { MapPreviewResponse } from '@/lib/types';
import type { WarRoomMapView } from '@/lib/war-room-map-view';

/** 보드 P-W03 레이어 중 서버 칸이 아직 없는 것 — 숨기지 않고 「서버 대기」로 보인다. */
const PENDING_LAYERS: readonly PendingLayer[] = [
    { id: 'supply', label: '보급선', contract: 'K2-09' },
    { id: 'fog', label: '시야', contract: 'K2-08' },
    { id: 'water', label: '수역', contract: 'K2-05' },
];
const CONTROL_LAYER = 'var(--z-map-ctrl, 20)';
// 레이어 · 범례 판은 펼치면 다른 조작 위에 선다 — 모바일 좁은 열에서 왼쪽 아래 보기 단추가 열린 판의 줄을 가렸다(10-01 캡처)
const PANEL_LAYER = 'calc(var(--z-map-ctrl, 20) + 1)';
// 내 위치 표지는 지도 이름표 · 城 · 깃발 위, 지도 조작 단추 밑
const MY_LOCATION_LAYER = 'calc(var(--z-map-ctrl, 20) - 1)';

export interface WarRoomLegendEntry {
    readonly nationId: number;
    readonly name: string;
    readonly color: string;
}

/** 郡 보기(4 px/칸 이상 8 미만)에서 초점 郡을 비춘다. */
const FOCUS_ZOOM = 6;

export interface WarRoomTopdownMapProps {
    readonly source: TopdownSource;
    readonly preview: MapPreviewResponse;
    readonly homeCityId: number | null;
    readonly focusCityId: number | null;
    readonly ariaLabel: string;
    /** 범례 판의 세력 색(지도 아래 줄과 같은 자료). */
    readonly legend?: readonly WarRoomLegendEntry[];
    /** 지도 handle(城으로 이동 + 선택 `focusCity` 등). 화면 틀이 목록 · 검색에서 부른다. 사라지면 null. */
    readonly onMapHandle?: (handle: TopdownMapHandle | null) => void;
    /** 레이어 · 범례 판을 화면 틀이 쥘 때(작전실 하단 시트와 「나중에 연 것이 이전 것을 닫는다」, K4). 안 넘기면 스스로 연다. */
    readonly layerPanel?: MapLayerPanel | null;
    readonly onLayerPanelChange?: (open: MapLayerPanel | null) => void;
    /** 보이는 군단 표지 · 남은 행군 경로(옛 지도와 같은 시야 거르기를 거친 것, `toTopdownCorps`). 「부대 경로」 층이 경로를 켜고 끈다. */
    readonly corps?: readonly CorpsMarker[];
    /** 내 장수(내 위치 표지 초상 · 링). 세력이 없으면(재야) nationColor null — 색을 짓지 않는다. 없으면 표지를 그리지 않는다. */
    readonly myGeneral?: WarRoomMyGeneral;
    /** 화면 틀이 지도를 덮은 폭(지난 순 서랍 · 모바일 하단 시트). 내 위치가 그 밑이면 화면 밖처럼 가장자리 화살표를 띄운다. */
    readonly myLocationInset?: { readonly left?: number; readonly bottom?: number };
    /** 주소로 연 보기(`?view=…&focus=…`). 처음 한 번만 맞춘다. 모르는 城이면 기본 초점, 수준이 없으면 기본(郡) 보기. */
    readonly initialView?: WarRoomMapView;
    /** 부모 상자 높이를 채운다(작전실 재배치 P-W01, K4). 아니면 높이 560 — 다른 화면 그대로. */
    readonly fill?: boolean;
}

export interface WarRoomMyGeneral {
    readonly name: string;
    readonly nationColor: string | null;
    readonly picture?: string | null;
    readonly imageServer?: number | null;
}

export default function WarRoomTopdownMap({ source, preview, homeCityId, focusCityId, ariaLabel, legend = [], onMapHandle,
    layerPanel, onLayerPanelChange, corps, myGeneral, myLocationInset, initialView, fill = false }: WarRoomTopdownMapProps) {
    const [camera, setCamera] = useState<Camera | null>(null);
    const [places, setPlaces] = useState<PlacesData | null>(null);
    const [placesError, setPlacesError] = useState<string | null>(null);
    const [picked, setPicked] = useState<HitResult | null>(null);
    const handle = useRef<TopdownMapHandle | null>(null);
    // 보기 단추는 handle 이 생긴 뒤 다시 그려야 눌린다(ref 만으로는 다시 그리지 않는다)
    const [mapHandle, setMapHandle] = useState<TopdownMapHandle | null>(null);
    useEffect(() => () => onMapHandle?.(null), []); // eslint-disable-line react-hooks/exhaustive-deps
    const [level, setLevel] = useState<ViewLevel | null>(null);
    const [layers, setLayers] = useState<MapLayers>(DEFAULT_LAYERS);
    const compact = useViewportClass() === 'mobile';

    useEffect(() => {
        let cancelled = false;
        setPlaces(null);
        setPlacesError(null);
        loadBakePlaces(source).then(
            (next) => { if (!cancelled) setPlaces(next); },
            (error: unknown) => {
                if (cancelled) return;
                console.warn('[작전실 새 지도] 장소 표', error);
                setPlacesError(error instanceof Error ? error.message : String(error));
            },
        );
        return () => { cancelled = true; };
    }, [source.bakeUrl, source.kitUrl]); // eslint-disable-line react-hooks/exhaustive-deps

    const world = useMemo(() => (places ? worldFromPreview(preview, places.provinceCount) : null), [places, preview]);
    useEffect(() => {
        if (world && !world.ok) console.warn('[작전실 새 지도] 세력색', world.reason);
    }, [world]);
    const focusCell = places && focusCityId != null ? cityCell(places, focusCityId) : null;
    const urlFocusCell = places && initialView?.focusCityId != null ? cityCell(places, initialView.focusCityId) : null;
    // 처음 한 번: 주소의 城(있으면) 또는 초점 城에 맞추고 주소의 보기 수준으로. 그 뒤로는 화면 틀이 초점 城을 바꿀 때만(郡 고르기) 따라간다.
    // - 장소 표가 오기 전에 사용자가 지도를 움직였으면(끌기 · 휠 · 핀치 · 키 · 지도 단추) 처음 맞추기를 건너뛴다(옛 지도 world-map-focus 규칙).
    // - 초점이 처음 정해지는 것(없음 → 있음, 장수 자료가 늦게 옴)은 바꾼 것이 아니다.
    // - 주소로 연 보기는 사용자가 무엇이든 누르기 전까지 주소의 수준 · 城을 지킨다(늦게 바뀐 초점이 덮지 않게).
    //   주소에 城이 없으면 수준만 지키고 중심은 바뀐 초점을 따라간다.
    const urlMode = initialView != null && (initialView.level != null || initialView.focusCityId != null);
    const opened = useRef(false);
    const lastFocus = useRef<number | null>(null);
    const touched = useRef(false);
    const markTouched = () => { touched.current = true; };
    const userActed = useRef(false);
    useEffect(() => {
        if (!urlMode) return undefined;
        const acted = () => { userActed.current = true; };
        window.addEventListener('pointerdown', acted, { capture: true, once: true });
        window.addEventListener('keydown', acted, { capture: true, once: true });
        return () => {
            window.removeEventListener('pointerdown', acted, { capture: true });
            window.removeEventListener('keydown', acted, { capture: true });
        };
    }, [urlMode]);
    const openAt = (map: TopdownMapHandle) => {
        if (!opened.current) {
            const start = urlFocusCell ?? focusCell;
            if (!start) return; // 장소 표 전
            opened.current = true;
            lastFocus.current = focusCityId;
            if (touched.current) return;
            map.centerOn(start, FOCUS_ZOOM);
            if (initialView?.level) map.setLevel(initialView.level);
            return;
        }
        const was = lastFocus.current;
        lastFocus.current = focusCityId;
        if (was == null || focusCityId === was || !focusCell) return;
        if (urlMode && !userActed.current) {
            // 주소의 수준은 지킨다. 주소에 城이 없으면 중심만 바뀐 초점(순이 넘어 다시 읽은 내 城 등)을 따라간다(배율 그대로).
            if (!urlFocusCell) map.centerOn(focusCell);
            return;
        }
        map.centerOn(focusCell, FOCUS_ZOOM);
    };
    useEffect(() => {
        if (handle.current) openAt(handle.current);
    }, [focusCell?.col, focusCell?.row, focusCityId, urlFocusCell?.col, urlFocusCell?.row]); // eslint-disable-line react-hooks/exhaustive-deps

    // 내 위치 표지(M2-11): 지금은 내 城(성 안)만 안다. 성 밖 · 군단 · 이동 중은 서버 U-04 대기.
    // 이름 · 링은 내 장수 · 내 세력이다(城 이름 · 城 세력이 아니다).
    const me = useMemo<MyLocation | null>(() => {
        if (!places || homeCityId == null || !myGeneral) return null;
        const cell = cityCell(places, homeCityId);
        if (!cell) return null;
        return { cell, state: 'IN_CITY', nationColor: myGeneral.nationColor, portrait: null, name: myGeneral.name };
    }, [places, homeCityId, myGeneral]);

    // 내 위치 표지를 누르면 내 城(성 안), 城 · 깃발을 누르면 그 城
    const pickedCityId = picked?.kind === 'me' ? homeCityId : picked?.kind === 'city' || picked?.kind === 'flag' ? picked.id : null;
    const pickedCity = pickedCityId != null ? preview.cities.find((entry) => String(entry.id) === String(pickedCityId)) : undefined;

    // 「내 위치로」(Home): 내 장수 자리를 현 보기로
    const goHome = me ? () => mapHandle?.centerOn(me.cell, DEFAULT_ZOOM) : undefined;
    const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
        if (event.key !== 'Home' || !goHome) return;
        event.preventDefault();
        goHome();
    };

    // fill: 지도 밑 글줄(장소 실패 · 세력 색 실패 · 고른 곳)은 지도 위 가운데 위 겹층으로 — 상자 높이를 넘기지 않는다.
    const notes = fill ? { position: 'absolute', zIndex: PANEL_LAYER, top: 12, left: '50%', transform: 'translateX(-50%)', maxWidth: 'calc(100% - 140px)',
        padding: '6px 10px', background: 'var(--panel)', border: '1px solid var(--line-2)' } as const : null;
    return <div style={{ position: 'relative', ...(fill ? { height: '100%' } : {}) }} onKeyDown={onKeyDown}
        onPointerDownCapture={markTouched} onWheelCapture={markTouched} onKeyDownCapture={markTouched}>
        <div style={{ position: 'relative', ...(fill ? { height: '100%' } : {}) }}>
            <TopdownMap
                source={source}
                world={world?.ok ? world.world : undefined}
                layers={layers}
                me={me}
                meOverlay
                corps={corps}
                minimap
                initialView={focusCell ? { center: focusCell, zoom: FOCUS_ZOOM } : 'fit'}
                onReady={(next) => { handle.current = next; setMapHandle(next); onMapHandle?.(next); openAt(next); }}
                selectedCityId={typeof pickedCityId === 'number' ? pickedCityId : pickedCityId != null ? Number(pickedCityId) : null}
                onViewChange={({ camera: next, level: nextLevel }) => { setCamera(next); setLevel(nextLevel); }}
                onSelect={setPicked}
                ariaLabel={ariaLabel}
                style={{ width: '100%', height: fill ? '100%' : 560 }}
            />
            <div style={{ position: 'absolute', inset: 0, zIndex: MY_LOCATION_LAYER, pointerEvents: 'none' }}>
                <MyLocationLayer
                    camera={camera}
                    level={level}
                    me={me ? { at: me.cell, state: me.state, name: me.name, nationColor: me.nationColor,
                        picture: myGeneral?.picture, imageServer: myGeneral?.imageServer } : null}
                    onPick={me ? () => setPicked({ kind: 'me', id: null, cell: me.cell }) : undefined}
                    onGo={goHome}
                    edgeInset={myLocationInset}
                    serverWait="U-04"
                />
            </div>
            <MapLayerButtons
                layers={layers}
                onLayersChange={setLayers}
                pending={PENDING_LAYERS}
                compact={compact}
                open={layerPanel}
                onOpenChange={onLayerPanelChange}
                legend={<div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                    {legend.map((entry) => <LegendSwatch key={entry.nationId} color={entry.color} label={entry.name} />)}
                    <LegendSwatch color="var(--muted)" label="무주" />
                    <LegendSwatch label="미정찰" hatched />
                </div>}
                style={{ position: 'absolute', zIndex: PANEL_LAYER, ...(compact ? { right: 8, top: 64 } : { right: 12, top: 12 }) }}
            />
            {/* 왼쪽 아래 보기 단추. 지난 순 서랍(K4)이 열리면 화면 틀이 --map-viewbar-left 로 서랍 오른쪽 + 12 에 둔다(보드 Drawers). */}
            <MapViewBar
                handle={mapHandle}
                level={level}
                onMyLocation={goHome}
                style={{ position: 'absolute', zIndex: CONTROL_LAYER, left: 'var(--map-viewbar-left, 12px)', bottom: 'var(--map-viewbar-bottom, 12px)' }}
            />
        </div>
        {!fill || placesError || (world && !world.ok) || pickedCity ? <div style={notes ?? undefined}>
        {placesError ? <p role="alert" style={{ margin: '6px 0 0', color: 'var(--danger, #e08a7c)' }}>
            지도 장소를 불러오지 못했습니다. 잠시 뒤 다시 열어 주세요.</p> : null}
        {world && !world.ok ? <p role="alert" style={{ margin: '6px 0 0', color: 'var(--danger, #e08a7c)' }}>
            세력 색을 칠하지 못했습니다. 지도 자료가 서버와 맞지 않습니다.</p> : null}
        {pickedCity ? <p role="status" data-testid="war-room-picked" style={{ margin: notes ? 0 : '6px 0 0' }}>
            {picked?.kind === 'me' ? '내 위치 — ' : null}
            <strong>{pickedCity.commanderyName ? `${pickedCity.commanderyName} ${pickedCity.name}` : pickedCity.name}</strong>
        </p> : null}
        </div> : null}
    </div>;
}
