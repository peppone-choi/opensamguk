'use client';

// 로그인 · 가입 · 로비 지도 미리보기의 새 지도(탑다운). MapPreview가 교체 스위치 빌드에서 서버가 topdownBakeId를 줄 때만
// 따로 받는 묶음으로 부른다. 처음엔 천하 전체(州 보기)를 보이고, 세력색은 preview의 구역 점유, 城 이름표는 옛 지도판과 같은 글자다.
// bake는 게이트웨이 게임 프록시(/api/game/…?server=<id>)로 받는다 — 로그인 없이 열린다.
import { buildWorldCities } from '@opensamguk/ui';
import {
    DEFAULT_LAYERS,
    TOPDOWN_MAP_NOTICE,
    TopdownMap,
    cityCell,
    loadBakePlaces,
    topdownSourceFor,
    worldFromPreview,
    type HitResult,
    type MapLayers,
    type MyLocation,
    type PlacesData,
    type TopdownMapStatus,
} from '@opensamguk/ui/map/topdown';
import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { previewCaption } from '@/lib/serverStatus';
import type { MapData } from './MapPreview';
import { CityTooltip, NameToggle, mapPreviewRootClass, useHideCityNames } from './mapPreviewParts';


export interface TopdownMapPreviewProps {
    readonly data: MapData;
    readonly serverId: string;
    readonly serverName?: string;
    readonly currentCityId: number | null;
    readonly variant: 'panel' | 'backdrop';
    /** bake 번호가 형식에 맞지 않으면 대신 그릴 옛 지도판. */
    readonly fallback: ReactNode;
}

export default function TopdownMapPreview({ data, serverId, serverName, currentCityId, variant, fallback }: TopdownMapPreviewProps) {
    const source = useMemo(() => topdownSourceFor(data.topdownBakeId, serverId), [data.topdownBakeId, serverId]);
    const [places, setPlaces] = useState<PlacesData | null>(null);
    const [placesFailed, setPlacesFailed] = useState(false);
    const [picked, setPicked] = useState<HitResult | null>(null);
    const [mapStatus, setMapStatus] = useState<TopdownMapStatus>('loading');
    const [hideCityName, toggleCityNames] = useHideCityNames();

    useEffect(() => {
        if (!source) return undefined;
        let cancelled = false;
        setPlaces(null);
        setPlacesFailed(false);
        loadBakePlaces(source).then(
            (next) => { if (!cancelled) setPlaces(next); },
            (error: unknown) => {
                if (cancelled) return;
                console.warn('[지도 미리보기 새 지도] 장소 표', error);
                setPlacesFailed(true);
            },
        );
        return () => { cancelled = true; };
    }, [source]);

    const world = useMemo(() => (places ? worldFromPreview(data, places.provinceCount) : null), [places, data]);
    useEffect(() => {
        if (world && !world.ok) console.warn('[지도 미리보기 새 지도] 세력색', world.reason);
    }, [world]);
    const layers = useMemo<MapLayers>(() => ({ ...DEFAULT_LAYERS, cityNames: !hideCityName }), [hideCityName]);
    // 누른 城 이름표는 옛 지도판과 같은 자료(세력 · 수도 · 상태 · 나루)로 만든다.
    const cities = useMemo(() => buildWorldCities(data), [data]);
    const me = useMemo<MyLocation | null>(() => {
        if (!places || currentCityId == null) return null;
        const cell = cityCell(places, currentCityId);
        const city = data.cities.find((entry) => entry.id === currentCityId);
        if (!cell || !city) return null;
        const nation = data.nations.find((entry) => entry.id === city.nationId);
        return { cell, state: 'IN_CITY', nationColor: nation?.color ?? null, portrait: null, name: city.name };
    }, [places, currentCityId, data]);

    if (!source) return <>{fallback}</>;

    // 내 위치 표지를 누르면 내 城, 城 · 깃발을 누르면 그 城. 빈 땅을 누르면 이름표를 거둔다.
    const pickedId = picked?.kind === 'me' ? currentCityId : picked?.kind === 'city' || picked?.kind === 'flag' ? picked.id : null;
    const shown = pickedId != null ? cities.find((city) => String(city.id) === String(pickedId)) : undefined;
    const backdrop = variant === 'backdrop';
    const label = serverName ?? data.serverName;
    // 안내가 있으면 이름표 대신 그 자리에 하나만 띄운다(그릴 수 없음 · 불러오지 못함이면 누를 城도 없다).
    const notice = mapStatus === 'unsupported' ? { role: 'status', text: TOPDOWN_MAP_NOTICE.unsupported }
        : mapStatus === 'error' ? { role: 'alert', text: TOPDOWN_MAP_NOTICE.error }
            : placesFailed ? { role: 'alert', text: '지도 장소를 불러오지 못했습니다. 잠시 뒤 다시 열어 주세요.' }
                : world && !world.ok ? { role: 'alert', text: '세력 색을 칠하지 못했습니다. 지도 자료가 서버와 맞지 않습니다.' }
                    : null;

    return (
        <div className={mapPreviewRootClass(backdrop, hideCityName)} aria-label="서버 지도">
            <div className="map-preview-canvas">
                <TopdownMap
                    className="map-preview-han"
                    source={source}
                    world={world?.ok ? world.world : undefined}
                    layers={layers}
                    me={me}
                    initialView="fit"
                    onSelect={setPicked}
                    notices={false}
                    onStatus={setMapStatus}
                    ariaLabel={`${label} 서버 지도 — 방향키로 옮기고 + · − 로 확대합니다`}
                />
                <NameToggle hidden={hideCityName} onToggle={toggleCityNames} />
                {/* 안내문은 누른 城 이름표 자리에 띄운다 — 로그인 배경은 그 자리를 패널이 가리지 않는 빈 칸에 두고(gateway-v31.css),
                    지도 아래 끝은 데스크톱 · 모바일 모두 패널 밑이다. 줄 바꿈 · 폭은 .map-preview-notice(globals.css). */}
                {notice
                    ? <div className="map-preview-tooltip map-preview-tooltip--pinned map-preview-notice" role={notice.role}>{notice.text}</div>
                    : shown && <CityTooltip city={shown} />}
            </div>
            {!backdrop && <div className="map-preview-cap">{previewCaption(label, data)}</div>}
        </div>
    );
}
