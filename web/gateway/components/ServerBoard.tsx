'use client';

import { useState } from 'react';
import MapPreview from './MapPreview';
import ServerLog from './ServerLog';
import serversData from '../config/servers.json';

/**
 * 서버 보드 — devsam '제 전황' 입구(로그인+로비 공용). 상단 서버 전환 탭 + 선택 서버의 세계지도 현황
 * (아이콘/깃발 정적 마커) + 그 아래 전황 로그. 서버 목록은 데이터 주도(config/servers.json, 배포 시 편집).
 *
 * 탭 클릭 → 선택 서버 전환(맵+로그 동시 교체). 서버 1개여도 탭 1개로 일관 렌더(devsam 형태 유지).
 */

interface ServerEntry {
    id: string;
    name: string;
    status?: string;
    gameApiUrl?: string;
}
const SERVERS = serversData.servers as ServerEntry[];

export default function ServerBoard() {
    const [selectedId, setSelectedId] = useState<string | null>(SERVERS[0]?.id ?? null);
    const selected = selectedId ? (SERVERS.find((s) => s.id === selectedId) ?? SERVERS[0]) : null;

    // 서버는 관리자 생성 런타임 데이터다. 서버가 없으면 로그인/로비에서 맵·로그·서버탭을 만들지 않는다.
    if (!selected) return null;

    return (
        <section className="server-board" aria-label="서버 현황">
            {/* 상단 서버 전환 탭 */}
            <div className="server-tabs" role="tablist" aria-label="서버 선택">
                {SERVERS.map((s) => (
                    <button
                        key={s.id}
                        type="button"
                        role="tab"
                        aria-selected={s.id === selectedId}
                        className={`server-tab${s.id === selectedId ? ' is-active' : ''}`}
                        onClick={() => setSelectedId(s.id)}
                    >
                        {s.name}
                        {s.status && s.status !== 'running' && (
                            <span className="server-tab-badge">{s.status === 'closed' ? '폐쇄' : '준비'}</span>
                        )}
                    </button>
                ))}
            </div>

            {/* 선택 서버 현황: 세계지도(아이콘/깃발) + 전황 로그 */}
            <MapPreview serverId={selected.id} serverName={selected.name} />
            <ServerLog serverId={selected.id} />
        </section>
    );
}
