'use client';

// 본관 현 지도 고르기(P-E02, 설계 §2.3 「본관 현 — 지도 대상 고르기」, 보드 V31K5MCreate1).
// 목록(TargetCandidateList)과 같은 picker 를 받아 지도 위 표지(MapTargetLayer)로도 고른다 — 한쪽에서 고르면 다른 쪽도 고른다.
//  - 표지는 군 보기 이상에서만 그린다. 주 보기(천하 전체)는 현 1천여 곳이 겹치므로 「확대하거나 주 · 군을 고르라」는 줄만 둔다.
//  - focus 가 바뀌면(목록에서 고름 · 주 · 군 거르기) 그 칸이 화면 밖이거나 주 보기일 때만 군 보기로 옮긴다.
//    지도에서 누른 표지는 이미 화면 안이라 지도가 움직이지 않는다.
//  - 고를 수 없는 표지를 누르면 고르지 않고 사유를 지도 아래 줄로 보인다(목록과 같은 사유).
import { useEffect, useMemo, useRef, useState } from 'react';
import type { TargetCandidate, TargetPicker } from '@opensamguk/ui';
import {
    HAN_MAP_SHAPE,
    MapTargetLayer,
    TopdownMap,
    cellToScreen,
    levelZoom,
    loadBakePlaces,
    viewLevel,
    worldFromPreview,
    type Camera,
    type CellPoint,
    type PlacesData,
    type TopdownMapHandle,
    type TopdownSource,
} from '@opensamguk/ui/map/topdown';
import type { MapPreviewResponse } from '@/lib/types';
import styles from './creation.module.css';

/** 표지가 반쯤 걸려도 화면 밖으로 센다(MapTargetLayer 표지 44 의 절반). */
const EDGE = 22;

export interface CountyMapProps {
    readonly source: TopdownSource;
    readonly preview: MapPreviewResponse;
    readonly candidates: readonly TargetCandidate[];
    readonly picker: TargetPicker;
    /** 옮겨 볼 칸 — 고른 현 또는 거른 주 · 군 후보의 가운데. null 이면 처음 맞춤(천하 전체) 그대로. */
    readonly focus: CellPoint | null;
    readonly selectedCityId: number | null;
}

export default function CountyMap({ source, preview, candidates, picker, focus, selectedCityId }: CountyMapProps) {
    const [places, setPlaces] = useState<PlacesData | null>(null);
    const [camera, setCamera] = useState<Camera | null>(null);
    const [blocked, setBlocked] = useState<TargetCandidate | null>(null);
    const handle = useRef<TopdownMapHandle | null>(null);
    const box = useRef<HTMLDivElement>(null);
    const cameraRef = useRef<Camera | null>(null);
    cameraRef.current = camera;

    // 세력색은 장소 표의 구역 수가 있어야 칠한다(기록 지도와 같은 순서). 못 읽으면 색 없이 지형만 그린다.
    useEffect(() => {
        let cancelled = false;
        loadBakePlaces(source).then(
            (next) => { if (!cancelled) setPlaces(next); },
            (error: unknown) => { if (!cancelled) console.warn('[본관 지도] 장소 표', error); },
        );
        return () => { cancelled = true; };
    }, [source.bakeUrl, source.kitUrl]); // eslint-disable-line react-hooks/exhaustive-deps
    const world = useMemo(() => (places ? worldFromPreview(preview, places.provinceCount) : null), [places, preview]);

    const moveTo = (target: CellPoint) => {
        const map = handle.current;
        const rect = box.current?.getBoundingClientRect();
        if (!map || !rect || rect.width === 0) return;
        const viewport = { width: rect.width, height: rect.height, dpr: 1 };
        const cam = cameraRef.current;
        const commandery = levelZoom('commandery', viewport, HAN_MAP_SHAPE);
        if (cam && viewLevel(cam.zoom) !== 'ju') {
            const at = cellToScreen(target, cam, viewport);
            if (at.x >= EDGE && at.x <= rect.width - EDGE && at.y >= EDGE && at.y <= rect.height - EDGE) return;
        }
        map.centerOn(target, Math.max(cam?.zoom ?? 0, commandery));
    };
    useEffect(() => {
        if (focus) moveTo(focus);
    }, [focus?.col, focus?.row]); // eslint-disable-line react-hooks/exhaustive-deps

    const zoomedOut = camera === null || viewLevel(camera.zoom) === 'ju';
    return (
        <div className={styles.countyMapWrap}>
            <div ref={box} className={styles.countyMap}>
                <TopdownMap
                    source={source}
                    world={world?.ok ? world.world : undefined}
                    initialView="fit"
                    selectedCityId={selectedCityId}
                    onReady={(next) => { handle.current = next; if (focus) moveTo(focus); }}
                    onViewChange={({ camera: next }) => setCamera(next)}
                    ariaLabel="본관 현 지도 — 방향키로 옮기고 + · − 로 확대합니다"
                    style={{ position: 'absolute', inset: 0 }}
                />
                {zoomedOut ? null : (
                    <MapTargetLayer camera={camera} candidates={candidates} picker={picker}
                        onBlocked={(candidate) => setBlocked(candidate)} />
                )}
            </div>
            {zoomedOut ? <p className={styles.wait}>지도를 확대하거나 주 · 군을 고르면 현 표지가 나옵니다.</p> : null}
            {blocked ? (
                <p role="status" className={styles.errLine}>{blocked.name} — {blocked.reason ?? '고를 수 없는 현입니다'}</p>
            ) : null}
        </div>
    );
}
