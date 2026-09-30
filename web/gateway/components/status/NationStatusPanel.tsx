'use client';

import { useEffect, useState } from 'react';
import { Flag, SectionHeader } from '@opensamguk/ui';
import type { MapData } from '@/components/MapPreview';
import { emperorPlace, fetchImperialPresence, type ImperialPresence } from '@/lib/imperialPresence';
import { nationStatusRows, previewNames } from '@/lib/serverStatus';
import StateLine from './StateLine';

export type PreviewState =
    | { readonly kind: 'loading' }
    | { readonly kind: 'error' }
    | { readonly kind: 'ready'; readonly data: MapData };

/**
 * 세력 현황(설계서 NS1–NS7) — 지도 미리보기의 세력 · 城 소유로 센 「현 수」. 삼모 랭킹(병력 순 · 금 · 쌀)은 쓰지 않는다.
 * 장수 수(NS4)는 미리보기에 없다 — 계약판 K5-11 의 `generalCount` 가 오기 전에는 칸을 두지 않는다(지어내지 않는다).
 * 머리 아래 황제 소재 한 줄(K8): READY 만 보이고, NOT_SEEDED 는 아무것도 두지 않는다.
 */
export default function NationStatusPanel({ serverId, serverLabel, preview, onRetry, mineNationId, className = '' }: {
    readonly serverId: string;
    readonly serverLabel: string;
    readonly preview: PreviewState;
    readonly onRetry: () => void;
    readonly mineNationId?: number | null;
    readonly className?: string;
}) {
    const rows = preview.kind === 'ready' ? nationStatusRows(preview.data) : null;
    const [imperial, setImperial] = useState<ImperialPresence | null>(null);
    const [imperialTry, setImperialTry] = useState(0);

    useEffect(() => {
        const controller = new AbortController();
        setImperial(null);
        fetchImperialPresence(serverId, controller.signal).then((result) => {
            if (!controller.signal.aborted) setImperial(result);
        });
        return () => controller.abort();
    }, [serverId, imperialTry]);

    const names = previewNames(preview.kind === 'ready' ? preview.data : null);
    const sub = rows ? `${serverLabel} · 세력 ${rows.length}` : serverLabel;

    return (
        <section className={`os-panel os-panel--static gw31-panel ${className}`.trim()} aria-label="세력 현황">
            <SectionHeader title="세력 현황" sub={sub} />
            {imperial?.kind === 'ready' && imperial.badges.map((badge) => (
                <p className="gw31-imperial" key={badge.lineCode}>
                    {imperial.badges.length > 1 ? `${badge.lineName} ` : ''}황제 — {emperorPlace(badge, names.city)}
                </p>
            ))}
            {preview.kind === 'loading' && <StateLine kind="loading" title="세력 현황을 불러오는 중" />}
            {preview.kind === 'error' && <StateLine kind="error" title="세력 현황을 불러오지 못했습니다" onRetry={onRetry} />}
            {rows && rows.length === 0 && <StateLine kind="empty" title="아직 선 세력이 없습니다" />}
            {rows && rows.length > 0 && (
                <ul className="gw31-nations">
                    {rows.map((row) => (
                        <li key={row.nationId} className="gw31-nations__row">
                            <Flag color={row.color} size={14} label={row.name} />
                            <span className="gw31-nations__name">
                                {row.name}
                                {mineNationId != null && mineNationId === row.nationId && <span className="os-chip os-chip--bronze">내 소속</span>}
                            </span>
                            <span className="os-num gw31-nations__stat">현 {row.counties}</span>
                        </li>
                    ))}
                </ul>
            )}
            {imperial?.kind === 'error' && (
                <StateLine kind="error" title="황실 소재를 불러오지 못했습니다" onRetry={() => setImperialTry((n) => n + 1)} />
            )}
        </section>
    );
}
