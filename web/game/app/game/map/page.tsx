'use client';

// 인게임 세계 지도 페이지 — v_cachedMap.php → Next 커버. 로비 MapPreview와 동일한 정적 마커 맵을 렌더한다.
// 좌표·도시점은 MapViewer가 game-api `/api/map/preview`에서 직접 가져온다. read 전용(mutation 없음).
// 도시 마커 클릭 = 해당 도시 정보 페이지 이동(MapViewer의 유일한 인터랙션) — 로비와 픽셀 단위 동일.

import Shell from '../../../components/Shell';
import MapViewer from '../../../components/game/MapViewer';

export default function GameMapPage() {
    return (
        <Shell>
            <div className="page-content">
                <h1>세계 지도</h1>
                <p className="text-muted">도시를 클릭하면 해당 도시 정보를 볼 수 있습니다.</p>
                <MapViewer />
            </div>
        </Shell>
    );
}
