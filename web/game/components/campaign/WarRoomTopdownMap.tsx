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
    TopdownMap,
    cityCell,
    loadBakePlaces,
    worldFromPreview,
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
    /** 주소로 연 보기(`?view=…&focus=…`). 처음 한 번만 맞춘다. 모르는 城이면 기본 초점, 수준이 없으면 기본(郡) 보기. */
    readonly initialView?: WarRoomMapView;
}

export default function WarRoomTopdownMap({ source, preview, homeCityId, focusCityId, ariaLabel, legend = [], onMapHandle,
    layerPanel, onLayerPanelChange, initialView }: WarRoomTopdownMapProps) {
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
    // 처음 한 번: 주소의 城(있으면) 또는 초점 城에 맞추고 주소의 보기 수준으로. 그 뒤로는 초점 城이 바뀔 때만 따라간다.
    const opened = useRef(false);
    const openAt = (map: TopdownMapHandle) => {
        if (!opened.current) {
            const start = urlFocusCell ?? focusCell;
            if (!start) return; // 장소 표 전
            opened.current = true;
            map.centerOn(start, FOCUS_ZOOM);
            if (initialView?.level) map.setLevel(initialView.level);
            return;
        }
        if (focusCell) map.centerOn(focusCell, FOCUS_ZOOM);
    };
    useEffect(() => {
        if (handle.current) openAt(handle.current);
    }, [focusCell?.col, focusCell?.row, urlFocusCell?.col, urlFocusCell?.row]); // eslint-disable-line react-hooks/exhaustive-deps

    const me = useMemo<MyLocation | null>(() => {
        if (!places || homeCityId == null) return null;
        const cell = cityCell(places, homeCityId);
        const city = preview.cities.find((entry) => entry.id === homeCityId);
        if (!cell || !city) return null;
        const nation = preview.nations.find((entry) => entry.id === city.nationId);
        return { cell, state: 'IN_CITY', nationColor: nation?.color ?? null, portrait: null, name: city.name };
    }, [places, homeCityId, preview]);

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

    return <div style={{ position: 'relative' }} onKeyDown={onKeyDown}>
        <div style={{ position: 'relative' }}>
            <TopdownMap
                source={source}
                world={world?.ok ? world.world : undefined}
                layers={layers}
                me={me}
                minimap
                initialView={focusCell ? { center: focusCell, zoom: FOCUS_ZOOM } : 'fit'}
                onReady={(next) => { handle.current = next; setMapHandle(next); onMapHandle?.(next); openAt(next); }}
                selectedCityId={typeof pickedCityId === 'number' ? pickedCityId : pickedCityId != null ? Number(pickedCityId) : null}
                onViewChange={({ level: next }) => setLevel(next)}
                onSelect={setPicked}
                ariaLabel={ariaLabel}
                style={{ width: '100%', height: 560 }}
            />
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
        {placesError ? <p role="alert" style={{ margin: '6px 0 0', color: 'var(--danger, #e08a7c)' }}>
            지도 장소를 불러오지 못했습니다. 잠시 뒤 다시 열어 주세요.</p> : null}
        {world && !world.ok ? <p role="alert" style={{ margin: '6px 0 0', color: 'var(--danger, #e08a7c)' }}>
            세력 색을 칠하지 못했습니다. 지도 자료가 서버와 맞지 않습니다.</p> : null}
        {pickedCity ? <p role="status" data-testid="war-room-picked" style={{ margin: '6px 0 0' }}>
            {picked?.kind === 'me' ? '내 위치 — ' : null}
            <strong>{pickedCity.commanderyName ? `${pickedCity.commanderyName} ${pickedCity.name}` : pickedCity.name}</strong>
        </p> : null}
    </div>;
}
