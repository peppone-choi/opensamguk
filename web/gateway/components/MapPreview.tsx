'use client';

import { HanMapCanvas, type IsoCityOverlay } from '@opensamguk/ui';
import { useCallback, useEffect, useMemo, useState } from 'react';
import cityRegionsData from '../config/cityRegions.json';

const CITY_REGIONS = cityRegionsData.regions as Record<string, string>;
const LS_HIDE_CITYNAME = 'sam.hideMapCityName';
const LEVEL_TEXT: Record<number, string> = {
    1: '수', 2: '진', 3: '관', 4: '이', 5: '소', 6: '중', 7: '대', 8: '특',
};

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

function levelText(level: number): string {
    return LEVEL_TEXT[level] ?? String(level);
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
    const [tileMissing, setTileMissing] = useState(false);
    const [hoverCity, setHoverCity] = useState<IsoCityOverlay | null>(null);
    const [cursor, setCursor] = useState({ x: 0, y: 0 });
    const [hideCityName, setHideCityName] = useState(false);

    useEffect(() => {
        if (mapData != null) {
            setData(mapData);
            setFailed(false);
            setTileMissing(false);
            return;
        }
        let active = true;
        setData(null);
        setFailed(false);
        setTileMissing(false);
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
        const result = new Map<number, MapNation>();
        data?.nations.forEach((nation) => result.set(nation.id, nation));
        return result;
    }, [data]);

    const cities = useMemo<IsoCityOverlay[]>(() => data?.cities.map((city) => {
        const nation = nationById.get(city.nationId);
        return {
            ...city,
            nationName: nation?.name,
            nationColor: nation?.color,
        };
    }) ?? [], [data, nationById]);
    const sourceSize = useMemo(() => ({
        width: data?.width || 700,
        height: data?.height || 610,
    }), [data?.height, data?.width]);

    const terrainUrl = useCallback((mapCode: string) => (
        `/api/game/api/map/terrain?server=${encodeURIComponent(serverId)}&mapCode=${encodeURIComponent(mapCode)}`
    ), [serverId]);
    const provinceUrl = useCallback((mapCode: string) => (
        `/api/game/api/map/provinces?server=${encodeURIComponent(serverId)}&mapCode=${encodeURIComponent(mapCode)}`
    ), [serverId]);
    const handleMissing = useCallback(() => setTileMissing(true), []);
    const handleHover = useCallback((city: IsoCityOverlay | null, point?: { x: number; y: number }) => {
        setHoverCity(city);
        if (point) setCursor(point);
    }, []);

    if (failed || tileMissing || (data && data.cities.length === 0)) {
        return (
            <div className="map-preview" aria-label="서버 지도 프리뷰">
                <div className="map-preview-ph">맵 프리뷰 (준비 중)</div>
            </div>
        );
    }
    if (!data) {
        return (
            <div className="map-preview" aria-label="서버 지도 프리뷰">
                <div className="map-preview-ph"><div className="spinner" /></div>
            </div>
        );
    }

    return (
        <div className={`map-preview${hideCityName ? ' hide-cityname' : ''}`} aria-label="서버 지도 프리뷰">
            <div className="map-preview-canvas">
                <HanMapCanvas
                    mapCode={data.mapCode}
                    terrainUrl={terrainUrl}
                    provinceUrl={provinceUrl}
                    cities={cities}
                    sourceSize={sourceSize}
                    currentCityId={currentCityId}
                    hideCityNames={hideCityName}
                    ariaLabel={`${data.mapCode} 서버 아이소 지도`}
                    onCityHover={handleHover}
                    onMissing={handleMissing}
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
            {hoverCity && (
                <div
                    className="map-preview-tooltip"
                    role="status"
                    style={{ left: cursor.x + 12, top: cursor.y + 16 }}
                >
                    <div className="map-preview-tooltip-name">
                        {`【${CITY_REGIONS[String(hoverCity.id)] ?? ''} | ${levelText(hoverCity.level)}】 ${hoverCity.name}`}
                    </div>
                    {hoverCity.nationId !== 0 && hoverCity.nationName && (
                        <div className="map-preview-tooltip-meta">{hoverCity.nationName}</div>
                    )}
                </div>
            )}
            <div className="map-preview-cap">
                {`${serverName ?? data.serverName} · ${data.year}년 ${data.month}월${data.turnPhaseText ? ` ${data.turnPhaseText}` : ''}`}
            </div>
        </div>
    );
}
