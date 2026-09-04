'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
    formatCompactMapTooltipMeta,
    HanMapCanvas,
    isOwnedNationVisual,
    type IsoActivation,
    type IsoCityOverlay,
    type IsoCountyHover,
    type IsoHoverPoint,
    type InitialFocusProfile,
    sameStrategicBinding,
    validStrategicBinding,
    type StrategicMapSnapshot,
    type StrategicMapRoute,
    type StrategicTopologyBinding,
} from '@opensamguk/ui';
import { api } from '@/lib/api';
import { readServerCookie, useServerGameUrl } from '@/lib/serverGameUrl';
import type { GameConstResponse, MapPreviewResponse, WorldMapResponse } from '@/lib/types';
import { getMaxRelativeTechLevel } from '@/lib/utilGame';

const NEUTRAL_NAME = '공 백 지';
const DEFAULT_PHASES_PER_MONTH = 3;
const DEFAULT_TURNS_PER_YEAR = 36;
const LS_HIDE_CITYNAME = 'sam.hideMapCityName';
const LS_SINGLE_TAP = 'sam.toggleSingleTap';
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
    initialFocus?: InitialFocusProfile;
    live?: boolean;
    showMe?: 0 | 1;
    refreshKey?: number;
    gameConst?: MapTitleGameConst | null;
    selectedCityId?: number | null;
    selectedServerRoute?: StrategicMapRoute | null;
    onStrategicBindingChange?: (binding: StrategicTopologyBinding | null) => void;
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
    initialFocus,
    live = false,
    showMe = 1,
    refreshKey = 0,
    gameConst,
    selectedCityId,
    selectedServerRoute,
    onStrategicBindingChange,
    onCitySelect,
    onNavigate,
}: MapViewerProps = {}) {
    const cityBaseHref = useServerGameUrl('city');
    const [data, setData] = useState<MapPreviewResponse | null>(mapData ?? null);
    const [failed, setFailed] = useState(false);
    const [tileMissing, setTileMissing] = useState(false);
    const [liveMyCity, setLiveMyCity] = useState<number | null>(null);
    const [hoverCounty, setHoverCounty] = useState<IsoCountyHover | null>(null);
    const [cursor, setCursor] = useState<IsoHoverPoint>({ x: 0, y: 0 });
    const [hideCityNames, setHideCityNames] = useState(false);
    const [singleTap, setSingleTap] = useState(false);
    const [touchDevice, setTouchDevice] = useState(false);
    const [strategicTopology, setStrategicTopology] = useState<StrategicMapSnapshot | null>(null);
    const [strategicError, setStrategicError] = useState<string | null>(null);
    const strategicCache = useRef<{ server: string | undefined; snapshot: StrategicMapSnapshot } | null>(null);
    const bindingCallback = useRef(onStrategicBindingChange);
    bindingCallback.current = onStrategicBindingChange;
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
            setStrategicTopology(null);
            setStrategicError(null);
            bindingCallback.current?.(null);
            return;
        }
        let active = true;
        const controller = new AbortController();
        const requestServer = readServerCookie();
        if (strategicCache.current && strategicCache.current.server !== requestServer) {
            strategicCache.current = null;
            bindingCallback.current?.(null);
        }
        const serverUnchanged = () => {
            if (readServerCookie() === requestServer) return true;
            strategicCache.current = null;
            setStrategicTopology(null);
            setStrategicError('서버가 변경되어 이전 수역 응답을 표시하지 않습니다. 지도를 갱신해주세요.');
            bindingCallback.current?.(null);
            return false;
        };
        const hadData = dataRef.current != null;
        setFailed(false);
        setTileMissing(false);
        setStrategicTopology(null);
        api.mapPreview(controller.signal)
            .then(async (preview) => {
                if (!live) return { data: preview, myCity: null };
                try {
                    const world = await api.worldMap(0, showMe);
                    return world.mapName === preview.mapCode ? mergeLive(preview, world) : { data: preview, myCity: null };
                } catch {
                    return { data: preview, myCity: null };
                }
            })
            .then(async (result) => {
                if (!active || !serverUnchanged()) return;
                setData(result.data);
                setLiveMyCity(result.myCity);
                const binding = result.data.strategicTopology;
                if (result.data.mapCode !== 'han-world-v3') {
                    setStrategicTopology(null);
                    setStrategicError(null);
                    strategicCache.current = null;
                    bindingCallback.current?.(null);
                    return;
                }
                const cached = strategicCache.current && strategicCache.current.server === requestServer
                    && sameStrategicBinding(binding, strategicCache.current.snapshot.binding)
                    ? strategicCache.current.snapshot : null;
                setStrategicTopology(null); // Do not show stale dynamic control while refreshing.
                try {
                    if (!binding) throw new Error('missing binding');
                    const response = await api.strategicTopology(cached?.binding.topologyHash, controller.signal);
                    if (!active || !serverUnchanged()) return;
                    if (!sameStrategicBinding(binding, response.binding)) throw new Error('binding mismatch');
                    const topology = cached?.topology ?? response.topology;
                    if (!topology) throw new Error('missing topology');
                    const snapshot = { ...response, topology };
                    strategicCache.current = { server: requestServer, snapshot };
                    setStrategicTopology(snapshot);
                    setStrategicError(null);
                    bindingCallback.current?.(binding);
                } catch {
                    if (active) {
                        setStrategicTopology(null);
                        setStrategicError('수역 데이터가 지도와 일치하지 않거나 불러올 수 없습니다.');
                        bindingCallback.current?.(null);
                    }
                }
            })
            .catch(() => {
                if (active) {
                    setStrategicTopology(null);
                    bindingCallback.current?.(null);
                    if (!serverUnchanged()) return;
                    if (dataRef.current?.mapCode === 'han-world-v3') setStrategicError('수역 데이터를 갱신하지 못했습니다.');
                }
                if (active && !hadData) setFailed(true);
            });
        return () => { active = false; controller.abort(); };
    }, [cityBaseHref, live, mapData, refreshKey, showMe]);

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
    const administrativeOwnership = useMemo(() => ({
        provinceOccupancy: (data?.provinceOccupancy ?? []).map((owner) => ({
            ...owner,
            nationColor: nationById.get(owner.nationId)?.color,
            nationName: nationById.get(owner.nationId)?.name,
        })),
        jurisdictionOwnership: (data?.jurisdictionOwnership ?? []).map((owner) => ({
            ...owner,
            nationColor: nationById.get(owner.nationId)?.color,
            nationName: nationById.get(owner.nationId)?.name,
        })),
        commanderyControl: (data?.commanderyControl ?? []).map((owner) => ({
            ...owner,
            nationColor: nationById.get(owner.nationId)?.color,
            nationName: nationById.get(owner.nationId)?.name,
        })),
    }), [data?.commanderyControl, data?.jurisdictionOwnership, data?.provinceOccupancy, nationById]);

    const selectionEnabled = onCitySelect != null;
    const navigationEnabled = !selectionEnabled && !(disallowClick ?? mapData != null);
    // This cache key triggers a new fetch when the immutable base changes. The response's strong
    // ETag, never the requested hash, remains the byte identity checked by HanMapCanvas.
    const terrainBaseHash = data?.mapCode === 'han-world-v3' && data.strategicTopology
        && validStrategicBinding(data.strategicTopology) ? data.strategicTopology.baseTilesSha256 : null;
    const terrainUrl = useCallback((mapCode: string) =>
        `/api/game/api/map/terrain?mapCode=${encodeURIComponent(mapCode)}`
        + (mapCode === 'han-world-v3' && terrainBaseHash ? `&baseTilesSha256=${terrainBaseHash}` : ''), [terrainBaseHash]);
    const provinceUrl = useCallback((mapCode: string) =>
        `/api/game/api/map/provinces?mapCode=${encodeURIComponent(mapCode)}`, []);
    const handleMissing = useCallback(() => setTileMissing(true), []);
    const handleCountyHover = useCallback((county: IsoCountyHover | null, point?: IsoHoverPoint) => {
        setHoverCounty(county);
        if (point) setCursor(point);
    }, []);
    const activateCity = useCallback((city: IsoCityOverlay, activation?: IsoActivation) => {
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
    const legacyHoverOwnerName = hoverCounty?.nationName
        && (isOwnedNationVisual(hoverCounty.nationId, hoverCounty.nationColor)
            || hoverCounty.nationName !== NEUTRAL_NAME)
        ? hoverCounty.nationName : undefined;
    const hoverMeta = formatCompactMapTooltipMeta({
        hierarchyPath: hoverCounty?.hierarchyPath,
        displayedOwnerName: hoverCounty?.displayedOwnerNationName ?? legacyHoverOwnerName,
        ownershipMismatch: hoverCounty?.ownershipMismatch,
        provinceOccupantNationName: hoverCounty?.provinceOccupantNationName,
        jurisdictionOwnerNationName: hoverCounty?.jurisdictionOwnerNationName,
        commanderyControllerNationName: hoverCounty?.commanderyControllerNationName,
    });
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
                    administrativeOwnership={administrativeOwnership.provinceOccupancy.length > 0
                        ? administrativeOwnership : undefined}
                    sourceSize={sourceSize}
                    currentCityId={currentCityId ?? liveMyCity}
                    initialFocus={initialFocus}
                    selectedCityId={selectedCityId}
                    strategicTopology={strategicTopology ?? undefined}
                    selectedServerRoute={mapData == null && selectedServerRoute?.serverId === readServerCookie()
                        && selectedServerRoute?.worldId === strategicTopology?.binding.worldId ? selectedServerRoute : undefined}
                    currentServerId={readServerCookie()}
                    hideCityNames={hideCityNames}
                    ariaLabel={`${data.mapCode} 세계 지도`}
                    onCountyHover={handleCountyHover}
                    onCityActivate={activateCity}
                    onMissing={handleMissing}
                />
                {strategicError && <p role="status">{strategicError}</p>}
                <div className="map-btn-stack">
                    <button type="button" className={`map-toggle-cityname${hideCityNames ? ' active' : ''}`} aria-pressed={hideCityNames} onClick={toggleCityNames}>도시명 표기</button>
                    {touchDevice && <button type="button" className={`map-toggle-singletap${singleTap ? ' active' : ''}`} aria-pressed={singleTap} onClick={toggleSingleTap}>두번 탭 해 도시 이동</button>}
                </div>
            </div>
            {hoverCounty && (
                <div className="map-tooltip" role="status" style={{ left: cursor.x + 12, top: cursor.y + 30 }}>
                    <div className="map-tooltip-name">{hoverCounty.displayName ?? `${hoverCounty.commanderyName} ${hoverCounty.countyName}`}</div>
                    {hoverMeta && <div className="map-tooltip-meta">{hoverMeta}</div>}
                </div>
            )}
        </section>
    );
}
