'use client';

import { useEffect, useState } from 'react';

/**
 * 전황 로그 — devsam '제 전황' 맵 아래 최근 이벤트 텍스트(정복/멸망/전투 등 월드 로그).
 *
 * 데이터: 게이트웨이 route handler `/api/server-log/[id]`가 해당 서버 game-api의 월드 로그 read로
 * 서버사이드 프록시(맵 프리뷰와 동일 패턴). 해당 read API는 후속(전황 read 엔드포인트) — 그 전까지
 * 404/실패 시 '전황 보고 준비 중' placeholder로 graceful degrade(절대 crash 안 함).
 */

interface LogEntry {
    id: number;
    year: number;
    month: number;
    text: string;
}

export default function ServerLog({ serverId = 'main' }: { serverId?: string }) {
    const [entries, setEntries] = useState<LogEntry[] | null>(null);
    const [failed, setFailed] = useState(false);

    useEffect(() => {
        let on = true;
        setEntries(null);
        setFailed(false);
        fetch(`/api/server-log/${serverId}`, { cache: 'no-store' })
            .then((r) => (r.ok ? r.json() : Promise.reject(new Error(String(r.status)))))
            .then((d: { entries?: LogEntry[] }) => {
                if (on) setEntries(d.entries ?? []);
            })
            .catch(() => {
                if (on) setFailed(true);
            });
        return () => {
            on = false;
        };
    }, [serverId]);

    if (failed || (entries && entries.length === 0)) {
        return (
            <div className="server-log" aria-label="전황 보고">
                <div className="server-log-head">전황 보고</div>
                <div className="server-log-empty">전황 보고 준비 중</div>
            </div>
        );
    }
    if (!entries) {
        return (
            <div className="server-log" aria-label="전황 보고">
                <div className="server-log-head">전황 보고</div>
                <div className="server-log-empty">
                    <div className="spinner" />
                </div>
            </div>
        );
    }

    return (
        <div className="server-log" aria-label="전황 보고">
            <div className="server-log-head">전황 보고</div>
            <ul className="server-log-list">
                {entries.map((e) => (
                    <li key={e.id} className="server-log-item">
                        <span className="server-log-date">{`${e.year}年 ${e.month}月`}</span>
                        <span className="server-log-text">{e.text}</span>
                    </li>
                ))}
            </ul>
        </div>
    );
}
