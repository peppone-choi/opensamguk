'use client';

// 작전실 천하 형세의 새 지도(탑다운). 제품 화면 교체 스위치(NEXT_PUBLIC_TOPDOWN_SCREENS)와 서버 bakeId가
// 둘 다 있을 때만 WarRoomMap이 이것을 그린다. 세력색은 preview의 구역 점유, 초점 · 내 위치는 bake 장소 표의 城 칸.
// 아직 옮기지 않은 것: 안개(郡 단위 시야 → 구역 대응), 부대 겹층(K2-08 서버 칸 · 경로 대기).
import { useEffect, useMemo, useRef, useState } from 'react';
import {
    TopdownMap,
    cityCell,
    loadBakePlaces,
    worldFromPreview,
    type HitResult,
    type MyLocation,
    type PlacesData,
    type TopdownMapHandle,
    type TopdownSource,
} from '@opensamguk/ui/map/topdown';
import type { MapPreviewResponse } from '@/lib/types';

/** 郡 보기(4 px/칸 이상 8 미만)에서 초점 郡을 비춘다. */
const FOCUS_ZOOM = 6;

export interface WarRoomTopdownMapProps {
    readonly source: TopdownSource;
    readonly preview: MapPreviewResponse;
    readonly homeCityId: number | null;
    readonly focusCityId: number | null;
    readonly ariaLabel: string;
}

export default function WarRoomTopdownMap({ source, preview, homeCityId, focusCityId, ariaLabel }: WarRoomTopdownMapProps) {
    const [places, setPlaces] = useState<PlacesData | null>(null);
    const [placesError, setPlacesError] = useState<string | null>(null);
    const [picked, setPicked] = useState<HitResult | null>(null);
    const handle = useRef<TopdownMapHandle | null>(null);

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
    useEffect(() => {
        if (focusCell) handle.current?.centerOn(focusCell, FOCUS_ZOOM);
    }, [focusCell?.col, focusCell?.row]); // eslint-disable-line react-hooks/exhaustive-deps

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

    return <div style={{ position: 'relative' }}>
        <TopdownMap
            source={source}
            world={world?.ok ? world.world : undefined}
            me={me}
            minimap
            initialView={focusCell ? { center: focusCell, zoom: FOCUS_ZOOM } : 'fit'}
            onReady={(next) => { handle.current = next; if (focusCell) next.centerOn(focusCell, FOCUS_ZOOM); }}
            onSelect={setPicked}
            ariaLabel={ariaLabel}
            style={{ width: '100%', height: 560 }}
        />
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
