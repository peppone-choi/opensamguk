'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { cityBadgeLabel, formatCompactMapTooltipMeta, HanMapCanvas, isOwnedNationVisual, isUprisingNation, useWorldMap, worldProvincesUrl, type IsoActivation, type IsoCityOverlay, type IsoCountyHover, type IsoHoverPoint, type InitialFocusProfile, sameStrategicBinding, type StrategicMapSnapshot, type StrategicMapRoute, type StrategicTopologyBinding, EmptyState, PlaceNameWithGloss } from '@opensamguk/ui';
import { api } from '@/lib/api';
import { readServerCookie, useServerGameUrl } from '@/lib/serverGameUrl';
import type { GameConstResponse, MapPreviewResponse, WorldMapResponse } from '@/lib/types';
import { getMaxRelativeTechLevel } from '@/lib/utilGame';
import { useMapLayers, type MapLayerScope } from '@/lib/use-map-layers';
import { buildVisibleCorps } from '@/lib/map-corps';
import { commanderyOfCity } from '@/lib/hwiha-fog';
import { CommanderyNavigator } from '@/components/hwiha/CommanderyNavigator';

const NEUTRAL_NAME = '공백지';
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

// 개시 3년 강조 — 리터럴 magenta/orange/yellow 대신 팔레트 클래스를 준다.
export function mapTitleClass(startYear: number | undefined, year: number): string | undefined {
    if (startYear == null) return undefined;
    if (year < startYear + 1) return 'map-title--y1';
    if (year < startYear + 2) return 'map-title--y2';
    if (year < startYear + 3) return 'map-title--y3';
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
    hwihaLayers?: MapLayerScope;
    disallowClick?: boolean;
    currentCityId?: number | null;
    generalId?: number | null;
    initialFocus?: InitialFocusProfile;
    live?: boolean;
    showMe?: 0 | 1;
    refreshKey?: number;
    gameConst?: MapTitleGameConst | null;
    selectedCityId?: number | null;
    selectedServerRoute?: StrategicMapRoute | null;
    onStrategicBindingChange?: (binding: StrategicTopologyBinding | null) => void;
    onCitySelect?: (cityId: number) => void;
    /** 선택 모드의 확장 콜백 — 클릭한 도시 오버레이(국가명·국가색 포함)를 통째로 받는다(05 천하 지도 레일). */
    onCityPick?: (city: IsoCityOverlay) => void;
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
    hwihaLayers,
    disallowClick,
    currentCityId,
    generalId,
    initialFocus,
    live = false,
    showMe = 1,
    refreshKey = 0,
    gameConst,
    selectedCityId,
    selectedServerRoute,
    onStrategicBindingChange,
    onCitySelect,
    onCityPick,
    onNavigate,
}: MapViewerProps = {}) {
    const cityBaseHref = useServerGameUrl('city');
    const [tileMissing, setTileMissing] = useState(false);
    const [liveMyCity, setLiveMyCity] = useState<number | null>(null);
    const [hoverCounty, setHoverCounty] = useState<IsoCountyHover | null>(null);
    const [cursor, setCursor] = useState<IsoHoverPoint>({ x: 0, y: 0 });
    const [hideCityNames, setHideCityNames] = useState(false);
    const [singleTap, setSingleTap] = useState(false);
    const [touchDevice, setTouchDevice] = useState(false);
    const [strategicTopology, setStrategicTopology] = useState<StrategicMapSnapshot | null>(null);
    const [strategicError, setStrategicError] = useState<string | null>(null);
    const [hoverCity, setHoverCity] = useState<IsoCityOverlay | null>(null);
    const strategicCache = useRef<{ server: string | undefined; snapshot: StrategicMapSnapshot } | null>(null);
    const bindingCallback = useRef(onStrategicBindingChange);
    bindingCallback.current = onStrategicBindingChange;
    const touchArmedId = useRef<number | null>(null);
    const [focusNo, setFocusNo] = useState<number | null>(null);
    const loadPreview = useCallback(async (signal: AbortSignal): Promise<MapPreviewResponse> => {
        const previewRequest = api.mapPreview(signal);
        const worldRequest = live ? api.worldMap(0, showMe).catch(() => null) : Promise.resolve(null);
        const preview = await previewRequest;
        const world = await worldRequest;
        const merged = world && world.mapName === preview.mapCode ? mergeLive(preview, world) : { data: preview, myCity: null };
        if (!signal.aborted) setLiveMyCity(merged.myCity);
        return merged.data;
    }, [live, showMe]);
    const layerScope = hwihaLayers ?? (mapData != null ? 'none' : 'full');
    const layers = useMapLayers(layerScope, refreshKey, generalId);
    const map = useWorldMap({ loadPreview, mapData, refreshKey, cacheScope: readServerCookie(),
        works: layers.works, sieges: layers.sieges });
    const ready = map.kind === 'ready' ? map : null;
    const data = ready?.preview ?? null;
    const home = useMemo(() => {
        if (!ready) return undefined;
        const city = ready.preview.cities.find((entry) => entry.id === (currentCityId ?? liveMyCity));
        return commanderyOfCity(ready.commanderies, city?.commanderyName);
    }, [ready, currentCityId, liveMyCity]);
    const focus = ready && layerScope === 'full' ?
        ready.commanderies.find((entry) => entry.no === focusNo) ?? home ?? ready.commanderies.find((entry) => entry.focusCityId != null) : undefined;
    const focusCityId = focus && home && focus.no === home.no ? currentCityId ?? liveMyCity : focus?.focusCityId;
    const corpsOverlay = useMemo(() => ready && layerScope === 'full'
        ? buildVisibleCorps(layers.corps, layers.visibility, ready.provinceCenter) : [],
        [ready, layerScope, layers.corps, layers.visibility]);

    useEffect(() => {
        if (mapData != null) return;
        setStrategicTopology(null);
        bindingCallback.current?.(null);
    }, [refreshKey, mapData]);

    // 2026-09-17: 메인 지도의 전장(장판·관도) 표식과 전장 선택 줄을 뺐다(사용자 결정).

    useEffect(() => {
        setStrategicTopology(null);
        if (mapData != null || !data) { bindingCallback.current?.(null); return; }
        const requestServer = readServerCookie();
        let active = true;
        const controller = new AbortController();
        const binding = data.strategicTopology;
        const previous = strategicCache.current;
        const cached = previous && previous.server === requestServer
            && sameStrategicBinding(binding, previous.snapshot.binding)
            ? previous.snapshot : null;
        void (async () => {
            try {
                if (!binding) throw new Error('missing binding');
                const response = await api.strategicTopology(cached?.binding.topologyHash, controller.signal);
                if (!active) return;
                if (readServerCookie() !== requestServer) {
                    strategicCache.current = null;
                    setStrategicError('서버가 변경되어 이전 수역 응답을 표시하지 않습니다. 지도를 갱신해주세요.');
                    bindingCallback.current?.(null);
                    return;
                }
                if (!sameStrategicBinding(binding, response.binding)) throw new Error('binding mismatch');
                const topology = cached?.topology ?? response.topology;
                if (!topology) throw new Error('missing topology');
                const snapshot = { ...response, topology };
                strategicCache.current = { server: requestServer, snapshot };
                setStrategicTopology(snapshot);
                setStrategicError(null);
                bindingCallback.current?.(binding);
            } catch {
                if (!active) return;
                setStrategicError('수역 데이터가 지도와 일치하지 않거나 불러올 수 없습니다.');
                bindingCallback.current?.(null);
            }
        })();
        return () => { active = false; controller.abort(); };
    }, [data, mapData]);

    useEffect(() => {
        setHideCityNames(window.localStorage.getItem(LS_HIDE_CITYNAME) === 'yes');
        setSingleTap(window.localStorage.getItem(LS_SINGLE_TAP) === 'yes');
        setTouchDevice(navigator.maxTouchPoints > 0 || window.matchMedia('(any-pointer: coarse)').matches);
    }, []);

    const cities = ready?.cities ?? [];
    const sourceSize = ready?.sourceSize;
    const administrativeOwnership = ready?.administrativeOwnership;

    const selectionEnabled = onCitySelect != null || onCityPick != null;
    const navigationEnabled = !selectionEnabled && !(disallowClick ?? mapData != null);
    const handleMissing = useCallback(() => setTileMissing(true), []);
    const handleCountyHover = useCallback((county: IsoCountyHover | null, point?: IsoHoverPoint) => {
        setHoverCounty(county);
        if (point) setCursor(point);
    }, []);
    const activateCity = useCallback((city: IsoCityOverlay, activation?: IsoActivation) => {
        if (selectionEnabled) {
            onCitySelect?.(city.id);
            onCityPick?.(city);
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
    }, [cityBaseHref, navigationEnabled, onCityPick, onCitySelect, onNavigate, selectionEnabled, singleTap]);

    const toggleCityNames = () => setHideCityNames((hidden) => {
        window.localStorage.setItem(LS_HIDE_CITYNAME, hidden ? 'no' : 'yes');
        return !hidden;
    });
    const toggleSingleTap = () => setSingleTap((enabled) => {
        window.localStorage.setItem(LS_SINGLE_TAP, enabled ? 'no' : 'yes');
        return !enabled;
    });

    if (map.kind === 'error' || map.kind === 'unsupported' || tileMissing || (data && data.cities.length === 0)) {
        return <section className="map-viewer" aria-label="세계 지도"><EmptyState illustration="map" title={map.kind === 'unsupported' ? `지원하지 않는 지도 판: ${map.mapCode}` : '지도 데이터 준비 중입니다.'} className="map-viewer-ph" /></section>;
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
    const displayedOwnerName = hoverCounty?.displayedOwnerNationName ?? legacyHoverOwnerName;
    const hoverMeta = formatCompactMapTooltipMeta({
        hierarchyPath: hoverCounty?.hierarchyPath,
        displayedOwnerName: isUprisingNation(displayedOwnerName)
            ? `봉기 세력 · ${displayedOwnerName}` : displayedOwnerName,
        ownershipMismatch: hoverCounty?.ownershipMismatch,
        provinceOccupantNationName: hoverCounty?.provinceOccupantNationName,
        jurisdictionOwnerNationName: hoverCounty?.jurisdictionOwnerNationName,
        commanderyControllerNationName: hoverCounty?.commanderyControllerNationName,
    });
    return (
        <section className={`map-viewer${hideCityNames ? ' hide-cityname' : ''}`} aria-label="세계 지도">
            <div
                className={`map-viewer-title${mapTitleClass(data.startYear, data.year) ? ` ${mapTitleClass(data.startYear, data.year)}` : ''}`}
                title={tooltip}
                aria-label={tooltip ? `${title} ${tooltip.replace(/\n/g, ' ')}` : title}
            >
                {title}
            </div>
            <div className="map-viewer-canvas">
                <HanMapCanvas
                    key={focus?.no ?? 'world'}
                    mapCode={data.mapCode}
                    tiles={ready!.tiles}
                    tilesSha256={ready!.tilesSha256}
                    provinceMap={ready!.provinceMap ?? undefined}
                    provinceUrl={ready!.provinceMap ? undefined : worldProvincesUrl()}
                    cities={cities}
                    administrativeOwnership={administrativeOwnership}
                    sourceSize={sourceSize}
                    markerPositions={ready!.markerPositions}
                    corps={corpsOverlay}
                    commanderyVisibility={layerScope !== 'none' ? layers.visibility : null}
                    fogMode="dim"
                    currentCityId={currentCityId ?? liveMyCity}
                    cameraFocusCityId={focusCityId ?? currentCityId ?? liveMyCity}
                    initialFocus={focus ? 'current-commandery' : initialFocus}
                    selectedCityId={selectedCityId}
                    strategicTopology={strategicTopology ?? undefined}
                    selectedServerRoute={mapData == null && selectedServerRoute?.serverId === readServerCookie()
                        && selectedServerRoute?.worldId === strategicTopology?.binding.worldId ? selectedServerRoute : undefined}
                    currentServerId={readServerCookie()}
                    hideCityNames={hideCityNames}
                    showCityFootprint
                    politicalStyle="tint"
                    ariaLabel={`${data.mapCode} 세계 지도`}
                    onCountyHover={handleCountyHover}
                    onCityHover={(city, point) => {
                        setHoverCity(city);
                        if (point) setCursor(point);
                    }}
                    onCityActivate={activateCity}
                    onMissing={handleMissing}
                />
                {focus && <CommanderyNavigator commanderies={ready!.commanderies} focus={focus} home={home} onFocus={setFocusNo}
                    overlayInfo
                    visibility={layers.visibility} intelAge={layers.intelAge} scoutable={layers.scoutable}
                    onScout={layers.canScout ? (no) => void layers.sendScout(no) : undefined} scoutPending={layers.scoutPending} />}
                {(ready?.refreshError || strategicError || layers.visibilityError || layers.corpsError || layers.badgeError || layers.scoutError || layers.scoutMessage) &&
                    <div className="map-layer-notices" aria-live="polite">
                        {ready?.refreshError && <p role="status">지도를 갱신하지 못했습니다.</p>}
                        {strategicError && <p role="status">{strategicError}</p>}
                        {layers.visibilityError && <p role="status">시야를 불러오지 못해 안개 레이어를 비웠습니다.</p>}
                        {layers.corpsError && <p role="status">군단을 불러오지 못해 군단 레이어를 비웠습니다.</p>}
                        {layers.badgeError && <p role="status">공사·포위 정보를 불러오지 못해 해당 배지를 비웠습니다.</p>}
                        {layers.scoutError && <p role="status">첩보 대상 정보를 불러오지 못했습니다.</p>}
                        {layers.scoutMessage && <p role="status">{layers.scoutMessage}</p>}
                    </div>}
                <div className="map-btn-stack">
                    <button type="button" className={`map-toggle-cityname${hideCityNames ? ' active' : ''}`} aria-pressed={hideCityNames} onClick={toggleCityNames}>도시명 표기</button>
                    {touchDevice && <button type="button" className={`map-toggle-singletap${singleTap ? ' active' : ''}`} aria-pressed={singleTap} onClick={toggleSingleTap}>두번 탭 해 도시 이동</button>}
                </div>
            </div>
            {hoverCounty && (
                <div className="map-tooltip" role="status" style={{ left: cursor.x + 12, top: cursor.y + 30 }}>
                    <div className="map-tooltip-name">
                        {/* 같은 郡 안 同音 縣이면 작은 漢字 병기(「양성현 陽城」, #838). */}
                        <PlaceNameWithGloss
                            name={hoverCounty.displayName ?? `${hoverCounty.commanderyName} ${hoverCounty.countyName}`}
                            gloss={hoverCounty.countyGloss}
                        />
                    </div>
                    {hoverMeta && <div className="map-tooltip-meta">{hoverMeta}</div>}
                    {hoverCity?.cityBadges?.map((badge, index) => (
                        <div className="map-tooltip-meta" key={`${badge.kind}-${index}`}>{cityBadgeLabel(badge)}</div>
                    ))}
                </div>
            )}
        </section>
    );
}
