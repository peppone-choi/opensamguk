'use client';

// 사령부 (chief center) — page 7 of the F4 action-page wave.
// READ + MUTATION (W4 FE): api.chiefReserved() → ChiefReservedResponse. 8개 직책 칸(officer level
// 12/11/10/9/8/7/6/5)을 카드 그리드로 렌더하고, 각 칸은 점유 장수 + 직책별 예약 국가 명령(slot별 brief)을
// 보여준다. 내 직책 칸(officerLevel === myOfficerLevel)에서는 슬롯을 골라 commandList(사령부 명령 팔레트)
// 에서 명령을 선택해 예약할 수 있다 → CommandModal을 그 명령 value + 그 turnIdx에 pin해 띄운다.
// POST /api/command/nation/bulk?generalId= (nation_turn 링 — P0-09). 팔레트는 백엔드 commandList가
// 내려보내는 명령(현재 F4StateText.CHIEF_COMMAND_TABLE의 6개 카테고리: 휴식/인사/외교/특수/전략/기타)만
// 렌더하며, 렌더된 명령은 모두 이 경로를 탄다. event_*연구 9종은 레지스트리 등록·ring 배선돼 있으나
// 이 테이블에는 아직 미포함 — 백엔드가 테이블에 포함시키면 함께 노출된다.
//
// Parity notes (legacy hwe/ts/PageChiefCenter.vue + ChiefCenter/TopItem.vue + ChiefReservedCommand.vue):
//  - Display order of the 8 posts is the legacy [12, 10, 8, 6, 11, 9, 7, 5] (two columns of 4).
//  - officerLevelText / name / turnTime come from the server (postFilterNationCommand applied server-side).
//  - Vacant occupant name renders as '-' (legacy `officer?.name ?? "-"`).
//  - Occupant name color follows the NPC tier (legacy getNPCColor).
//  - turnTime is shown as its last 5 chars (HH:mm), matching BottomItem's `.slice(-5)`.
//  - 예약 슬롯 brief는 색/태그 마크업 verbatim, 빈 슬롯은 '휴식'.
//  - myOfficerLevel >= 5 gate: 수뇌부가 아니면 INFO notice, 칸은 read-only(예약 편집 불가).
// EMPTY-SAFE: missing posts / empty reservedTurns render empty cells, never crash.

import { useEffect, useState, useCallback } from 'react';
import Shell from '../../../components/Shell';
import GameCard from '../../../components/GameCard';
import CommandModal from '../../../components/CommandModal';
import ChiefCommandReserve, { type ChiefReserveLaunch } from '../../../components/game/ChiefCommandReserve';
import { api } from '../../../lib/api';
import { useFrontInfo } from '../../../hooks/useFrontInfo';
import type { ChiefReservedResponse, ChiefPost, ChiefCommandCategory } from '../../../types/game';

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

function ChiefPostCard({
    post,
    maxChiefTurn,
    isMe,
    commandList,
    onLaunch,
}: {
    post: ChiefPost | undefined;
    maxChiefTurn: number;
    isMe: boolean;
    commandList: ChiefCommandCategory[];
    onLaunch: (spec: ChiefReserveLaunch) => void;
}) {
    const name = post ? (post.name ?? '-') : '-';
    const nameColor = getNPCColor(post?.npcType ?? 0);
    const turns = post?.reservedTurns ?? [];

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

            {/* 내 직책 칸이면 슬롯-편집(ChiefCommandReserve), 아니면 read-only brief 목록. */}
            {isMe ? (
                <ChiefCommandReserve
                    maxChiefTurn={maxChiefTurn}
                    reservedTurns={turns}
                    commandList={commandList}
                    onLaunch={onLaunch}
                />
            ) : (
                <div style={{ display: 'flex', flexDirection: 'column', gap: '1px' }}>
                    {Array.from({ length: maxChiefTurn }, (_, idx) => {
                        const turn = turns.find((t) => t.turnIdx === idx);
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
            )}
        </GameCard>
    );
}

export default function ChiefCenterPage() {
    const { frontInfo, refresh } = useFrontInfo();
    const generalId = frontInfo?.general.generalId ?? null;
    const nationId = frontInfo?.general.nationId;
    const [data, setData] = useState<ChiefReservedResponse | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string>('');
    const [toast, setToast] = useState<string>('');
    // 슬롯+명령이 선택돼 열린 CommandModal spec(null = 닫힘).
    const [launch, setLaunch] = useState<ChiefReserveLaunch | null>(null);

    function showToast(msg: string) {
        setToast(msg);
        setTimeout(() => setToast(''), 3000);
    }

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
    const posts = data?.posts ?? [];
    const commandList = data?.commandList ?? [];
    // officerLevel로 색인(직책 칸 조회). posts는 배열이므로 레벨→post 맵으로 변환.
    const postByLevel = new Map(posts.map((p) => [p.officerLevel, p]));
    // myOfficerLevel >= 5 gate: only 수뇌부 (chief posts lv 5+) may view/edit the 사령부.
    const myOfficerLevel = data?.myOfficerLevel ?? 0;
    const isAllowed = myOfficerLevel >= 5;

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

            {toast && (
                <div className="toast" style={{ position: 'fixed', top: 'var(--space-md)', right: 'var(--space-md)', zIndex: 200 }}>
                    {toast}
                </div>
            )}

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
                    {CHIEF_LEVEL_ORDER.map((level) => (
                        <ChiefPostCard
                            key={level}
                            post={postByLevel.get(level)}
                            maxChiefTurn={maxChiefTurn}
                            isMe={level === myOfficerLevel}
                            commandList={commandList}
                            onLaunch={(spec) => {
                                if (generalId == null) {
                                    showToast('장수가 없어 명령을 예약할 수 없습니다.');
                                    return;
                                }
                                setLaunch(spec);
                            }}
                        />
                    ))}
                </div>
            )}

            {/* 슬롯+명령 선택 → CommandModal을 그 명령 value + turnIdx에 pin해 띄운다.
                argType은 game-api가 argsSchema에서 파생한 값(city/nation/general/amount|null) →
                인자 폼이 필요하면 모달이 해당 picker를 연다. 인자 없는 명령은 즉시 예약. */}
            {launch && generalId != null && (
                <CommandModal
                    onClose={() => setLaunch(null)}
                    onToast={(msg) => showToast(msg)}
                    generalId={generalId}
                    nationId={nationId}
                    turnIdx={launch.turnIdx}
                    pinnedCommand={launch.command.value}
                    pinnedLabel={`${launch.command.simpleName} (${launch.turnIdx + 1}턴)`}
                    pinnedArgType={launch.command.argType}
                    onReserved={() => {
                        refresh();
                        fetchData();
                    }}
                    isNationCommand
                />
            )}
        </Shell>
    );
}
