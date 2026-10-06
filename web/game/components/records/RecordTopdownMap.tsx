'use client';

// 기록 화면의 지도(탑다운). 서버가 bakeId를 줄 때 RecordMap이 그린다(없으면 「지도를 준비 중입니다」).
// 옛 지도와 같은 동작을 옮겼다(K5 10-01): 고른 기록의 현을 가운데 · 현 보기로 두고 노란 테두리로 고른다. 현이 바뀌면 다시 맞춘다.
// 지도 칸이 작아(데스크톱 360 · 모바일 200) 보기 단추 · 작은 지도는 두지 않는다 — 끌기 · 휠 · 핀치 · 키보드는 그대로 된다.
import { useEffect, useMemo, useRef, useState } from 'react';
import {
    DEFAULT_ZOOM,
    TopdownMap,
    cityCell,
    loadBakePlaces,
    worldFromPreview,
    type PlacesData,
    type TopdownMapHandle,
    type TopdownSource,
} from '@opensamguk/ui/map/topdown';
import type { MapPreviewResponse } from '@/lib/types';

export interface RecordTopdownMapProps {
    readonly source: TopdownSource;
    readonly preview: MapPreviewResponse;
    readonly cityId: number;
    readonly label: string;
}

export default function RecordTopdownMap({ source, preview, cityId, label }: RecordTopdownMapProps) {
    const [places, setPlaces] = useState<PlacesData | null>(null);
    const [placesFailed, setPlacesFailed] = useState(false);
    const handle = useRef<TopdownMapHandle | null>(null);

    useEffect(() => {
        let cancelled = false;
        setPlaces(null);
        setPlacesFailed(false);
        loadBakePlaces(source).then(
            (next) => { if (!cancelled) setPlaces(next); },
            (error: unknown) => {
                if (cancelled) return;
                console.warn('[기록 새 지도] 장소 표', error);
                setPlacesFailed(true);
            },
        );
        return () => { cancelled = true; };
    }, [source.bakeUrl, source.kitUrl]); // eslint-disable-line react-hooks/exhaustive-deps

    const world = useMemo(() => (places ? worldFromPreview(preview, places.provinceCount) : null), [places, preview]);
    useEffect(() => {
        if (world && !world.ok) console.warn('[기록 새 지도] 세력색', world.reason);
    }, [world]);
    const cell = places ? cityCell(places, cityId) : null;
    // 현이 바뀌면(다른 기록을 고르면) 그 현으로 다시 맞춘다
    useEffect(() => {
        if (cell) handle.current?.centerOn(cell, DEFAULT_ZOOM);
    }, [cell?.col, cell?.row]); // eslint-disable-line react-hooks/exhaustive-deps

    return <>
        <TopdownMap
            source={source}
            world={world?.ok ? world.world : undefined}
            initialView={cell ? { center: cell, zoom: DEFAULT_ZOOM } : 'fit'}
            selectedCityId={cityId}
            onReady={(next) => { handle.current = next; if (cell) next.centerOn(cell, DEFAULT_ZOOM); }}
            ariaLabel={`${label} 일대 지도`}
            style={{ width: '100%', height: '100%' }}
        />
        {placesFailed ? <p role="alert" style={{ position: 'absolute', left: 12, right: 12, bottom: 12, margin: 0, color: 'var(--danger, #e08a7c)' }}>
            지도 장소를 불러오지 못했습니다. 잠시 뒤 다시 열어 주세요.</p> : null}
    </>;
}
