'use client';

// 부대 편성 (troop) — page 6 of the F4 action-page wave.
// READ + MUTATION (F4 Wave C2 slice B): api.troops() → TroopListResponse renders every formed troop
// (name + leader-city header, 【턴】 turn time, leader, reserved-command brief list, member roster
// with a (N명) count). The six troop ops (NewTroop / JoinTroop / ExitTroop / Disband / KickFromTroop /
// SetTroopName) are wired through the existing CommandModal (pinnedCommand + extraArgs, the slice-A
// pattern) — every troop arg rides extraArgs (troopName/troopId/targetGeneralId); none needs a
// Select*/amount sub-form, so pinnedArgType is always null.
//
// Mutation gating (membership-derived from myGeneralId; the engine re-validates every guard):
//  - 부대 창설 (NewTroop): shown when I am troopless. troopName via a text input → extraArgs.
//  - 부대 탑승 (JoinTroop): shown on OTHER troops when I am troopless. troopId = that card's troop.
//  - 부대 탈퇴 (ExitTroop, member) / 부대 해산 (ExitTroop, leader): on MY troop card.
//  - 부대명 변경 (SetTroopName): on MY troop when I am the leader. troopName via a text input.
//  - 추방 (KickFromTroop): per non-leader member of MY troop when I am the leader.
//
// Parity notes (legacy hwe/ts/PageTroop.vue) — unchanged from the read wave:
//  - Title '부대 편성' verbatim. Troops kept in server order (no client re-sort).
//  - Card header: troopName, '【 <city> 】' (leader's city), '【턴】 <HH:mm>' (turnTime.slice(14,19)).
//  - Reserved-command brief list: `${idx + 1}: ${brief}` verbatim (brief may carry color/tag markup).
//  - Member roster: comma-separated; leader gold/bold; same-city plain; other-city red + '(<city>)';
//    trailing '(N명)' count. NPC name color follows legacy getNPCColor tiers.
//
// EMPTY-SAFE: an empty troops array renders the empty-state notice. The fresh scenario_1010 seed has
// no formed troops → api.troops() returns { troops: [], myGeneralId } (200).

import { useCallback, useEffect, useState } from 'react';
import Shell from '../../../components/Shell';
import PageHead from '../../../components/PageHead';
import GameCard from '../../../components/GameCard';
import CommandModal from '../../../components/CommandModal';
import GeneralName from '../../../components/game/GeneralName';
import { api } from '../../../lib/api';
import { getNPCColor } from '../../../lib/utilGame';
import { useTurnRefresh } from '../../../hooks/useTurnRefresh';
import type { TroopInfo, TroopListResponse, TroopMember } from '../../../types/game';

// One open troop CommandModal spec. argType is always null (args ride extraArgs).
type TroopModalSpec = { command: string; label: string; extraArgs?: Record<string, unknown> };

// Legacy renders turnTime.slice(14, 19): the HH:mm portion of 'YYYY-MM-DD HH:mm:ss'.
function turnHourMinute(turnTime: string): string {
    return (turnTime ?? '').slice(14, 19);
}

function TroopMembers({
    troop,
    myGeneralId,
    onKick,
}: {
    troop: TroopInfo;
    myGeneralId: number;
    onKick?: (member: TroopMember) => void;
}) {
    // 레거시 same-city 판정은 부대장 소재 도시 한글명과 비교(BE가 cityName으로 emit, bug #11).
    const leaderCityName = troop.leaderCityName;
    return (
        <div style={{ fontSize: 'var(--text-sm)', lineHeight: 1.7 }}>
            {troop.members.map((member: TroopMember, idx: number) => {
                // Legacy: leader → troopLeader style; same-city → plain; other-city → red + (city).
                const isLeader = member.generalId === troop.troopLeader;
                const sameCity = member.cityName === leaderCityName;
                const isMe = member.generalId === myGeneralId;
                const color = isLeader
                    ? 'var(--gold)'
                    : sameCity
                      ? (getNPCColor(member.npc) ?? 'var(--text-primary)')
                      : 'var(--crimson)'; // troopDiffCityMemeber
                return (
                    <span key={member.generalId}>
                        {idx !== 0 && <span style={{ color: 'var(--text-muted)' }}>, </span>}
                        <GeneralName
                            name={member.name}
                            npcType={member.npc}
                            style={{
                                color,
                                fontWeight: isLeader ? 700 : 400,
                                textDecoration: isMe ? 'underline' : undefined,
                            }}
                        />
                        <span>
                            {!isLeader && !sameCity && (
                                <span style={{ color: 'var(--crimson)' }}> ({member.cityName})</span>
                            )}
                        </span>
                        {/* 추방 (KickFromTroop): leader-only, never on the leader row. */}
                        {onKick && !isLeader && (
                            <button
                                onClick={() => onKick(member)}
                                style={{ marginLeft: 4, fontSize: 'var(--text-xs)', padding: '0 4px' }}
                            >
                                추방
                            </button>
                        )}
                    </span>
                );
            })}
            <span style={{ color: 'var(--text-muted)', marginLeft: 'var(--space-xs)' }}>
                ({troop.members.length}명)
            </span>
        </div>
    );
}

function TroopItem({
    troop,
    myGeneralId,
    relation,
    canRename,
    renameDraft,
    setRenameDraft,
    openModal,
}: {
    troop: TroopInfo;
    myGeneralId: number;
    relation: 'leader' | 'member' | 'other' | 'none';
    // 레거시 myPermission >= 4: 모든 부대에 부대명 변경 허용(bug #18).
    canRename: boolean;
    renameDraft: string;
    setRenameDraft: (v: string) => void;
    openModal: (spec: TroopModalSpec) => void;
}) {
    const isMyTroop = relation === 'leader' || relation === 'member';

    return (
        <GameCard
            style={{
                borderColor: isMyTroop ? 'var(--gold-dim)' : undefined,
                borderWidth: isMyTroop ? '2px' : undefined,
            }}
        >
            {/* Header: troop name + leader-city + 【턴】 turn time + per-card action. */}
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
                        {troop.name}
                    </strong>
                    <span style={{ fontSize: 'var(--text-sm)', color: 'var(--text-muted)', whiteSpace: 'nowrap' }}>
                        【 {troop.leaderCityName} 】
                    </span>
                </div>
                <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-xs)' }}>
                    {relation === 'other' && (
                        <button onClick={() => openModal({ command: 'troopJoin', label: '부대 탑승', extraArgs: { troopId: troop.troopLeader } })}>
                            부대 탑승
                        </button>
                    )}
                    {relation === 'member' && (
                        <button onClick={() => openModal({ command: 'troopExit', label: '부대 탈퇴' })}>부대 탈퇴</button>
                    )}
                    {relation === 'leader' && (
                        <button onClick={() => openModal({ command: 'troopExit', label: '부대 해산' })}>부대 해산</button>
                    )}
                    <span className="f_tnum" style={{ fontSize: 'var(--text-xs)', color: 'var(--text-muted)', whiteSpace: 'nowrap' }}>
                        【턴】 {turnHourMinute(troop.turnTime)}
                    </span>
                </div>
            </div>

            {/* 부대명 변경 행 — 레거시 myPermission >= 4면 모든 부대에 노출(bug #18). */}
            {canRename && (
                <div style={{ display: 'flex', gap: 'var(--space-xs)', marginBottom: 'var(--space-sm)' }}>
                    <input
                        type="text"
                        maxLength={18}
                        placeholder="새 부대명"
                        value={renameDraft}
                        onChange={(e) => setRenameDraft(e.target.value)}
                        style={{ flex: '0 1 200px' }}
                    />
                    <button
                        onClick={() =>
                            openModal({ command: 'troopSetName', label: '부대명 변경', extraArgs: { troopId: troop.troopLeader, troopName: renameDraft } })
                        }
                    >
                        부대명 변경
                    </button>
                </div>
            )}

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
                    <strong style={{ fontSize: 'var(--text-sm)' }}>
                        <GeneralName name={troop.leaderName} npcType={troop.leaderNpc} />
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

                {/* Member roster column (leader gets per-member 추방 buttons). */}
                <TroopMembers
                    troop={troop}
                    myGeneralId={myGeneralId}
                    onKick={
                        relation === 'leader'
                            ? (member) =>
                                  openModal({
                                      command: 'troopKick',
                                      label: `${member.name} 추방`,
                                      extraArgs: { troopId: troop.troopLeader, targetGeneralId: member.generalId },
                                  })
                            : undefined
                    }
                />
            </div>
        </GameCard>
    );
}

export default function TroopPage() {
    const [data, setData] = useState<TroopListResponse | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState('');
    const [modal, setModal] = useState<TroopModalSpec | null>(null);
    const [toast, setToast] = useState<string | null>(null);
    const [createName, setCreateName] = useState('');
    const [renameName, setRenameName] = useState('');

    // background=true는 턴 갱신용 — 부대 목록을 로딩 표시로 지우지 않는다.
    const fetchData = useCallback(async (background = false) => {
        if (!background) setLoading(true);
        try {
            const res = await api.troops();
            setData(res);
            setError('');
        } catch {
            // 턴 갱신(background) 실패는 보고 있던 화면을 지우지 않는다.
            if (!background) setError('부대 정보를 불러올 수 없습니다.');
        } finally {
            if (!background) setLoading(false);
        }
    }, []);

    useEffect(() => {
        fetchData();
    }, [fetchData]);

    // OPENSAM-196: 전용 EventSource 대신 Shell의 단일 SSE를 공유하는 턴 완료 신호로 갈아탄다.
    // 부대 창설/개명 입력 draft는 fetchData가 건드리지 않으므로 갱신해도 안전하다.
    useTurnRefresh(() => {
        fetchData(true);
    });

    // EMPTY-SAFE: a fresh seed with no formed troops returns { troops: [] } (200).
    const troops = data?.troops ?? [];
    const myGeneralId = data?.myGeneralId ?? 0;
    // 레거시 myPermission. 부대명 변경 게이트(>= 4, bug #18).
    const permission = data?.permission ?? 0;
    const canRename = permission >= 4;

    // My membership: the troop whose roster includes me (if any), and whether I lead it.
    const myTroop = myGeneralId !== 0 ? troops.find((t) => t.members.some((m) => m.generalId === myGeneralId)) ?? null : null;
    const iAmTroopless = myGeneralId !== 0 && myTroop == null;

    const relationOf = (troop: TroopInfo): 'leader' | 'member' | 'other' | 'none' => {
        if (myGeneralId === 0) return 'none';
        if (myTroop && troop.troopLeader === myTroop.troopLeader) {
            // 부대장 == 호출자 → leader(레거시 me.no == me.troop), 아니면 member.
            return troop.troopLeader === myGeneralId ? 'leader' : 'member';
        }
        return iAmTroopless ? 'other' : 'none';
    };

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
                <PageHead title="부대 편성" />
                {data && (
                    <span style={{ fontSize: 'var(--text-sm)', color: 'var(--text-muted)' }}>{troops.length}개 부대</span>
                )}
                <button onClick={() => void fetchData()} style={{ marginLeft: 'auto' }}>새로고침</button>
            </div>

            {loading && <p style={{ color: 'var(--text-muted)' }}>로딩 중...</p>}
            {error && <p style={{ color: 'var(--crimson)' }}>{error}</p>}

            {/* 부대 창설 (NewTroop) — shown only when I am troopless. */}
            {iAmTroopless && (
                <GameCard style={{ marginBottom: 'var(--space-md)' }}>
                    <div style={{ display: 'flex', gap: 'var(--space-xs)', alignItems: 'center', flexWrap: 'wrap' }}>
                        <strong style={{ fontSize: 'var(--text-sm)' }}>부대 창설</strong>
                        <input
                            type="text"
                            maxLength={18}
                            placeholder="부대 이름 (최대 18너비)"
                            value={createName}
                            onChange={(e) => setCreateName(e.target.value)}
                            style={{ flex: '0 1 220px' }}
                        />
                        <button onClick={() => setModal({ command: 'troopNew', label: '부대 창설', extraArgs: { troopName: createName } })}>
                            부대 창설
                        </button>
                    </div>
                </GameCard>
            )}

            {data && troops.length === 0 && (
                <GameCard>
                    <p style={{ color: 'var(--text-secondary)' }}>편성된 부대가 없습니다.</p>
                </GameCard>
            )}

            {data && troops.length > 0 && (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-md)' }}>
                    {troops.map((troop) => (
                        <TroopItem
                            key={troop.troopLeader}
                            troop={troop}
                            myGeneralId={myGeneralId}
                            relation={relationOf(troop)}
                            canRename={canRename}
                            renameDraft={renameName}
                            setRenameDraft={setRenameName}
                            openModal={setModal}
                        />
                    ))}
                </div>
            )}

            {/* Troop CommandModal (pinnedCommand + extraArgs, slice-A pattern). All troop ops are
                no-sub-form (pinnedArgType=null) — args ride extraArgs. */}
            {modal && myGeneralId !== 0 && (
                <CommandModal
                    onClose={() => setModal(null)}
                    onToast={(msg) => setToast(msg)}
                    generalId={myGeneralId}
                    pinnedCommand={modal.command}
                    pinnedLabel={modal.label}
                    pinnedArgType={null}
                    extraArgs={modal.extraArgs}
                    onReserved={() => {
                        setCreateName('');
                        setRenameName('');
                        fetchData();
                    }}
                />
            )}

            {toast && (
                <div
                    role="status"
                    style={{
                        position: 'fixed',
                        bottom: 16,
                        left: '50%',
                        transform: 'translateX(-50%)',
                        background: 'var(--surface-raised)',
                        padding: '8px 16px',
                        borderRadius: 8,
                    }}
                    onClick={() => setToast(null)}
                >
                    {toast}
                </div>
            )}
        </Shell>
    );
}
