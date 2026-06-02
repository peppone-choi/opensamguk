'use client';

// 사령부 (chief center) — page 7 of the F4 action-page wave.
// READ-ONLY this wave: api.chiefReserved() → ChiefReservedResponse. We render the 8 chief
// posts (officer levels 12/11/10/9/8/7/6/5) as a grid of cards, each card holding the post's
// occupant + that post's reserved-command turn[] (brief per slot, up to maxChiefTurn).
// The legacy '명령' (reserved-command edit) UI is DEFERRED — no CommandModal wiring here.
//
// Parity notes (legacy hwe/ts/PageChiefCenter.vue + ChiefCenter/TopItem.vue + BottomItem.vue):
//  - Display order of the 8 posts is the legacy [12, 10, 8, 6, 11, 9, 7, 5] (two columns of 4).
//  - officerLevelText / name / turnTime come from the server (postFilterNationCommand already applied).
//  - Vacant occupant name renders as '-' (legacy `officer?.name ?? "-"`).
//  - Occupant name color follows the NPC tier (legacy getNPCColor).
//  - turnTime is shown as its last 5 chars (HH:mm), matching BottomItem's `.slice(-5)`.
//  - officerLevel >= 5 gate: below it the caller is not 수뇌부 → INFO notice, posts stay read-only.
// EMPTY-SAFE: missing posts / empty turn[] render empty cells, never crash.

import { useEffect, useState, useCallback } from 'react';
import Shell from '../../../components/Shell';
import GameCard from '../../../components/GameCard';
import { api } from '../../../lib/api';
import type { ChiefReservedResponse, ChiefPost } from '../../../types/game';

// Legacy [12, 10, 8, 6, 11, 9, 7, 5] — preserved verbatim for parity of the post grid order.
const CHIEF_LEVEL_ORDER = [12, 10, 8, 6, 11, 9, 7, 5];

// Mirrors legacy hwe/ts/utilGame/getNPCColor.ts byte-for-byte.
function getNPCColor(npcType: number): string | undefined {
    if (npcType === 6) return 'mediumaquamarine';
    if (npcType === 5) return 'darkcyan';
    if (npcType === 4) return 'deepskyblue';
    if (npcType >= 2) return 'cyan';
    if (npcType === 1) return 'skyblue';
    return undefined;
}

// BottomItem.vue: `(officer?.turnTime ?? "  -  ").slice(-5)` — last 5 chars (HH:mm).
function shortTurnTime(turnTime: string | null): string {
    return (turnTime ?? '  -  ').slice(-5);
}

function ChiefPostCard({ post, maxChiefTurn, isMe }: { post: ChiefPost | undefined; maxChiefTurn: number; isMe: boolean }) {
    const name = post ? (post.name ?? '-') : '-';
    const nameColor = getNPCColor(post?.npcType ?? 0);
    const turns = post?.turn ?? [];

    return (
        <GameCard
            style={{
                borderColor: isMe ? 'var(--gold-dim)' : undefined,
                borderWidth: isMe ? '2px' : undefined,
            }}
        >
            <div
                style={{
                    display: 'flex',
                    justifyContent: 'space-between',
                    alignItems: 'baseline',
                    gap: 'var(--space-sm)',
                    marginBottom: 'var(--space-sm)',
                    paddingBottom: 'var(--space-xs)',
                    borderBottom: '1px solid var(--border-subtle)',
                }}
            >
                <div style={{ display: 'flex', alignItems: 'baseline', gap: 'var(--space-sm)', minWidth: 0 }}>
                    <span style={{ fontSize: 'var(--text-sm)', color: 'var(--text-muted)', whiteSpace: 'nowrap' }}>
                        {post?.officerLevelText ?? ''}
                    </span>
                    <strong
                        style={{
                            fontSize: 'var(--text-base)',
                            color: nameColor ?? 'var(--text-primary)',
                            textDecoration: isMe ? 'underline' : undefined,
                            overflow: 'hidden',
                            textOverflow: 'ellipsis',
                            whiteSpace: 'nowrap',
                        }}
                    >
                        {name}
                    </strong>
                </div>
                {post?.turnTime != null && (
                    <span className="f_tnum" style={{ fontSize: 'var(--text-xs)', color: 'var(--text-muted)', whiteSpace: 'nowrap' }}>
                        {shortTurnTime(post.turnTime)}
                    </span>
                )}
            </div>

            <div style={{ display: 'flex', flexDirection: 'column', gap: '1px' }}>
                {Array.from({ length: maxChiefTurn }, (_, idx) => {
                    const turn = turns[idx];
                    return (
                        <div
                            key={idx}
                            style={{
                                display: 'grid',
                                gridTemplateColumns: '1.75rem 1fr',
                                alignItems: 'center',
                                fontSize: 'var(--text-sm)',
                                padding: '2px var(--space-xs)',
                                background: idx % 2 === 0 ? 'var(--bg-hover)' : 'transparent',
                                borderRadius: 'var(--radius-sm)',
                            }}
                        >
                            <span style={{ color: 'var(--text-muted)', fontSize: 'var(--text-xs)' }}>{idx + 1}</span>
                            <span
                                style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
                                dangerouslySetInnerHTML={{ __html: turn?.brief ?? '' }}
                            />
                        </div>
                    );
                })}
            </div>
        </GameCard>
    );
}

export default function ChiefCenterPage() {
    const [data, setData] = useState<ChiefReservedResponse | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string>('');

    const fetchData = useCallback(async () => {
        setLoading(true);
        try {
            const res = await api.chiefReserved();
            setData(res);
            setError('');
        } catch {
            setError('사령부 정보를 불러올 수 없습니다.');
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => {
        fetchData();
    }, [fetchData]);

    useEffect(() => {
        const es = new EventSource('/api/game/sse/turn');
        es.addEventListener('turnCompleted', () => fetchData());
        es.onerror = () => es.close();
        return () => es.close();
    }, [fetchData]);

    const maxChiefTurn = data?.maxChiefTurn ?? 0;
    const chiefList = data?.chiefList ?? {};
    // officerLevel >= 5 gate: only 수뇌부 (chief posts lv 5+) may view/edit the 사령부.
    const isAllowed = (data?.officerLevel ?? 0) >= 5;

    return (
        <Shell>
            <div style={{ display: 'flex', alignItems: 'baseline', gap: 'var(--space-md)', marginBottom: 'var(--space-md)', flexWrap: 'wrap' }}>
                <h1 style={{ fontSize: 'var(--text-2xl)', fontWeight: 700 }}>사령부</h1>
                {data && (
                    <span style={{ fontSize: 'var(--text-sm)', color: 'var(--text-muted)' }}>
                        {data.year}년 {data.month}월
                    </span>
                )}
                <button onClick={fetchData} style={{ marginLeft: 'auto' }}>새로고침</button>
            </div>

            {loading && <p style={{ color: 'var(--text-muted)' }}>로딩 중...</p>}
            {error && <p style={{ color: 'var(--crimson)' }}>{error}</p>}

            {data && !isAllowed && (
                <GameCard>
                    <p style={{ color: 'var(--text-secondary)' }}>권한이 부족합니다. 수뇌부가 아닙니다.</p>
                </GameCard>
            )}

            {data && isAllowed && (
                <div
                    style={{
                        display: 'grid',
                        gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))',
                        gap: 'var(--space-md)',
                    }}
                >
                    {CHIEF_LEVEL_ORDER.map(level => (
                        <ChiefPostCard
                            key={level}
                            post={chiefList[level]}
                            maxChiefTurn={maxChiefTurn}
                            isMe={level === data.officerLevel}
                        />
                    ))}
                </div>
            )}
        </Shell>
    );
}
