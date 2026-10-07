'use client';

// 로비·로그인 · 가입 화면의 지도. 미리보기를 먼저 받고, 서버가 topdownBakeId를 주면 지도(TopdownMapPreview — 따로 받는 묶음)를,
// 안 주면 「지도를 준비 중입니다」 안내 칸을 그린다(D113 — 옛 지도판은 지웠다, M2-9).
import type { StrategicTopologyBinding } from '@opensamguk/ui';
import { lazy, Suspense, useEffect, useState } from 'react';
import { MapPreviewFailed, MapPreviewLoading, MapPreviewPreparing, mapPreviewRootClass } from './mapPreviewParts';

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
    supplyReason?: { code: string; label: string; year: number; month: number; phase: number } | null;
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
    /** 지도 bake(64자리). game-api가 bake와 지금 세계의 지문이 맞을 때만 준다 — 없으면 「지도를 준비 중입니다」. */
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
    /** 지도 위에 떠 있는 고정 판(CSS 선택자) — 지도 이름표가 그 밑에 숨지 않게 피한다(K10 실지도 10-03). */
    avoidSelector?: string;
    /** 지도 조작 묶음 — `none`(가입) · `names`(기본, 로비) · `zoom`(로그인: + · − · 이름). */
    controls?: 'none' | 'names' | 'zoom';
    /** 지도 조작 묶음을 데스크톱에서 내보낼 자리(요소 id) — 로그인 카드 아래(D41). */
    controlsHostId?: string;
    /** 받은 미리보기를 옆 패널(세력 현황 · 천하 정세 이름 풀이)과 나눈다 — 같은 자료를 두 번 부르지 않는다. */
    onPreview?: (data: MapData) => void;
    onPreviewError?: () => void;
}

// 첫 그림(M1-5): 지도 코드 묶음은 미리보기를 받은 뒤가 아니라 이 모듈이 평가될 때 함께 받기 시작한다.
// 미리보기 → 묶음 → bake 가 직렬이라 로그인 첫 그림이 그만큼 늦었다(10-05 로컬 측정). 묶음은 lazy 그대로 같은 약속을 쓴다.
// 지도 렌더러는 이 파일에서 바로 가져오지 않는다 — 공용 패키지에 sideEffects 표시가 없어 로그인 첫 묶음이 커진다.
const topdownModule = typeof window !== 'undefined' ? import('./TopdownMapPreview') : null;
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

type Loaded = { kind: 'loading' } | { kind: 'error' } | { kind: 'ready'; data: MapData };

/** 미리보기를 한 번 받아 지도를 그린다. bakeId가 없으면 안내 칸이다. */
export default function MapPreview(props: MapPreviewProps = {}) {
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
    if (!loaded.data.topdownBakeId) return <MapPreviewPreparing rootClass={rootClass} />;
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
            />
        </Suspense>
    );
}
