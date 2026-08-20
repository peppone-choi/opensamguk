'use client';

// 지도 하단 중원정세 섹션: devsam PageCachedMap.vue `cachedMap.history[]` v-html 패턴 대응.
// world-log/page.tsx 와 동일한 렌더 인프라(api.worldLog, GameCard, dangerouslySetInnerHTML)를 재사용.

import { useCallback, useEffect, useState } from 'react';
import Shell from '../../../components/Shell';
import MapViewer from '../../../components/game/MapViewer';
import HanMapCanvas from '../../../components/game/HanMapCanvas';
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
    const [mapName, setMapName] = useState<string | null>(null);
    const [mapError, setMapError] = useState<string | null>(null);
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

    useEffect(() => {
        let active = true;
        api.gameConst()
            .then((result) => {
                if (!active) return;
                setMapName(result.mapName);
                setMapError(null);
            })
            .catch(() => {
                if (active) setMapError('지도 설정을 확인할 수 없습니다.');
            });
        return () => { active = false; };
    }, []);

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
                {mapError && <p role="alert" style={{ color: 'var(--crimson)' }}>{mapError}</p>}
                {!mapError && mapName === null && <p className="text-muted">지도 설정을 불러오는 중입니다.</p>}
                {!mapError && mapName === 'han' && (
                    <HanMapCanvas onMissing={() => setMapError('후한 군현 지도 데이터를 불러올 수 없습니다.')} />
                )}
                {!mapError && mapName !== null && mapName !== 'han' && (
                    <>
                        <p className="text-muted">도시를 클릭하면 해당 도시 정보를 볼 수 있습니다.</p>
                        <MapViewer refreshKey={mapRefreshKey} />
                    </>
                )}

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
