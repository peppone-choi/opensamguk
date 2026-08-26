'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
    HanMapCanvas,
    isOwnedNationVisual,
    type IsoActivation,
    type IsoCityOverlay,
    type IsoHoverPoint,
} from '@opensamguk/ui';
import { api } from '@/lib/api';
import cityRegionsData from '@/config/cityRegions.json';
import { useServerGameUrl } from '@/lib/serverGameUrl';
import type { GameConstResponse, MapPreviewResponse, WorldMapResponse } from '@/lib/types';
import { getMaxRelativeTechLevel } from '@/lib/utilGame';

const CITY_REGIONS = cityRegionsData.regions as Record<string, string>;
const NEUTRAL_NAME = '공 백 지';
const DEFAULT_PHASES_PER_MONTH = 3;
const DEFAULT_TURNS_PER_YEAR = 36;
const LS_HIDE_CITYNAME = 'sam.hideMapCityName';
const LS_SINGLE_TAP = 'sam.toggleSingleTap';
const LEVEL_TEXT: Record<number, string> = {
    1: '수', 2: '진', 3: '관', 4: '이', 5: '소', 6: '중', 7: '대', 8: '특',
};

type MapTitleGameConst = NonNullable<GameConstResponse['gameConst']>;

export function seasonOf(month: number): string {
    if (month <= 3) return 'spring';
    if (month <= 6) return 'summer';
    if (month <= 9) return 'fall';
    return 'winter';
}

export function mapTitleColor(startYear: number | undefined, year: number): string | undefined {
    if (startYear == null) return undefined;
    if (year < startYear + 1) return 'magenta';
    if (year < startYear + 2) return 'orange';
    if (year < startYear + 3) return 'yellow';
    return undefined;
}

function phaseName(phase: number): string {
    if (phase === 2) return '중순';
    if (phase === 3) return '하순';
    return '상순';
}

function dateFromElapsedTurns(startYear: number, elapsedTurns: number) {
    const year = startYear + Math.floor(elapsedTurns / DEFAULT_TURNS_PER_YEAR);
    const withinYear = ((elapsedTurns % DEFAULT_TURNS_PER_YEAR) + DEFAULT_TURNS_PER_YEAR) % DEFAULT_TURNS_PER_YEAR;
    return {
        year,
        month: Math.floor(withinYear / DEFAULT_PHASES_PER_MONTH) + 1,
        phase: (withinYear % DEFAULT_PHASES_PER_MONTH) + 1,
    };
}

function turnSpanText(turns: number): string {
    const months = Math.floor(Math.max(0, turns) / DEFAULT_PHASES_PER_MONTH);
    const phases = Math.max(0, turns) % DEFAULT_PHASES_PER_MONTH;
    const years = Math.floor(months / 12);
    const parts: string[] = [];
    if (years > 0) parts.push(`${years}년`);
    if (months % 12 > 0) parts.push(`${months % 12}개월`);
    if (phases > 0) parts.push(`${phases}순`);
    return parts.length ? parts.join(' ') : '0순';
}

export function mapTitleTooltip(
    startYear: number | undefined,
    year: number,
    month: number,
    phase = 1,
    gameConst?: MapTitleGameConst | null,
): string | undefined {
    const result: string[] = [];
    if (startYear != null) {
        const safePhase = Math.min(Math.max(Math.trunc(phase || 1), 1), DEFAULT_PHASES_PER_MONTH);
        const openingTurns = gameConst?.openingLimitTurns ?? DEFAULT_TURNS_PER_YEAR;
        const elapsed = (year - startYear) * DEFAULT_TURNS_PER_YEAR
            + (month - 1) * DEFAULT_PHASES_PER_MONTH + safePhase - 1;
        if (elapsed < openingTurns) {
            const unlock = dateFromElapsedTurns(startYear, openingTurns);
            result.push(`초반제한 기간 : ${turnSpanText(openingTurns - elapsed)} (${unlock.year}년 ${unlock.month}월 ${phaseName(unlock.phase)} 해제)`);
        }
    }
    const max = gameConst?.maxTechLevel;
    const initial = gameConst?.initialAllowedTechLevel;
    const incYear = gameConst?.techLevelIncYear;
    if (startYear != null && typeof max === 'number' && typeof initial === 'number'
        && typeof incYear === 'number' && incYear > 0) {
        const limit = getMaxRelativeTechLevel(startYear, year, max, initial, incYear);
        result.push(limit === max
            ? `기술등급 제한 : ${limit}등급 (최종)`
            : `기술등급 제한 : ${limit}등급 (${limit * incYear + startYear}년 해제)`);
    }
    return result.length ? result.join('\n') : undefined;
}

export interface MapViewerProps {
    mapData?: MapPreviewResponse | null;
    isDetailMap?: boolean;
    disallowClick?: boolean;
    currentCityId?: number | null;
    live?: boolean;
    showMe?: 0 | 1;
    refreshKey?: number;
    gameConst?: MapTitleGameConst | null;
    selectedCityId?: number | null;
    onCitySelect?: (cityId: number) => void;
    onNavigate?: (href: string) => void;
}

function mergeLive(preview: MapPreviewResponse, world: WorldMapResponse) {
    const cityById = new Map(world.cityList.map((city) => [city[0], city]));
    const nationById = new Map(world.nationList.map((nation) => [nation[0] as number, nation]));
    return {
        data: {
            ...preview,
            startYear: world.startYear,
            year: world.year,
            month: world.month,
            turnPhase: world.turnPhase ?? preview.turnPhase,
            turnPhaseText: world.turnPhaseText ?? preview.turnPhaseText,
            cities: preview.cities.map((city) => {
                const tuple = cityById.get(city.id);
                if (!tuple) return city;
                const [, level, state, nationId, , supply] = tuple;
                return {
                    ...city,
                    level,
                    state,
                    nationId,
                    supply: supply !== 0,
                    isCapital: nationById.get(nationId)?.[3] === city.id,
                };
            }),
            nations: world.nationList.map((nation) => ({
                id: nation[0] as number,
                name: nation[1] as string,
                color: nation[2] as string,
            })),
        } satisfies MapPreviewResponse,
        myCity: world.myCity,
    };
}

export default function MapViewer({
    mapData,
    disallowClick,
    currentCityId,
    live = false,
    showMe = 1,
    refreshKey = 0,
    gameConst,
    selectedCityId,
    onCitySelect,
    onNavigate,
}: MapViewerProps = {}) {
    const cityBaseHref = useServerGameUrl('city');
    const [data, setData] = useState<MapPreviewResponse | null>(mapData ?? null);
    const [failed, setFailed] = useState(false);
    const [tileMissing, setTileMissing] = useState(false);
    const [liveMyCity, setLiveMyCity] = useState<number | null>(null);
    const [hoverCity, setHoverCity] = useState<IsoCityOverlay | null>(null);
    const [cursor, setCursor] = useState<IsoHoverPoint>({ x: 0, y: 0 });
    const [hideCityNames, setHideCityNames] = useState(false);
    const [singleTap, setSingleTap] = useState(false);
    const [touchDevice, setTouchDevice] = useState(false);
    const touchArmedId = useRef<number | null>(null);
    const dataRef = useRef<MapPreviewResponse | null>(data);

    useEffect(() => {
        dataRef.current = data;
    }, [data]);

    useEffect(() => {
        if (mapData != null) {
            setData(mapData);
            setFailed(false);
            setTileMissing(false);
            setLiveMyCity(null);
            return;
        }
        let active = true;
        const hadData = dataRef.current != null;
        setFailed(false);
        setTileMissing(false);
        api.mapPreview()
            .then(async (preview) => {
                if (!live) return { data: preview, myCity: null };
                try {
                    return mergeLive(preview, await api.worldMap(0, showMe));
                } catch {
                    return { data: preview, myCity: null };
                }
            })
            .then((result) => {
                if (!active) return;
                setData(result.data);
                setLiveMyCity(result.myCity);
            })
            .catch(() => {
                if (active && !hadData) setFailed(true);
            });
        return () => { active = false; };
    }, [live, mapData, refreshKey, showMe]);

    useEffect(() => {
        setHideCityNames(window.localStorage.getItem(LS_HIDE_CITYNAME) === 'yes');
        setSingleTap(window.localStorage.getItem(LS_SINGLE_TAP) === 'yes');
        setTouchDevice(navigator.maxTouchPoints > 0 || window.matchMedia('(any-pointer: coarse)').matches);
    }, []);

    const nationById = useMemo(() => new Map(
        data?.nations.map((nation) => [nation.id, nation]) ?? [],
    ), [data]);
    const cities = useMemo<IsoCityOverlay[]>(() => data?.cities.map((city) => {
        const nation = nationById.get(city.nationId);
        const owned = isOwnedNationVisual(city.nationId, nation?.color);
        return {
            ...city,
            nationName: owned ? nation?.name : NEUTRAL_NAME,
            nationColor: owned ? nation.color : undefined,
        };
    }) ?? [], [data, nationById]);
    const sourceSize = useMemo(() => ({
        width: data?.width || 700,
        height: data?.height || 610,
    }), [data?.height, data?.width]);

    const selectionEnabled = onCitySelect != null;
    const navigationEnabled = !selectionEnabled && !(disallowClick ?? mapData != null);
    const terrainUrl = useCallback((mapCode: string) =>
        `/api/game/api/map/terrain?mapCode=${encodeURIComponent(mapCode)}`, []);
    const provinceUrl = useCallback((mapCode: string) =>
        `/api/game/api/map/provinces?mapCode=${encodeURIComponent(mapCode)}`, []);
    const handleMissing = useCallback(() => setTileMissing(true), []);
    const handleHover = useCallback((city: IsoCityOverlay | null, point?: IsoHoverPoint) => {
        setHoverCity(city);
        if (point) setCursor(point);
    }, []);
    const activateCity = useCallback((city: IsoCityOverlay, activation?: IsoActivation) => {
        setHoverCity(city);
        if (selectionEnabled) {
            onCitySelect?.(city.id);
            return;
        }
        if (!navigationEnabled) return;
        if (activation?.pointerType === 'touch') {
            const alreadyArmed = touchArmedId.current === city.id;
            touchArmedId.current = city.id;
            if (!singleTap && !alreadyArmed) return;
        }
        const href = `${cityBaseHref}?id=${encodeURIComponent(String(city.id))}`;
        if (onNavigate) onNavigate(href);
        else window.location.assign(href);
    }, [cityBaseHref, navigationEnabled, onCitySelect, onNavigate, selectionEnabled, singleTap]);

    const toggleCityNames = () => setHideCityNames((hidden) => {
        window.localStorage.setItem(LS_HIDE_CITYNAME, hidden ? 'no' : 'yes');
        return !hidden;
    });
    const toggleSingleTap = () => setSingleTap((enabled) => {
        window.localStorage.setItem(LS_SINGLE_TAP, enabled ? 'no' : 'yes');
        return !enabled;
    });

    if (failed || tileMissing || (data && data.cities.length === 0)) {
        return <section className="map-viewer" aria-label="세계 지도"><div className="map-viewer-ph">지도 데이터 준비 중입니다.</div></section>;
    }
    if (!data) {
        return <section className="map-viewer" aria-label="세계 지도"><div className="map-viewer-ph"><div className="spinner" /></div></section>;
    }

    const title = `${data.year}년 ${data.month}월${data.turnPhaseText ? ` ${data.turnPhaseText}` : ''}`;
    const tooltip = mapTitleTooltip(data.startYear, data.year, data.month, data.turnPhase ?? 1, gameConst);
    return (
        <section className={`map-viewer${hideCityNames ? ' hide-cityname' : ''}`} aria-label="세계 지도">
            <div
                className="map-viewer-title"
                style={{ color: mapTitleColor(data.startYear, data.year) }}
                title={tooltip}
                aria-label={tooltip ? `${title} ${tooltip.replace(/\n/g, ' ')}` : title}
            >
                {title}
            </div>
            <div className="map-viewer-canvas">
                <HanMapCanvas
                    mapCode={data.mapCode}
                    terrainUrl={terrainUrl}
                    provinceUrl={provinceUrl}
                    cities={cities}
                    sourceSize={sourceSize}
                    currentCityId={currentCityId ?? liveMyCity}
                    selectedCityId={selectedCityId}
                    hideCityNames={hideCityNames}
                    ariaLabel={`${data.mapCode} 세계 지도`}
                    onCityHover={handleHover}
                    onCityActivate={activateCity}
                    onMissing={handleMissing}
                />
                <div className="map-btn-stack">
                    <button type="button" className={`map-toggle-cityname${hideCityNames ? ' active' : ''}`} aria-pressed={hideCityNames} onClick={toggleCityNames}>도시명 표기</button>
                    {touchDevice && <button type="button" className={`map-toggle-singletap${singleTap ? ' active' : ''}`} aria-pressed={singleTap} onClick={toggleSingleTap}>두번 탭 해 도시 이동</button>}
                </div>
            </div>
            {hoverCity && (
                <div className="map-tooltip" role="status" style={{ left: cursor.x + 12, top: cursor.y + 30 }}>
                    <div className="map-tooltip-name">{`【${CITY_REGIONS[String(hoverCity.id)] ?? ''} | ${LEVEL_TEXT[hoverCity.level] ?? hoverCity.level}】 ${hoverCity.name}`}</div>
                    {isOwnedNationVisual(hoverCity.nationId, hoverCity.nationColor)
                        && <div className="map-tooltip-meta">{hoverCity.nationName}</div>}
                </div>
            )}
        </section>
    );
}
