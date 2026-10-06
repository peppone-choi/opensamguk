'use client';

// 봉신 계약 상세의 봉토 지도(보드 V31K8Vassals 460×260). K2 #1400 읽기 전용 표지 — 봉토 현을 차례 번호로 강조하고 누를 것은 없다.
// 새 지도 교체 스위치와 서버 bakeId가 둘 다 있을 때만 그린다. 아니면 아무것도 그리지 않는다 — 봉토 현 이름 줄이 그대로 남는다.
// 표지 층은 aria-hidden 이라 이름은 상세의 「봉토 현 N곳 — …」 글이 읽힌다.
import { useEffect, useMemo, useState } from 'react';
import type { TargetCandidate } from '@opensamguk/ui';
import {
    MapTargetLayer,
    TopdownMap,
    cityCell,
    loadBakePlaces,
    topdownScreensEnabled,
    topdownSourceFor,
    worldFromPreview,
    type Camera,
    type PlacesData,
    type TopdownSource,
} from '@opensamguk/ui/map/topdown';
import type { MapPreviewResponse } from '@/lib/types';
import styles from './offices.module.css';

interface FiefMapProps {
    readonly preview: MapPreviewResponse;
    readonly countyIds: readonly number[];
    readonly countyName: (countyId: number) => string | null;
    readonly label: string;
}

export default function FiefMap(props: FiefMapProps) {
    const bakeId = props.preview.topdownBakeId;
    const source = useMemo(() => (topdownScreensEnabled() ? topdownSourceFor(bakeId) : null), [bakeId]);
    if (!source || props.countyIds.length === 0) return null;
    return <FiefTopdownMap source={source} {...props} />;
}

function FiefTopdownMap({ source, preview, countyIds, countyName, label }: FiefMapProps & { readonly source: TopdownSource }) {
    const [places, setPlaces] = useState<PlacesData | null>(null);
    const [failed, setFailed] = useState(false);
    const [camera, setCamera] = useState<Camera | null>(null);

    useEffect(() => {
        let cancelled = false;
        setPlaces(null);
        setFailed(false);
        loadBakePlaces(source).then(
            (next) => { if (!cancelled) setPlaces(next); },
            (error: unknown) => {
                if (cancelled) return;
                console.warn('[봉토 지도] 장소 표', error);
                setFailed(true);
            },
        );
        return () => { cancelled = true; };
    }, [source.bakeUrl, source.kitUrl]); // eslint-disable-line react-hooks/exhaustive-deps

    const world = useMemo(() => (places ? worldFromPreview(preview, places.provinceCount) : null), [places, preview]);
    // 표지 칸은 정수 칸(발자국 가운데의 내림) — MapTargetLayer 가 +0.5 로 칸 가운데에 둔다(K2 #1400).
    const fiefs = useMemo<readonly TargetCandidate[]>(() => (places ? countyIds.flatMap((id) => {
        const at = cityCell(places, id);
        return at ? [{ targetKind: 'place' as const, targetId: String(id), cityId: String(id), available: true,
            cell: { col: Math.floor(at.col), row: Math.floor(at.row) }, name: countyName(id) ?? '어느 현' }] : [];
    }) : []), [places, countyIds, countyName]);
    const cells = useMemo(() => fiefs.flatMap((f) => (f.cell ? [f.cell] : [])), [fiefs]);

    if (failed) return <p className={styles.fiefMapNote} role="alert">봉토 지도의 장소를 불러오지 못했습니다. 잠시 뒤 다시 열어 주세요.</p>;
    if (!places) return <div className={styles.fiefMap} aria-busy="true" />;
    if (cells.length === 0) return <p className={styles.fiefMapNote}>봉토 현이 이 지도에 없습니다.</p>;
    return (
        <div className={styles.fiefMap} data-fief-map={fiefs.map((f) => f.targetId).join(' ')}>
            <TopdownMap
                source={source}
                world={world?.ok ? world.world : undefined}
                initialView={{ cells }}
                onViewChange={(view) => setCamera(view.camera)}
                ariaLabel={`${label} — 봉토 지도`}
                style={{ width: '100%', height: '100%' }}
            />
            <MapTargetLayer readOnly marked={fiefs.map((f) => f.targetId)} camera={camera} candidates={fiefs} />
        </div>
    );
}
