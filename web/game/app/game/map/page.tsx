'use client';

// 인게임 세계 지도 페이지 — v_cachedMap.php → Next 커버. 로비 MapPreview와 동일한 정적 마커 맵을 렌더한다.
// 좌표·도시점은 MapViewer가 game-api `/api/map/preview`에서 직접 가져온다. read 전용(mutation 없음).
// 도시 마커 클릭 = 해당 도시 정보 페이지 이동(MapViewer의 유일한 인터랙션) — 로비와 픽셀 단위 동일.
//
// 지도 하단 중원정세 섹션: devsam PageCachedMap.vue `cachedMap.history[]` v-html 패턴 대응.
// world-log/page.tsx 와 동일한 렌더 인프라(api.worldLog, GameCard, dangerouslySetInnerHTML)를 재사용.

import { useCallback, useEffect, useState } from 'react';
import Shell from '../../../components/Shell';
import MapViewer from '../../../components/game/MapViewer';
import GameCard from '../../../components/GameCard';
import { api } from '../../../lib/api';
import type { WorldLogResponse } from '../../../lib/api';
import { useTurnRefresh } from '../../../hooks/useTurnRefresh';

// ── 중원정세 섹션바 — world-log/page.tsx sectionBarStyle과 동일 ──────────────
const sectionBarStyle: React.CSSProperties = {
    textAlign: 'center',
    border: '0.5px solid var(--border-medium)',
    background: 'var(--bg-elevated)',
    padding: 'var(--space-xs) var(--space-sm)',
    fontWeight: 600,
    marginTop: 'var(--space-md)',
    marginBottom: 0,
};

// ── 로그 행 — world-log/page.tsx logRowStyle과 동일 ─────────────────────────
const logRowStyle: React.CSSProperties = {
    padding: 'var(--space-xs) 0',
    borderBottom: '0.5px solid var(--border-subtle)',
    display: 'flex',
    gap: 'var(--space-sm)',
};

export default function GameMapPage() {
    const [logData, setLogData] = useState<WorldLogResponse | null>(null);
    const [logLoading, setLogLoading] = useState(true);
    const [logError, setLogError] = useState<string | null>(null);

    // background=true(턴 갱신)면 로딩 문구를 다시 띄우지 않는다(OPENSAM-196).
    const fetchLog = useCallback(async (background = false) => {
        if (!background) setLogLoading(true);
        try {
            const result = await api.worldLog();
            setLogData(result);
            setLogError(null);
        } catch {
            setLogError('전황 데이터를 불러올 수 없습니다.');
        } finally {
            if (!background) setLogLoading(false);
        }
    }, []);

    useEffect(() => {
        fetchLog();
    }, [fetchLog]);

    // MapViewer는 refreshKey prop 변경 시 조용히 자체 재조회한다(리마운트 아님) — OPENSAM-196.
    const [mapRefreshKey, setMapRefreshKey] = useState(0);
    useTurnRefresh(() => {
        fetchLog(true);
        setMapRefreshKey((k) => k + 1);
    });

    const entries = logData?.entries ?? [];

    return (
        <Shell>
            <div className="page-content">
                <h1>세계 지도</h1>
                <p className="text-muted">도시를 클릭하면 해당 도시 정보를 볼 수 있습니다.</p>
                <MapViewer refreshKey={mapRefreshKey} />

                {/* ── 중원정세 — devsam PageCachedMap.vue cachedMap.history[] v-html 대응 ── */}
                <div style={sectionBarStyle}>중원 정세</div>
                <GameCard>
                    {logLoading && <p style={{ color: 'var(--text-muted)', margin: 0 }}>로딩 중...</p>}
                    {logError && <p style={{ color: 'var(--crimson)', margin: 0 }}>{logError}</p>}
                    {!logLoading && !logError && (
                        entries.length === 0 ? (
                            <p style={{ color: 'var(--text-muted)', textAlign: 'center', margin: 0 }}>기록이 없습니다.</p>
                        ) : (
                            <div>
                                {entries.map((item) => (
                                    <div key={item.id} style={logRowStyle}>
                                        {/* text는 서버 패러티 로그 원문(색/태그) — devsam v-html="formatLog(item)" 동일 패턴 */}
                                        <span style={{ flex: '1 1 auto' }} dangerouslySetInnerHTML={{ __html: item.text }} />
                                    </div>
                                ))}
                            </div>
                        )
                    )}
                </GameCard>
            </div>
        </Shell>
    );
}
