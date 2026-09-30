'use client';

import { useEffect, useState, type ReactNode } from 'react';
import { SectionHeader, formatGameDate, worldEventSentence, type GameEvent, type GameEventPage } from '@opensamguk/ui';
import type { MapData } from '@/components/MapPreview';
import { previewNames } from '@/lib/serverStatus';
import StateLine from './StateLine';

type Feed = { readonly kind: 'loading' } | { readonly kind: 'error' } | { readonly kind: 'ready'; readonly events: readonly GameEvent[] };

/**
 * 천하 정세(설계서 SB5 · SL1–SL5) — 공개 사건 피드(/api/world-events, ADR-LITE-069 WORLD 칸)를 알림체 문장으로 보인다.
 * 이름은 같은 서버의 지도 미리보기에서 푼다. 모르는 사건 종류는 줄을 뺀다(문장을 지어내지 않는다).
 */
export default function WorldEventsPanel({ serverId, preview, limit = 5, footer, className = '' }: {
    readonly serverId: string;
    readonly preview: MapData | null;
    readonly limit?: number;
    readonly footer?: ReactNode;
    readonly className?: string;
}) {
    const [feed, setFeed] = useState<Feed>({ kind: 'loading' });
    const [attempt, setAttempt] = useState(0);

    useEffect(() => {
        const controller = new AbortController();
        setFeed({ kind: 'loading' });
        fetch(`/api/server-events/${encodeURIComponent(serverId)}?limit=${limit}`, { cache: 'no-store', signal: controller.signal })
            .then((response) => (response.ok ? response.json() : Promise.reject(new Error(String(response.status)))))
            .then((page: GameEventPage) => {
                if (!controller.signal.aborted) setFeed({ kind: 'ready', events: Array.isArray(page.events) ? page.events : [] });
            })
            .catch(() => {
                if (!controller.signal.aborted) setFeed({ kind: 'error' });
            });
        return () => controller.abort();
    }, [serverId, limit, attempt]);

    const names = previewNames(preview);
    const lines = feed.kind === 'ready'
        ? feed.events.flatMap((event) => {
            const text = worldEventSentence(event, names);
            return text ? [{ id: event.id, date: formatGameDate(event.occurredAt), text }] : [];
        })
        : [];

    return (
        <section className={`os-panel os-panel--static gw31-panel ${className}`.trim()} aria-label="천하 정세">
            <SectionHeader title="천하 정세" sub="공개 사건 · 최근" />
            {feed.kind === 'loading' && <StateLine kind="loading" title="천하 정세를 불러오는 중" />}
            {feed.kind === 'error' && <StateLine kind="error" title="천하 정세를 불러오지 못했습니다" onRetry={() => setAttempt((n) => n + 1)} />}
            {feed.kind === 'ready' && lines.length === 0 && <StateLine kind="empty" title="아직 공개된 사건이 없습니다" />}
            {lines.length > 0 && (
                <ul className="gw31-events">
                    {lines.map((line) => (
                        <li key={line.id} className="gw31-events__row">
                            <span className="os-num gw31-events__date">{line.date}</span>
                            <span className="gw31-events__text">{line.text}</span>
                        </li>
                    ))}
                </ul>
            )}
            {footer}
        </section>
    );
}
