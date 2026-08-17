'use client';

import { useEffect, useMemo, useState } from 'react';
import Shell from '../../../components/Shell';
import GameCard from '../../../components/GameCard';
import GameTable from '../../../components/GameTable';
import StatusBadge from '../../../components/StatusBadge';
import CommandModal from '../../../components/CommandModal';
import { api } from '../../../lib/api';
import { formatNumber } from '../../../lib/format';
import { getNPCColor } from '../../../lib/utilGame';
import { useTurnRefresh } from '../../../hooks/useTurnRefresh';
import type { FrontInfoResponse } from '../../../lib/types';
import type {
    TournamentResponse,
    TournamentEntrant,
    TournamentBracketMatch,
    TournamentRankingBoard,
} from '../../../types/game';
import {
    buildBracketRounds,
    buildStandingSections,
    tournamentAbilityLabel,
    tournamentStateText,
    tournamentTermText,
} from './view-model';

// Verbatim Korean parity (byte-for-byte from the legacy PHP/Vue):
//   - state text  : func_tournament.php getTournament() / formatTournament.ts stepMap
//   - type text   : 전력전 / 통솔전 / 일기토 / 설전  (+ 종합 / 통솔 / 무력 / 지력)
//   - section hdrs: 16강 승자전 / 조별 본선 순위 / 조별 예선 순위
//   - group cols  : 순 · 장수 · {tp2} · 경 · 승 · 무 · 패 · 점 · 득
//   - group names : 一조 … 八조
//   - footer rules: b_tournament.php trailing <font> block

// state 0 = magenta(없음), the rest are orange(진행중) per getTournament().
function stateVariant(state: number): 'gold' | 'crimson' | 'jade' | 'muted' {
    if (state <= 0) return 'muted';
    return 'gold';
}

export default function TournamentPage() {
    const [data, setData] = useState<TournamentResponse | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState('');
    // F4 C2 — own identity + enroll modal (participation toggle).
    const [generalId, setGeneralId] = useState<number | null>(null);
    const [nationId, setNationId] = useState<number | null>(null);
    const [enrollOpen, setEnrollOpen] = useState<{ value: number } | null>(null);
    const [toast, setToast] = useState<string | null>(null);

    // OPENSAM-196 — background=true면 로딩 화면을 다시 띄우지 않는다.
    const fetchData = (background = false) => {
        if (!background) {
            setLoading(true);
            setError('');
        }
        api
            .tournamentView()
            .then((res: TournamentResponse) => setData(res ?? null))
            .catch(() => setError('토너먼트 정보를 불러올 수 없습니다.'))
            .finally(() => { if (!background) setLoading(false); });
    };

    useEffect(fetchData, []);

    // OPENSAM-196 — 턴 종료 시 토너먼트 현황 재조회.
    useTurnRefresh(() => fetchData(true));

    useEffect(() => {
        api.frontInfo()
            .then((fi: FrontInfoResponse) => {
                setGeneralId(fi.general.generalId);
                setNationId(fi.general.nationId);
            })
            .catch(() => { /* identity optional — enroll button hides when absent */ });
    }, []);

    const entrants: readonly TournamentEntrant[] = data && Array.isArray(data.entrants) ? data.entrants : [];
    const bracket: readonly TournamentBracketMatch[] = data && Array.isArray(data.bracket) ? data.bracket : [];
    const rankings: readonly TournamentRankingBoard[] = data && Array.isArray(data.rankings) ? data.rankings : [];
    const tnmtType = data?.tnmtTypeText ?? '전력전';
    const abilityLabel = tournamentAbilityLabel(tnmtType);
    const standingSections = useMemo(() => buildStandingSections(entrants), [entrants]);
    const bracketRounds = useMemo(() => buildBracketRounds(bracket), [bracket]);

    const hasBracket = bracketRounds.some((r) => r.matches.length > 0);

    if (loading) {
        return (
            <Shell>
                <h1 style={{ fontSize: 'var(--text-2xl)', marginBottom: 'var(--space-lg)' }}>토너먼트</h1>
                <p style={{ color: 'var(--text-muted)' }}>로딩 중...</p>
            </Shell>
        );
    }

    if (error) {
        return (
            <Shell>
                <h1 style={{ fontSize: 'var(--text-2xl)', marginBottom: 'var(--space-lg)' }}>토너먼트</h1>
                <p style={{ color: 'var(--crimson)' }}>{error}</p>
                <button
                    onClick={() => fetchData()}
                    style={{
                        marginTop: 'var(--space-md)',
                        background: 'var(--bg-hover)',
                        color: 'var(--text-primary)',
                        fontSize: 'var(--text-sm)',
                        padding: 'var(--space-xs) var(--space-sm)',
                    }}
                >
                    갱신
                </button>
            </Shell>
        );
    }

    const state = data?.state ?? 0;
    const stateText = tournamentStateText(state);

    const groupHeaders = ['순', '장수', abilityLabel, '경', '승', '무', '패', '점', '득'];
    const groupRow = (e: TournamentEntrant) => [
        e.groupRank,
        <span
            key={`g-${e.generalId}-${e.stage}-${e.groupNo}`}
            style={{ color: e.promoted ? 'var(--gold)' : getNPCColor(e.npc) }}
        >
            {e.generalName}
        </span>,
        formatNumber(e.ability),
        formatNumber(e.games),
        formatNumber(e.win),
        formatNumber(e.draw),
        formatNumber(e.lose),
        formatNumber(e.points),
        formatNumber(e.goalDifference),
    ];

    const bracketName = (n: string | null) => (n && n.length > 0 ? n : '-');

    return (
        <Shell>
            <h1 style={{ fontSize: 'var(--text-2xl)', marginBottom: 'var(--space-lg)' }}>토너먼트</h1>

            {/* 진행 상태 / 종목 / 운영자 메세지 */}
            <GameCard className="tournament-header" style={{ marginBottom: 'var(--space-lg)' }}>
                <div
                    className="card-header"
                    style={{
                        display: 'flex',
                        alignItems: 'center',
                        gap: 'var(--space-md)',
                        flexWrap: 'wrap',
                        marginBottom: 'var(--space-sm)',
                    }}
                >
                    <h2 style={{ fontSize: 'var(--text-lg)', fontWeight: 600 }}>삼모전 토너먼트</h2>
                    <StatusBadge variant="jade">{tnmtType}</StatusBadge>
                    <StatusBadge variant={stateVariant(state)}>{stateText}</StatusBadge>
                    {data && data.turnTerm > 0 ? (
                        <StatusBadge variant="muted">{tournamentTermText(data.turnTerm)}</StatusBadge>
                    ) : null}
                    {/* F4 C2 — 참가/불참 토글 (enroll). tnmt 1 = 참가, 0 = 불참. */}
                    {generalId != null && (
                        <>
                            <button
                                onClick={() => setEnrollOpen({ value: 1 })}
                                style={{ marginLeft: 'auto', background: 'var(--bg-hover)', color: 'var(--text-primary)', fontSize: 'var(--text-sm)', padding: 'var(--space-xs) var(--space-sm)' }}
                            >
                                참가
                            </button>
                            <button
                                onClick={() => setEnrollOpen({ value: 0 })}
                                style={{ background: 'var(--bg-hover)', color: 'var(--text-primary)', fontSize: 'var(--text-sm)', padding: 'var(--space-xs) var(--space-sm)' }}
                            >
                                불참
                            </button>
                        </>
                    )}
                    <button
                        onClick={() => fetchData()}
                        style={{
                            marginLeft: generalId != null ? undefined : 'auto',
                            background: 'var(--bg-hover)',
                            color: 'var(--text-primary)',
                            fontSize: 'var(--text-sm)',
                            padding: 'var(--space-xs) var(--space-sm)',
                        }}
                    >
                        갱신
                    </button>
                </div>
                {data?.tnmtMsg ? (
                    <p style={{ color: 'var(--gold)', fontSize: 'var(--text-sm)' }}>
                        운영자 메세지 : {data.tnmtMsg}
                    </p>
                ) : null}
            </GameCard>

            {/* 16강 승자전 */}
            <h2 style={{ fontSize: 'var(--text-lg)', fontWeight: 600, marginBottom: 'var(--space-sm)' }}>
                16강 승자전
            </h2>
            {hasBracket ? (
                <div className="bracket">
                    {bracketRounds.map((r) =>
                        r.matches.length === 0 ? null : (
                            <div key={r.round} className="bracket-round">
                                <h3>{r.label}</h3>
                                {r.matches.map((m) => (
                                    <div key={`${r.round}-${m.matchIdx}`} className="bracket-match">
                                        <span
                                            className={
                                                m.winnerGeneralId != null &&
                                                m.winnerGeneralId === m.leftGeneralId
                                                    ? 'bracket-winner'
                                                    : ''
                                            }
                                        >
                                            {bracketName(m.leftName)}
                                        </span>
                                        <span className="bracket-vs">vs</span>
                                        <span
                                            className={
                                                m.winnerGeneralId != null &&
                                                m.winnerGeneralId === m.rightGeneralId
                                                    ? 'bracket-winner'
                                                    : ''
                                            }
                                        >
                                            {bracketName(m.rightName)}
                                        </span>
                                    </div>
                                ))}
                            </div>
                        ),
                    )}
                </div>
            ) : (
                <p style={{ color: 'var(--text-muted)', marginBottom: 'var(--space-lg)' }}>
                    진행 중인 16강 대진이 없습니다.
                </p>
            )}

            {entrants.length === 0 ? (
                <p style={{ color: 'var(--text-muted)' }}>참가자가 없습니다.</p>
            ) : (
                standingSections.map((section) => (
                    <section key={section.stage}>
                        <h2
                            style={{
                                fontSize: 'var(--text-lg)',
                                fontWeight: 600,
                                margin: 'var(--space-lg) 0 var(--space-sm)',
                            }}
                        >
                            {section.title}
                        </h2>
                        <div
                            style={{
                                display: 'grid',
                                gridTemplateColumns: 'repeat(auto-fill, minmax(260px, 1fr))',
                                gap: 'var(--space-md)',
                            }}
                        >
                            {section.groups.map((group) => (
                                <GameCard key={`${section.stage}-${group.groupNo}`}>
                                    <h3
                                        style={{
                                            fontSize: 'var(--text-sm)',
                                            fontWeight: 600,
                                            marginBottom: 'var(--space-sm)',
                                            color: 'var(--text-secondary)',
                                        }}
                                    >
                                        {group.label}
                                    </h3>
                                    {group.rows.length === 0 ? (
                                        <p style={{ color: 'var(--text-muted)', fontSize: 'var(--text-sm)' }}>-</p>
                                    ) : (
                                        <GameTable headers={groupHeaders} rows={group.rows.map(groupRow)} />
                                    )}
                                </GameCard>
                            ))}
                        </div>
                    </section>
                ))
            )}

            {/* 종목별 순위 — 전력전 / 통솔전 / 일기토 / 설전 */}
            <h2
                style={{
                    fontSize: 'var(--text-lg)',
                    fontWeight: 600,
                    margin: 'var(--space-lg) 0 var(--space-sm)',
                }}
            >
                종목별 순위
            </h2>
            <div
                style={{
                    display: 'grid',
                    gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))',
                    gap: 'var(--space-md)',
                }}
            >
                {rankings.map((board) => (
                    <GameCard key={board.type}>
                        <h3
                            style={{
                                fontSize: 'var(--text-sm)',
                                fontWeight: 600,
                                marginBottom: 'var(--space-sm)',
                                color: 'var(--text-secondary)',
                            }}
                        >
                            {board.type}
                        </h3>
                        {board.rows.length === 0 ? (
                            <p style={{ color: 'var(--text-muted)', fontSize: 'var(--text-sm)' }}>
                                순위 정보가 없습니다.
                            </p>
                        ) : (
                            <GameTable
                                headers={['순위', '장수', '국가', '점수']}
                                rows={board.rows.map((r) => [
                                    r.rank,
                                    r.generalName,
                                    r.nationName,
                                    formatNumber(r.value),
                                ])}
                            />
                        )}
                    </GameCard>
                ))}
            </div>

            {/* 진행 안내 — b_tournament.php 하단 안내 블록 (verbatim) */}
            <GameCard style={{ marginTop: 'var(--space-lg)' }}>
                <div style={{ fontSize: 'var(--text-sm)', color: 'var(--text-secondary)', lineHeight: 1.7 }}>
                    ㆍ예선은 홈&amp;어웨이 풀리그로 진행됩니다. (총 14경기)
                    <br />
                    ㆍ상위 4명이 본선에 진출하게 되며 조추첨을 통해 조가 배정됩니다.
                    <br />
                    ㆍ각 조1위가 시드1로 랜덤하게 조에 배정되며, 역시 각 조2위가 시드2로 랜덤하게 조에 배정됩니다.
                    <br />
                    ㆍ그후 남은 3, 4위는 완전 랜덤하게 모든 조에 랜덤하게 배정됩니다.
                    <br />
                    ㆍ본선은 개인당 3경기를 치르게 되며 승점(승3, 무1, 패0), 득실, 참가순서(시드)에 따라 순위를 매깁니다.
                    <br />
                    ㆍ각 조 1, 2위는 16강에 지정된 위치에 배정됩니다.
                    <br />
                    ㆍ16강부터는 1경기 토너먼트로 진행됩니다.
                    <br />
                    ㆍ참가비는 금20~140이며, 성적에 따라 금과 약간의 명성이 포상으로 주어집니다.
                    <br />
                    ㆍ16강자 100, 8강자 300, 4강자 600, 준우승자 1200, 우승자 2000 (220년 기준)
                    <br />
                    ㆍ즐거운 삼토!
                </div>
            </GameCard>

            {/* F4 C2 — 토너먼트 참가 토글 CommandModal (pinnedCommand + extraArgs.value). */}
            {enrollOpen && generalId != null && (
                <CommandModal
                    onClose={() => setEnrollOpen(null)}
                    onToast={(msg) => setToast(msg)}
                    generalId={generalId}
                    nationId={nationId ?? undefined}
                    pinnedCommand="tournamentEnroll"
                    pinnedLabel={enrollOpen.value === 1 ? '토너먼트 참가' : '토너먼트 불참'}
                    pinnedArgType={null}
                    extraArgs={{ value: enrollOpen.value }}
                    onReserved={() => fetchData()}
                />
            )}
            {toast && (
                <div role="status" style={{ position: 'fixed', bottom: 16, left: '50%', transform: 'translateX(-50%)', background: 'var(--surface-raised)', padding: '8px 16px', borderRadius: 8 }} onClick={() => setToast(null)}>
                    {toast}
                </div>
            )}
        </Shell>
    );
}
