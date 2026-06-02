'use client';

// 부대 편성 (troop) — page 6 of the F4 action-page wave.
// READ-ONLY this wave: api.troops() → TroopListResponse. We render every formed troop
// as a card: troop name + leader-city header, 【턴】 turn time, leader, reserved-command
// brief list, and the member roster with a (N명) count. Troop ops (NewTroop / JoinTroop /
// ExitTroop / Disband / KickFromTroop / SetTroopName) are DEFERRED — no mutation wiring here.
//
// Parity notes (legacy hwe/ts/PageTroop.vue):
//  - Title '부대 편성' verbatim.
//  - Troops arrive pre-sorted by the server (legacy sorts by turntime then leader id);
//    we preserve the array order as-is (no client re-sort).
//  - Card header: troopName, then '【 <city> 】' (the leader's city), then '【턴】 <HH:mm>'.
//    Legacy renders turnTime.slice(14, 19) — the HH:mm portion of 'YYYY-MM-DD HH:mm:ss'.
//  - Reserved-command brief list: each slot as `${idx + 1}: ${brief}` verbatim. brief is '집합'
//    / '-' style text (may carry color/tag markup → rendered as HTML, mirroring the war-room
//    reserved-command brief panes).
//  - Member roster: comma-separated names. The troop leader (member.troopId === troop.troopId
//    AND member is the leader) and same-city members render plain; a member in a DIFFERENT city
//    than the leader renders red with a '(<city>)' suffix (legacy troopDiffCityMemeber).
//  - Trailing '(N명)' member count verbatim.
//  - NPC name color follows the legacy getNPCColor tier (utilGame/getNPCColor.ts).
//
// EMPTY-SAFE: an empty troops array renders the empty-state notice, never crashes. The
// fresh scenario_1010 seed has no formed troops → api.troops() returns { troops: [] } (200).

import { useCallback, useEffect, useState } from 'react';
import Shell from '../../../components/Shell';
import GameCard from '../../../components/GameCard';
import { api } from '../../../lib/api';
import type { GeneralListItem, TroopInfo, TroopListResponse } from '../../../types/game';

// NPC name color — verbatim port of legacy utilGame/getNPCColor.ts (npc type → CSS color).
function npcColor(npc: number): string | undefined {
    if (npc === 6) return 'mediumaquamarine';
    if (npc === 5) return 'darkcyan';
    if (npc === 4) return 'deepskyblue';
    if (npc >= 2) return 'cyan';
    if (npc === 1) return 'skyblue';
    return undefined;
}

// Legacy renders turnTime.slice(14, 19): the HH:mm portion of 'YYYY-MM-DD HH:mm:ss'.
// EMPTY-SAFE: a short/blank turnTime just yields the (possibly empty) sliced fragment.
function turnHourMinute(turnTime: string): string {
    return (turnTime ?? '').slice(14, 19);
}

function TroopMembers({ troop, myGeneralId }: { troop: TroopInfo; myGeneralId: number }) {
    const leaderCity = troop.troopLeader.cityId;
    return (
        <div style={{ fontSize: 'var(--text-sm)', lineHeight: 1.7 }}>
            {troop.members.map((member: GeneralListItem, idx: number) => {
                // Legacy: leader → troopLeader style; same-city → plain; other-city → red + (city).
                const isLeader = member.generalId === troop.troopId;
                const sameCity = member.cityId === leaderCity;
                const isMe = member.generalId === myGeneralId;
                const color = isLeader
                    ? 'var(--gold)'
                    : sameCity
                      ? (npcColor(member.npc) ?? 'var(--text-primary)')
                      : 'var(--crimson)'; // troopDiffCityMemeber
                return (
                    <span key={member.generalId}>
                        {idx !== 0 && <span style={{ color: 'var(--text-muted)' }}>, </span>}
                        <span
                            style={{
                                color,
                                fontWeight: isLeader ? 700 : 400,
                                textDecoration: isMe ? 'underline' : undefined,
                            }}
                        >
                            {member.name}
                            {!isLeader && !sameCity && (
                                <span style={{ color: 'var(--crimson)' }}> ({member.cityId})</span>
                            )}
                        </span>
                    </span>
                );
            })}
            {/* '(N명)' member count verbatim. */}
            <span style={{ color: 'var(--text-muted)', marginLeft: 'var(--space-xs)' }}>
                ({troop.members.length}명)
            </span>
        </div>
    );
}

function TroopItem({ troop, myGeneralId }: { troop: TroopInfo; myGeneralId: number }) {
    const leader = troop.troopLeader;
    const isMyTroop = troop.troopId !== 0 && myGeneralId !== 0 && troop.members.some((m) => m.generalId === myGeneralId);

    return (
        <GameCard
            style={{
                borderColor: isMyTroop ? 'var(--gold-dim)' : undefined,
                borderWidth: isMyTroop ? '2px' : undefined,
            }}
        >
            {/* Header: troop name + leader-city + 【턴】 turn time. */}
            <div
                style={{
                    display: 'flex',
                    alignItems: 'baseline',
                    justifyContent: 'space-between',
                    gap: 'var(--space-sm)',
                    flexWrap: 'wrap',
                    paddingBottom: 'var(--space-xs)',
                    marginBottom: 'var(--space-sm)',
                    borderBottom: '1px solid var(--border-subtle)',
                }}
            >
                <div style={{ display: 'flex', alignItems: 'baseline', gap: 'var(--space-sm)', minWidth: 0 }}>
                    <strong
                        style={{
                            fontSize: 'var(--text-base)',
                            color: 'var(--text-primary)',
                            overflow: 'hidden',
                            textOverflow: 'ellipsis',
                            whiteSpace: 'nowrap',
                        }}
                    >
                        {troop.troopName}
                    </strong>
                    <span style={{ fontSize: 'var(--text-sm)', color: 'var(--text-muted)', whiteSpace: 'nowrap' }}>
                        【 {leader.cityId} 】
                    </span>
                </div>
                <span className="f_tnum" style={{ fontSize: 'var(--text-xs)', color: 'var(--text-muted)', whiteSpace: 'nowrap' }}>
                    【턴】 {turnHourMinute(troop.turnTime)}
                </span>
            </div>

            <div
                style={{
                    display: 'grid',
                    gridTemplateColumns: 'minmax(120px, 200px) 1fr',
                    gap: 'var(--space-md)',
                    alignItems: 'start',
                }}
            >
                {/* Leader + reserved-command brief column. */}
                <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-xs)' }}>
                    <strong
                        style={{
                            fontSize: 'var(--text-sm)',
                            color: npcColor(leader.npc) ?? 'var(--text-primary)',
                        }}
                    >
                        {leader.name}
                    </strong>
                    <div style={{ display: 'flex', flexDirection: 'column', gap: '1px' }}>
                        {troop.reservedCommandBrief.map((brief, idx) => (
                            <div
                                key={idx}
                                style={{
                                    fontSize: 'var(--text-xs)',
                                    color: 'var(--text-secondary)',
                                    overflow: 'hidden',
                                    textOverflow: 'ellipsis',
                                    whiteSpace: 'nowrap',
                                }}
                            >
                                <span style={{ color: 'var(--text-muted)' }}>{idx + 1}: </span>
                                <span dangerouslySetInnerHTML={{ __html: brief }} />
                            </div>
                        ))}
                    </div>
                </div>

                {/* Member roster column. */}
                <TroopMembers troop={troop} myGeneralId={myGeneralId} />
            </div>
        </GameCard>
    );
}

export default function TroopPage() {
    const [data, setData] = useState<TroopListResponse | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState('');

    const fetchData = useCallback(async () => {
        setLoading(true);
        try {
            const res = await api.troops();
            setData(res);
            setError('');
        } catch {
            setError('부대 정보를 불러올 수 없습니다.');
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

    // EMPTY-SAFE: a fresh seed with no formed troops returns { troops: [] } (200).
    const troops = data?.troops ?? [];
    const myGeneralId = data?.myGeneralId ?? 0;

    return (
        <Shell>
            <div
                style={{
                    display: 'flex',
                    alignItems: 'baseline',
                    gap: 'var(--space-md)',
                    marginBottom: 'var(--space-md)',
                    flexWrap: 'wrap',
                }}
            >
                <h1 style={{ fontSize: 'var(--text-2xl)', fontWeight: 700 }}>부대 편성</h1>
                {data && (
                    <span style={{ fontSize: 'var(--text-sm)', color: 'var(--text-muted)' }}>
                        {troops.length}개 부대
                    </span>
                )}
                <button onClick={fetchData} style={{ marginLeft: 'auto' }}>새로고침</button>
            </div>

            {loading && <p style={{ color: 'var(--text-muted)' }}>로딩 중...</p>}
            {error && <p style={{ color: 'var(--crimson)' }}>{error}</p>}

            {data && troops.length === 0 && (
                <GameCard>
                    <p style={{ color: 'var(--text-secondary)' }}>편성된 부대가 없습니다.</p>
                </GameCard>
            )}

            {data && troops.length > 0 && (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-md)' }}>
                    {troops.map((troop) => (
                        <TroopItem key={troop.troopId} troop={troop} myGeneralId={myGeneralId} />
                    ))}
                </div>
            )}
        </Shell>
    );
}
