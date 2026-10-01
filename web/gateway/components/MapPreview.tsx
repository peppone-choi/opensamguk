'use client';

// 로비·로그인 화면의 지도. 작전실과 같은 2D 지도판을 쓴다.
import {
    WorldMapCanvas,
    cityDisplayName,
    cityBadgeLabel,
    isUprisingNation,
    WATERWAY_SITE_ROLES,
    useWorldMap,
    worldProvincesUrl,
    type IsoCityOverlay,
    type StrategicTopologyBinding,
} from '@opensamguk/ui';
import { useCallback, useEffect, useState } from 'react';

// 레이어 「이름」 켜고 끄기(설계서 MP5). 옛 삼모 키(sam.hideMapCityName)는 쓰지 않는다.
const LS_HIDE_NAMES = 'opensamguk.map.hideNames';
/** 주인 없는 城 — 공용 지도는 「공백지」라 적는다. 화면은 v3 범례 말 「무주」로 보인다(설계서 MP7). */
const NEUTRAL_LABEL = '무주';
const SHARED_NEUTRAL_NAME = '공백지';
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
    strategicTopology?: StrategicTopologyBinding | null;
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
    /** `backdrop` = 로그인 · 가입의 화면 전체 배경(캡션은 화면이 따로 그린다). 기본 `panel`. */
    variant?: 'panel' | 'backdrop';
    /** 받은 미리보기를 옆 패널(세력 현황 · 천하 정세 이름 풀이)과 나눈다 — 같은 자료를 두 번 부르지 않는다. */
    onPreview?: (data: MapData) => void;
    onPreviewError?: () => void;
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
    variant = 'panel',
    onPreview,
    onPreviewError,
}: MapPreviewProps = {}) {
    const loadPreview = useCallback(async (signal: AbortSignal): Promise<MapData> => {
        try {
            const response = await fetch(`/api/server-map/${encodeURIComponent(serverId)}`, { cache: 'no-store', signal });
            if (!response.ok) throw new Error(String(response.status));
            const data = (await response.json()) as MapData;
            if (!signal.aborted) onPreview?.(data);
            return data;
        } catch (error) {
            if (!signal.aborted) onPreviewError?.();
            throw error;
        }
        // onPreview · onPreviewError 는 부르는 쪽이 useCallback 으로 고정한다 — 바뀔 때마다 다시 부르지 않게 의존에서 뺀다.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [serverId]);
    const map = useWorldMap({ loadPreview, mapData, serverId, refreshKey });
    const ready = map.kind === 'ready' ? map : null;
    const data = ready?.preview ?? null;
    const [hideCityName, setHideCityName] = useState(false);
    const [picked, setPicked] = useState<IsoCityOverlay | null>(null);
    const [hover, setHover] = useState<{ city: IsoCityOverlay; x: number; y: number } | null>(null);

    useEffect(() => {
        try {
            setHideCityName(window.localStorage.getItem(LS_HIDE_NAMES) === 'yes');
        } catch {
            /* 저장소를 못 읽으면(사생활 창 등) 이름을 보인다 */
        }
    }, []);
    const backdrop = variant === 'backdrop';
    const rootClass = `map-preview${backdrop ? ' map-preview--backdrop' : ''}${hideCityName ? ' hide-cityname' : ''}`;

    const handlePickCity = useCallback((city: IsoCityOverlay) => setPicked(city), []);
    const handleHoverCity = useCallback((city: IsoCityOverlay | null, at?: { x: number; y: number }) => {
        setHover(city && at ? { city, x: at.x, y: at.y } : null);
    }, []);

    if (map.kind === 'error' || map.kind === 'unsupported' || (data && data.cities.length === 0)) {
        return (
            <div className={rootClass} aria-label="서버 지도">
                <div className="map-preview-ph" role="status">
                    지도를 불러오지 못했습니다
                    {map.kind === 'unsupported' && <span className="map-preview-ph__why">지원하지 않는 지도 판 — {map.mapCode}</span>}
                </div>
            </div>
        );
    }
    if (!data) {
        return (
            <div className={rootClass} aria-label="서버 지도">
                <div className="map-preview-ph" role="status"><div className="spinner" aria-hidden="true" />지도를 불러오는 중</div>
            </div>
        );
    }

    const shown = hover?.city ?? picked;
    // 주인 없는 城은 「무주」. 공용 지도가 붙인 「공백지」도 같은 뜻이다.
    const shownNation = shown?.nationName && shown.nationName !== SHARED_NEUTRAL_NAME ? shown.nationName : undefined;

    return (
        <div className={rootClass} aria-label="서버 지도">
            <div className="map-preview-canvas">
                <WorldMapCanvas
                    className="map-preview-han"
                    mapCode={data.mapCode}
                    tiles={ready!.tiles}
                    tilesSha256={ready!.tilesSha256}
                    provinceMap={ready!.provinceMap ?? undefined}
                    provinceUrl={ready!.provinceMap ? undefined : worldProvincesUrl(serverId)}
                    cities={ready!.cities}
                    administrativeOwnership={ready!.administrativeOwnership}
                    sourceSize={ready!.sourceSize}
                    markerPositions={ready!.markerPositions}
                    currentCityId={currentCityId}
                    selectedCityId={picked?.id ?? null}
                    hideCityNames={hideCityName}
                    politicalStyle="tint"
                    showCellGrid
                    showCityFootprint
                    onCityActivate={handlePickCity}
                    onCityHover={handleHoverCity}
                    ariaLabel={`${data.mapCode} 서버 지도`}
                />
                <div className="map-btn-stack">
                    <button
                        type="button"
                        className={`map-toggle-cityname${hideCityName ? '' : ' active'}`}
                        aria-pressed={!hideCityName}
                        aria-label="지도 이름 보이기"
                        onClick={() => {
                            setHideCityName((hidden) => {
                                try {
                                    window.localStorage.setItem(LS_HIDE_NAMES, hidden ? 'no' : 'yes');
                                } catch {
                                    /* 저장하지 못해도 이번 화면에서는 바뀐다 */
                                }
                                return !hidden;
                            });
                        }}
                    >
                        이름
                    </button>
                </div>
                {/* 얹으면 커서를 따라오고, 누르면 왼위에 붙는다(손가락에는 hover 가 없다). */}
                {shown && (
                    <div
                        className={`map-preview-tooltip${hover ? '' : ' map-preview-tooltip--pinned'}`}
                        role="status"
                        style={hover ? { left: hover.x + 14, top: hover.y + 14 } : undefined}
                    >
                        <div className="map-preview-tooltip-name">{cityDisplayName(shown)}</div>
                        <div className="map-preview-tooltip-meta">
                            {isUprisingNation(shownNation) ? '봉기 세력 · ' : ''}
                            {shownNation ?? NEUTRAL_LABEL}
                            {shown.isCapital ? ' · 수도' : ''}
                        </div>
                        {(shown.cityBadges ?? []).map((badge, index) => (
                            <div className="map-preview-tooltip-meta" key={`state-${index}`}>{cityBadgeLabel(badge)}</div>
                        ))}
                        {(WATERWAY_SITE_ROLES[shown.id] ?? []).map((feature) => (
                            <div className="map-preview-tooltip-meta" key={feature}>{feature === 'port' ? '항구' : '나루'}</div>
                        ))}
                    </div>
                )}
            </div>
            {!backdrop && (
                <div className="map-preview-cap">
                    {`${serverName ?? data.serverName} · ${data.year}년 ${data.month}월${data.turnPhaseText ? ` ${data.turnPhaseText}` : ''}`}
                </div>
            )}
        </div>
    );
}
