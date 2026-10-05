'use client';

// 연감 연말 판도 지도(P-H02 보강, 계약판 소비 안 K5-WAIT-04) — 그해 말 소유(ownership)를 탑다운 지도에 칠한다.
//  - 그해 구역 번호는 그해 지도 판(mapPin)을 가리킨다. 지금 지도 판(미리보기 bakeId)과 같을 때만 칠하고, 다르면 그릴 수 없다고 말한다
//    (엉뚱한 구역을 칠하지 않는다). 구역 수 · 번호가 판과 맞지 않아도 칠하지 않는다.
//  - 지금 소유(지도 미리보기)로 그해 판도를 그리지 않는다. 세력 이름 · 색은 그해 판도 표(territory)의 것이다.
//  - 지도는 천하 전체 맞춤으로 두고 끌기 · 휠 · 핀치는 그대로 된다.
import { useEffect, useMemo, useState } from 'react';
import { StatusView } from '@opensamguk/ui';
import {
    TopdownMap,
    loadBakePlaces,
    topdownScreensEnabled,
    topdownSourceFor,
    worldFromPreview,
    type PlacesData,
    type TopdownSource,
} from '@opensamguk/ui/map/topdown';
import type { YearbookOwnership, YearbookTerritory } from '@/lib/yearbook-contract';
import { yearEndPreview } from '@/lib/yearbook-view';

export interface YearbookMapProps {
    readonly year: number;
    readonly ownership: YearbookOwnership;
    readonly territory: readonly YearbookTerritory[];
}

/** `currentPin` — 지금 지도 판(미리보기 topdownBakeId). undefined 는 아직 미리보기를 받는 중. */
export default function YearbookMap({ currentPin, ...props }: YearbookMapProps & { readonly currentPin: string | null | undefined }) {
    const { year, ownership } = props;
    const source = useMemo(() => (topdownScreensEnabled() ? topdownSourceFor(ownership.mapPin) : null), [ownership.mapPin]);
    if (currentPin === undefined) return <StatusView kind="loading" rows={3} />;
    if (source && currentPin !== ownership.mapPin) {
        return <StatusView kind="empty" title={`${year}년 지도 판이 지금과 달라 판도를 그릴 수 없습니다`}
            body="그해 구역 번호를 지금 지도에 칠하면 엉뚱한 곳이 칠해집니다. 옆의 판도 표는 그대로 맞습니다." />;
    }
    if (!source) {
        return <StatusView kind="empty" title={`${year}년 말 판도 지도를 그릴 수 없습니다`}
            body="이 화면이 그해 지도 판을 읽지 못합니다. 옆의 판도 표는 그대로 맞습니다." />;
    }
    return <YearEndTopdown {...props} source={source} />;
}

function YearEndTopdown({ year, source, ownership, territory }: YearbookMapProps & { readonly source: TopdownSource }) {
    const [places, setPlaces] = useState<PlacesData | null>(null);
    const [failed, setFailed] = useState(false);
    const [seq, setSeq] = useState(0);

    useEffect(() => {
        let cancelled = false;
        setPlaces(null);
        setFailed(false);
        loadBakePlaces(source).then(
            (next) => { if (!cancelled) setPlaces(next); },
            (error: unknown) => {
                if (cancelled) return;
                console.warn('[연감 지도] 장소 표', error);
                setFailed(true);
            },
        );
        return () => { cancelled = true; };
    }, [source.bakeUrl, source.kitUrl, seq]); // eslint-disable-line react-hooks/exhaustive-deps

    const world = useMemo(
        () => (places ? worldFromPreview(yearEndPreview(ownership, territory), places.provinceCount) : null),
        [places, ownership, territory],
    );
    if (failed) return <StatusView kind="error" title="지도 장소를 불러오지 못했습니다" onRetry={() => setSeq((n) => n + 1)} />;
    if (world && !world.ok) {
        return <StatusView kind="empty" title={`${year}년 말 판도를 지도에 칠하지 못했습니다`}
            body={`${world.reason}. 지금 소유로 대신 칠하지 않습니다. 옆의 판도 표는 그대로 맞습니다.`} />;
    }
    return (
        <TopdownMap
            source={source}
            world={world?.ok ? world.world : undefined}
            initialView="fit"
            ariaLabel={`${year}년 말 판도 지도`}
            style={{ width: '100%', height: '100%' }}
        />
    );
}
