'use client';

// 인게임 세계 지도 페이지(W9) — v_cachedMap.php → Next 커버. 로비 MapPreview(좌표·도시점)와 달리 fog
// (정찰 spyList / 아국 장수 소재 shownByGeneralList / myCity / myNation)를 GET /api/map(WorldMapController)에서
// 받아 MapViewer 위에 오버레이한다. 좌표(x/y)는 /api/map엔 없으므로 MapViewer가 /api/map/preview에서 가져오고,
// 이 페이지는 fog만 id로 머지해 prop으로 내린다. read 전용(mutation 없음).

import { useEffect, useState } from 'react';
import Shell from '../../../components/Shell';
import MapViewer from '../../../components/game/MapViewer';
import { useFrontInfo } from '../../../hooks/useFrontInfo';
import { api } from '../../../lib/api';
import type { WorldMapResponse } from '../../../lib/types';

export default function GameMapPage() {
    const { frontInfo } = useFrontInfo();
    const [fog, setFog] = useState<WorldMapResponse | null>(null);

    useEffect(() => {
        let on = true;
        // showMe=1: 내 장수 소재 도시(myCity) 노출. neutralView=0: 내 국가 정보 포함.
        api.worldMap(0, 1)
            .then((d) => {
                if (on) setFog(d);
            })
            .catch(() => {
                // fog 실패는 치명적이지 않음 — MapViewer는 좌표만으로도 렌더된다.
                if (on) setFog(null);
            });
        return () => {
            on = false;
        };
    }, []);

    const currentCityId = frontInfo?.general?.hasGeneral ? frontInfo.general.cityId : null;
    const shownByGeneralCityIds = fog ? new Set(fog.shownByGeneralList) : undefined;
    const spyCityRemain = fog?.spyList;

    return (
        <Shell>
            <div className="page-content">
                <h1>세계 지도</h1>
                <p className="text-muted">
                    내 장수가 있는 도시는 금색 테두리, 정찰 중인 도시는 잔여 개월 배지로 표시됩니다.
                </p>
                <MapViewer
                    currentCityId={currentCityId}
                    shownByGeneralCityIds={shownByGeneralCityIds}
                    spyCityRemain={spyCityRemain}
                />
            </div>
        </Shell>
    );
}
