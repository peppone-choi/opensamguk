'use client';

// 로비·로그인 화면의 지도. 작전실과 같은 2D 지도판을 쓴다.
import {
    HanMapCanvas,
    cityDisplayName,
    cityBadgeLabel,
    citySnapshotBadges,
    isUprisingNation,
    WATERWAY_SITE_ROLES,
    isOwnedNationVisual,
    type IsoCityOverlay,
} from '@opensamguk/ui';
import { useCallback, useEffect, useMemo, useState } from 'react';

const LS_HIDE_CITYNAME = 'sam.hideMapCityName';
interface MapCity {
    id: number;
    name: string;
    /** meta.nameCh — 縣 판정용(cityName.ts). 없으면 level 로만 가른다. */
    nameCh?: string;
    /** 서버가 계산한 화면 이름("경조윤 장안현"). 오면 그대로 쓴다 — 로그와 같은 글자다. */
    displayName?: string;
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
    const [picked, setPicked] = useState<IsoCityOverlay | null>(null);
    // 마우스를 얹은 城. 툴팁은 이걸로 뜬다 — 눌러야 나오는 건 지도가 아니다.
    const [hover, setHover] = useState<{ city: IsoCityOverlay; x: number; y: number } | null>(null);

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

    const cities = useMemo<IsoCityOverlay[]>(() => (data?.cities ?? []).map((city) => {
        const nation = nationById.get(city.nationId);
        const owned = isOwnedNationVisual(city.nationId, nation?.color);
        return { ...city, nationName: owned ? nation?.name : undefined,
            nationColor: owned ? nation?.color : undefined,
            cityBadges: citySnapshotBadges(city).filter((badge) => badge.kind === 'supply') };
    }), [data, nationById]);
    const ownership = useMemo(() => data ? ({
        provinceOccupancy: (data.provinceOccupancy ?? []).map((owner) => ({ ...owner,
            nationName: nationById.get(owner.nationId)?.name,
            nationColor: nationById.get(owner.nationId)?.color })),
        jurisdictionOwnership: (data.jurisdictionOwnership ?? []).map((owner) => ({ ...owner,
            nationName: nationById.get(owner.nationId)?.name,
            nationColor: nationById.get(owner.nationId)?.color })),
        commanderyControl: (data.commanderyControl ?? []).map((owner) => ({ ...owner,
            nationName: nationById.get(owner.nationId)?.name,
            nationColor: nationById.get(owner.nationId)?.color })),
    }) : undefined, [data, nationById]);
    const handlePickCity = useCallback((city: IsoCityOverlay) => setPicked(city), []);
    const handleMissing = useCallback(() => setFailed(true), []);
    const handleHoverCity = useCallback((city: IsoCityOverlay | null, at?: { x: number; y: number }) => {
        setHover(city && at ? { city, x: at.x, y: at.y } : null);
    }, []);

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
                <div className="map-preview-ph"><div className="spinner" /></div>
            </div>
        );
    }

    return (
        <div className={`map-preview${hideCityName ? ' hide-cityname' : ''}`} aria-label="서버 지도 프리뷰">
            <div className="map-preview-canvas">
                <HanMapCanvas
                    className="map-preview-han"
                    mapCode={data.mapCode}
                    terrainUrl={`/api/game/api/map/terrain?server=${encodeURIComponent(serverId)}&mapCode=${encodeURIComponent(data.mapCode)}`}
                    provinceUrl={`/api/game/api/map/provinces?server=${encodeURIComponent(serverId)}&mapCode=${encodeURIComponent(data.mapCode)}`}
                    cities={cities}
                    administrativeOwnership={ownership}
                    sourceSize={sourceSize}
                    currentCityId={currentCityId}
                    selectedCityId={picked?.id ?? null}
                    hideCityNames={hideCityName}
                    politicalStyle="tint"
                    showCityFootprint
                    onCityActivate={handlePickCity}
                    onCityHover={handleHoverCity}
                    onMissing={handleMissing}
                    ariaLabel={`${data.mapCode} 서버 지도`}
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
                {/* 얹으면 커서를 따라오고, 누르면 왼위에 붙는다(손가락에는 hover 가 없다). */}
                {(hover ?? picked) && (
                    <div
                        className={`map-preview-tooltip${hover ? '' : ' map-preview-tooltip--pinned'}`}
                        role="status"
                        style={hover ? { left: hover.x + 14, top: hover.y + 14 } : undefined}
                    >
                        <div className="map-preview-tooltip-name">{cityDisplayName((hover?.city ?? picked)!)}</div>
                        {/* 주인이 없으면 국가 줄을 내지 않는다 — 공백지에 「재야」라고 적지 않는다(2026-09-10). */}
                        {((hover?.city ?? picked)!.nationName || (hover?.city ?? picked)!.isCapital) && (
                            <div className="map-preview-tooltip-meta">
                                {isUprisingNation((hover?.city ?? picked)!.nationName) ? '봉기 세력 · ' : ''}
                                {(hover?.city ?? picked)!.nationName ?? ''}
                                {(hover?.city ?? picked)!.isCapital
                                    ? `${(hover?.city ?? picked)!.nationName ? ' · ' : ''}수도`
                                    : ''}
                            </div>
                        )}
                        {citySnapshotBadges((hover?.city ?? picked)!).map((badge, index) => (
                            <div className="map-preview-tooltip-meta" key={`state-${index}`}>{cityBadgeLabel(badge)}</div>
                        ))}
                        {(data.mapCode === 'han-world-v3'
                            ? WATERWAY_SITE_ROLES[(hover?.city ?? picked)!.id] ?? []
                            : []).map((feature) => (
                            <div className="map-preview-tooltip-meta" key={feature}>{feature === 'port' ? '항구' : '나루'}</div>
                        ))}
                    </div>
                )}
            </div>
            <div className="map-preview-cap">
                {`${serverName ?? data.serverName} · ${data.year}년 ${data.month}월${data.turnPhaseText ? ` ${data.turnPhaseText}` : ''}`}
            </div>
        </div>
    );
}
