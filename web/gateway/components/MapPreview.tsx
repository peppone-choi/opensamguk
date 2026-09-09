'use client';

// 로비·로그인 화면의 지도. 게임창과 같은 아이소 지형판을 쓴다.
//
// 여기서는 2D(스프라이트) 판만 쓴다. 3D 는 three 를 끌고 오는데, 로비는 보기만 하는
// 화면이라 600KB 짜리 런타임을 번들에 들일 이유가 없다. 격자·좌표·세력색 합성은
// 게임창과 완전히 같은 코드(@opensamguk/ui/iso)를 쓴다.
import {
    IsoMap2D,
    isOwnedNationVisual,
    placeGameCities,
    useIsoTileGrid,
    type PlacedCity,
} from '@opensamguk/ui';
import { useCallback, useEffect, useMemo, useState } from 'react';

const LS_HIDE_CITYNAME = 'sam.hideMapCityName';
interface MapCity {
    id: number;
    name: string;
    level: number;
    nationId: number;
    x: number;
    y: number;
    region?: number;
    regionName?: string;
    commanderyName?: string;
    isCommanderySeat?: boolean;
    provinceId?: number;
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
    provinceOccupancy?: { provinceRecordId: string; provinceIndex: number; nationId: number }[];
    jurisdictionOwnership?: { jurisdictionId: string; nationId: number }[];
    commanderyControl?: { commanderyId: string; nationId: number }[];
}

export interface MapPreviewProps {
    serverId?: string;
    serverName?: string;
    mapData?: MapData | null;
    disallowClick?: boolean;
    currentCityId?: number | null;
    live?: boolean;
    showMe?: 0 | 1;
    refreshKey?: number;
}

export function seasonOf(month: number): string {
    if (month <= 3) return 'spring';
    if (month <= 6) return 'summer';
    if (month <= 9) return 'fall';
    return 'winter';
}

export default function MapPreview({
    serverId = 'main',
    serverName,
    mapData,
    disallowClick: _disallowClick,
    currentCityId,
    live: _live,
    showMe: _showMe,
    refreshKey = 0,
}: MapPreviewProps = {}) {
    const [data, setData] = useState<MapData | null>(null);
    const [failed, setFailed] = useState(false);
    const [hideCityName, setHideCityName] = useState(false);
    const [picked, setPicked] = useState<PlacedCity | null>(null);

    useEffect(() => {
        if (mapData != null) {
            setData(mapData);
            setFailed(false);
            return;
        }
        let active = true;
        setData(null);
        setFailed(false);
        fetch(`/api/server-map/${serverId}`, { cache: 'no-store' })
            .then((response) => response.ok ? response.json() : Promise.reject(new Error(String(response.status))))
            .then((next: MapData) => {
                if (active) setData(next);
            })
            .catch(() => {
                if (active) setFailed(true);
            });
        return () => {
            active = false;
        };
    }, [mapData, refreshKey, serverId]);

    useEffect(() => {
        setHideCityName(window.localStorage.getItem(LS_HIDE_CITYNAME) === 'yes');
    }, []);

    const nationById = useMemo(() => {
        const result = new Map<number, { name: string; color: string }>();
        data?.nations.forEach((nation) => result.set(nation.id, { name: nation.name, color: nation.color }));
        return result;
    }, [data]);

    const sourceSize = useMemo(() => ({
        width: data?.width || 700,
        height: data?.height || 610,
    }), [data?.height, data?.width]);

    // 縣 → 국가색. 서버가 판정한 provinceOccupancy 가 정본이고, 없으면 도시 소속에서 짓는다.
    const nationColorByOwner = useMemo(() => {
        const table: Record<number, string> = {};
        const occupancy = data?.provinceOccupancy ?? [];
        // 城 과 같은 규칙이다 — 소유가 확실할 때만 칠한다(placeGameCities 참조).
        const put = (provinceIndex: number | null | undefined, nationId: number) => {
            const color = nationById.get(nationId)?.color;
            if (!isOwnedNationVisual(nationId, color) || provinceIndex == null || provinceIndex < 0) return;
            table[provinceIndex] = color;
        };
        if (occupancy.length > 0) {
            for (const owner of occupancy) put(owner.provinceIndex, owner.nationId);
        } else {
            for (const city of data?.cities ?? []) put(city.provinceId, city.nationId);
        }
        return table;
    }, [data, nationById]);

    const terrainUrl = data
        ? `/api/game/api/map/terrain?server=${encodeURIComponent(serverId)}&mapCode=${encodeURIComponent(data.mapCode)}`
        : '';
    const grid = useIsoTileGrid(terrainUrl);
    const placed = useMemo<PlacedCity[]>(() => (
        grid.data && data
            ? placeGameCities(data.cities, grid.data, { sourceSize, nations: nationById })
            : []
    ), [data, grid.data, nationById, sourceSize]);
    const handlePickCity = useCallback((city: PlacedCity) => setPicked(city), []);

    if (failed || grid.status === 'error' || (data && data.cities.length === 0)) {
        return (
            <div className="map-preview" aria-label="서버 지도 프리뷰">
                <div className="map-preview-ph">맵 프리뷰 (준비 중)</div>
            </div>
        );
    }
    if (!data || grid.status !== 'ready') {
        return (
            <div className="map-preview" aria-label="서버 지도 프리뷰">
                <div className="map-preview-ph"><div className="spinner" /></div>
            </div>
        );
    }

    return (
        <div className={`map-preview${hideCityName ? ' hide-cityname' : ''}`} aria-label="서버 지도 프리뷰">
            <div className="map-preview-canvas">
                <IsoMap2D
                    data={grid.data}
                    cities={placed}
                    tintMode="nation"
                    tintStrength={0.55}
                    nationColorByOwner={nationColorByOwner}
                    currentCityId={currentCityId}
                    selectedCityId={picked?.id ?? null}
                    hideCityNames={hideCityName}
                    onPickCity={handlePickCity}
                    ariaLabel={`${data.mapCode} 서버 아이소 지도`}
                />
                <div className="map-btn-stack">
                    <button
                        type="button"
                        className={`map-toggle-cityname${hideCityName ? ' active' : ''}`}
                        aria-pressed={hideCityName}
                        onClick={() => {
                            setHideCityName((hidden) => {
                                window.localStorage.setItem(LS_HIDE_CITYNAME, hidden ? 'no' : 'yes');
                                return !hidden;
                            });
                        }}
                    >
                        도시명 표기
                    </button>
                </div>
            </div>
            {picked && (
                <div className="map-preview-tooltip map-preview-tooltip--pinned" role="status">
                    <div className="map-preview-tooltip-name">{picked.name}</div>
                    <div className="map-preview-tooltip-meta">
                        {picked.nationName ?? '재야'}
                        {picked.isCapital ? ' · 수도' : ''}
                    </div>
                </div>
            )}
            <div className="map-preview-cap">
                {`${serverName ?? data.serverName} · ${data.year}년 ${data.month}월${data.turnPhaseText ? ` ${data.turnPhaseText}` : ''}`}
            </div>
        </div>
    );
}
