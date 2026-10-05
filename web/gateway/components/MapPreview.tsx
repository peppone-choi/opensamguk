'use client';

// 로비·로그인 화면의 지도. 작전실과 같은 2D 지도판을 쓴다.
// 제품 화면 교체 스위치(NEXT_PUBLIC_TOPDOWN_SCREENS=1)를 켠 빌드는 미리보기를 먼저 받고, 서버가 topdownBakeId를 주면
// 새 지도(TopdownMapPreview — 따로 받는 묶음)를, 안 주면 받은 미리보기 그대로 옛 지도판을 그린다.
// 스위치가 꺼진 빌드(운영 게이트웨이 이미지에는 이 값이 없다)는 지금처럼 옛 지도판만 그리고 새 지도 묶음을 받지 않는다.
import {
    WorldMapCanvas,
    useWorldMap,
    worldProvincesUrl,
    type IsoCityOverlay,
    type StrategicTopologyBinding,
} from '@opensamguk/ui';
import { lazy, Suspense, useCallback, useEffect, useState } from 'react';
import { previewCaption } from '@/lib/serverStatus';
import { CityTooltip, MapPreviewFailed, MapPreviewLoading, NameToggle, mapPreviewRootClass, useHideCityNames } from './mapPreviewParts';

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
    /** 새 지도 bake(64자리). game-api가 bake와 지금 세계의 지문이 맞을 때만 준다 — 없으면 옛 지도판. */
    topdownBakeId?: string;
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
    /** 지도 위에 떠 있는 고정 판(CSS 선택자) — 새 지도의 이름표가 그 밑에 숨지 않게 피한다(K10 실지도 10-03). 옛 지도판은 쓰지 않는다. */
    avoidSelector?: string;
    /** 새 지도의 조작 묶음 — `none`(가입) · `names`(기본, 로비) · `zoom`(로그인: + · − · 이름). 옛 지도판은 쓰지 않는다. */
    controls?: 'none' | 'names' | 'zoom';
    /** 새 지도 조작 묶음을 데스크톱에서 내보낼 자리(요소 id) — 로그인 카드 아래(D41). */
    controlsHostId?: string;
    /** 받은 미리보기를 옆 패널(세력 현황 · 천하 정세 이름 풀이)과 나눈다 — 같은 자료를 두 번 부르지 않는다. */
    onPreview?: (data: MapData) => void;
    onPreviewError?: () => void;
}

/**
 * 제품 화면 교체 스위치. Next가 빌드 때 박도록 `process.env.NEXT_PUBLIC_…`를 그대로 읽는다.
 * `@opensamguk/ui/map/topdown`의 topdownScreensEnabled와 같은 규칙이지만, 그 묶음을 이 파일에서 가져오면
 * 스위치가 꺼진 빌드에도 렌더러가 로그인 번들에 실린다(공용 패키지에 sideEffects 표시가 없다).
 */
function topdownScreensOn(): boolean {
    return process.env.NEXT_PUBLIC_TOPDOWN_SCREENS === '1';
}

// 첫 그림(M1-5): 새 지도 코드 묶음은 미리보기를 받은 뒤가 아니라 이 모듈이 평가될 때 함께 받기 시작한다(스위치 빌드만).
// 미리보기 → 묶음 → bake 가 직렬이라 로그인 첫 그림이 그만큼 늦었다(10-05 로컬 측정). 묶음은 lazy 그대로 같은 약속을 쓴다.
const topdownModule = topdownScreensOn() && typeof window !== 'undefined' ? import('./TopdownMapPreview') : null;
const TopdownMapPreview = lazy(() => topdownModule ?? import('./TopdownMapPreview'));

async function fetchMapPreview(serverId: string, signal?: AbortSignal): Promise<MapData> {
    const response = await fetch(`/api/server-map/${encodeURIComponent(serverId)}`, { cache: 'no-store', signal });
    if (!response.ok) throw new Error(String(response.status));
    return (await response.json()) as MapData;
}

/**
 * 첫 그림(M1-5): 미리보기 요청을 하이드레이션 전에 시작한다. 받는 중 자리(MapPreviewLoading)가 서버 HTML 에 서버 id 를 싣고
 * (`data-map-preview-server`), 이 모듈이 평가될 때 그 자리를 찾아 바로 받는다. 효과는 같은 약속을 한 번만 이어받는다
 * (다시 받기 · 다른 서버는 지금처럼 새로 받는다). 자리를 못 찾으면(아직 안 그려짐) 아무것도 하지 않는다 — 효과가 지금처럼 받는다.
 * 이 요청은 효과보다 먼저 나가 효과의 중단(signal)을 받지 않는다 — 결과는 효과가 이어받을 때만 쓴다.
 */
const earlyPreviews = new Map<string, Promise<MapData>>();
function startEarlyPreviews(): void {
    if (typeof document === 'undefined') return;
    for (const node of document.querySelectorAll<HTMLElement>('[data-map-preview-server]')) {
        const id = node.dataset.mapPreviewServer;
        if (!id || earlyPreviews.has(id)) continue;
        const early = fetchMapPreview(id);
        early.catch(() => undefined); // 이어받지 않은 실패가 처리 안 된 거부로 남지 않게
        earlyPreviews.set(id, early);
    }
}
startEarlyPreviews();
function takeEarlyPreview(serverId: string): Promise<MapData> | undefined {
    const early = earlyPreviews.get(serverId);
    earlyPreviews.delete(serverId);
    return early;
}

export default function MapPreview(props: MapPreviewProps = {}) {
    return topdownScreensOn() ? <SwitchMapPreview {...props} /> : <IsoMapPreview {...props} />;
}

type Loaded = { kind: 'loading' } | { kind: 'error' } | { kind: 'ready'; data: MapData };

/** 스위치 빌드: 미리보기를 한 번 받아 어느 지도를 그릴지 고른다. 옛 지도판에는 받은 자료를 넘겨 다시 부르지 않는다. */
function SwitchMapPreview(props: MapPreviewProps) {
    const { serverId = 'main', mapData, refreshKey = 0, variant = 'panel', onPreview, onPreviewError } = props;
    const [loaded, setLoaded] = useState<Loaded>(mapData ? { kind: 'ready', data: mapData } : { kind: 'loading' });
    useEffect(() => {
        if (mapData) {
            setLoaded({ kind: 'ready', data: mapData });
            return undefined;
        }
        const controller = new AbortController();
        setLoaded({ kind: 'loading' });
        (takeEarlyPreview(serverId) ?? fetchMapPreview(serverId, controller.signal)).then(
            (data) => {
                if (controller.signal.aborted) return;
                onPreview?.(data);
                setLoaded({ kind: 'ready', data });
            },
            (error: unknown) => {
                if (controller.signal.aborted) return;
                console.warn('[지도 미리보기]', error);
                onPreviewError?.();
                setLoaded({ kind: 'error' });
            },
        );
        return () => controller.abort();
        // onPreview · onPreviewError 는 부르는 쪽이 useCallback 으로 고정한다 — 바뀔 때마다 다시 부르지 않게 의존에서 뺀다.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [serverId, mapData, refreshKey]);

    const rootClass = mapPreviewRootClass(variant === 'backdrop', false);
    // 서버 HTML 의 받는 중 자리에 서버 id 를 싣는다 — 모듈이 평가될 때 미리보기를 먼저 받기 시작한다(startEarlyPreviews)
    if (loaded.kind === 'loading') return <MapPreviewLoading rootClass={rootClass} serverId={mapData ? undefined : serverId} />;
    if (loaded.kind === 'error' || loaded.data.cities.length === 0) return <MapPreviewFailed rootClass={rootClass} />;
    const iso = <IsoMapPreview {...props} mapData={loaded.data} />;
    if (!loaded.data.topdownBakeId) return iso;
    return (
        <Suspense fallback={<MapPreviewLoading rootClass={rootClass} />}>
            <TopdownMapPreview
                data={loaded.data}
                serverId={serverId}
                serverName={props.serverName}
                currentCityId={props.currentCityId ?? null}
                variant={variant}
                avoidSelector={props.avoidSelector}
                controls={props.controls}
                controlsHostId={props.controlsHostId}
                fallback={iso}
            />
        </Suspense>
    );
}

/** 옛 지도판(WorldMapCanvas). 스위치가 꺼진 빌드는 이것만 그린다. */
function IsoMapPreview({
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
}: MapPreviewProps) {
    const loadPreview = useCallback(async (signal: AbortSignal): Promise<MapData> => {
        try {
            const data = await fetchMapPreview(serverId, signal);
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
    const [hideCityName, toggleCityNames] = useHideCityNames();
    const [picked, setPicked] = useState<IsoCityOverlay | null>(null);
    const [hover, setHover] = useState<{ city: IsoCityOverlay; x: number; y: number } | null>(null);
    const backdrop = variant === 'backdrop';
    const rootClass = mapPreviewRootClass(backdrop, hideCityName);

    const handlePickCity = useCallback((city: IsoCityOverlay) => setPicked(city), []);
    const handleHoverCity = useCallback((city: IsoCityOverlay | null, at?: { x: number; y: number }) => {
        setHover(city && at ? { city, x: at.x, y: at.y } : null);
    }, []);

    if (map.kind === 'error' || map.kind === 'unsupported' || (data && data.cities.length === 0)) {
        return <MapPreviewFailed rootClass={rootClass} why={map.kind === 'unsupported' ? `지원하지 않는 지도 판 — ${map.mapCode}` : undefined} />;
    }
    if (!data) return <MapPreviewLoading rootClass={rootClass} />;

    const shown = hover?.city ?? picked;
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
                <NameToggle hidden={hideCityName} onToggle={toggleCityNames} />
                {shown && <CityTooltip city={shown} at={hover} />}
            </div>
            {!backdrop && <div className="map-preview-cap">{previewCaption(serverName ?? data.serverName, data)}</div>}
        </div>
    );
}
