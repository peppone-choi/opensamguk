'use client';

import { useEffect, useState } from 'react';
import MapPreview from './MapPreview';
import ServerLog from './ServerLog';

/**
 * 서버 보드 — devsam '제 전황' 입구(로그인+로비 공용). 상단 서버 전환 탭 + 선택 서버의 세계지도 현황
 * (아이콘/깃발 정적 마커) + 그 아래 전황 로그. 서버 목록은 관리자 생성 서버 라우팅 config에서 온다.
 *
 * 탭 클릭 → 선택 서버 전환(맵+로그 동시 교체). 서버 1개여도 탭 1개로 일관 렌더(devsam 형태 유지).
 */

interface ServerEntry {
    id: string;
    name: string;
    generation?: number;
}
export default function ServerBoard() {
    const [servers, setServers] = useState<ServerEntry[]>([]);
    const [selectedId, setSelectedId] = useState<string | null>(null);

    useEffect(() => {
        let alive = true;
        fetch('/api/servers', { cache: 'no-store' })
            .then((r) => (r.ok ? r.json() : Promise.reject(new Error(String(r.status)))))
            .then((data: { servers?: ServerEntry[] }) => {
                if (!alive) return;
                const nextServers = data.servers ?? [];
                setServers(nextServers);
                setSelectedId((current) => current ?? nextServers[0]?.id ?? null);
            })
            .catch(() => {
                if (!alive) return;
                setServers([]);
                setSelectedId(null);
            });
        return () => {
            alive = false;
        };
    }, []);

    const selected = selectedId ? (servers.find((s) => s.id === selectedId) ?? servers[0]) : null;

    // 서버는 관리자 생성 런타임 데이터다. 서버가 없으면 로그인/로비에서 맵·로그·서버탭을 만들지 않는다.
    if (!selected) return null;

    return (
        <section className="server-board" aria-label="서버 현황">
            {/* 상단 서버 전환 탭 */}
            <div className="server-tabs" role="tablist" aria-label="서버 선택">
                {servers.map((s) => (
                    <button
                        key={s.id}
                        type="button"
                        role="tab"
                        aria-selected={s.id === selectedId}
                        className={`server-tab${s.id === selectedId ? ' is-active' : ''}`}
                        onClick={() => setSelectedId(s.id)}
                    >
                        {s.name}
                        {s.generation != null && <span className="status-badge status-gold">{s.generation}기</span>}
                    </button>
                ))}
            </div>

            {/* 선택 서버 현황: 세계지도(아이콘/깃발) + 전황 로그 */}
            <MapPreview serverId={selected.id} serverName={selected.name} />
            <ServerLog serverId={selected.id} />
        </section>
    );
}
