'use client';

import { useEffect, useMemo, useState } from 'react';
import Shell from '../../../components/Shell';
import GameCard from '../../../components/GameCard';
import GameTable from '../../../components/GameTable';
import StatusBadge from '../../../components/StatusBadge';
import CommandModal from '../../../components/CommandModal';
import { api } from '../../../lib/api';
import { formatNumber } from '../../../lib/format';
import type { FrontInfoResponse } from '../../../lib/types';
import type {
    TournamentResponse,
    TournamentEntrant,
    TournamentBracketMatch,
    TournamentRankRow,
} from '../../../types/game';

// 토너먼트 (page 12) — view-only. Consumes the foundation read contract
// api.tournamentView() → TournamentResponse (GET /api/tournament). Legacy: b_tournament.php
// + func_tournament.php + utilGame/formatTournament.ts. The betting bracket (page 11) can
// consume the SAME contract for its 16강 상황 panel.
//
// READ-ONLY this wave. 참가 (enroll) is an INTAKE command deferred to Wave C — no mutation
// wiring here beyond what already exists. Admin actions live on /game/tournament-admin.
//
// EMPTY-SAFE: scenario_1010 has no active tournament → the controller returns a zeroed shape
// (state 0, empty entrants/bracket/rankings) (200, never 500). Every array maps over [] and
// renders an empty table; never crashes.
//
// Verbatim Korean parity (byte-for-byte from the legacy PHP/Vue):
//   - state text  : func_tournament.php getTournament() / formatTournament.ts stepMap
//   - type text   : 전력전 / 통솔전 / 일기토 / 설전  (+ 종합 / 통솔 / 무력 / 지력)
//   - section hdrs: 16강 승자전 / 조별 본선 순위 / 조별 예선 순위
//   - group cols  : 순 · 장수 · {tp2} · 경 · 승 · 무 · 패 · 점 · 득
//   - group names : 一조 … 八조
//   - footer rules: b_tournament.php trailing <font> block

// 토너먼트 진행 상태 (state 0-10) — getTournament() byte-verbatim.
// Index beyond the array degrades gracefully to a passthrough label.
const STATE_TEXT: string[] = [
    '경기 없음',
    '참가 모집중',
    '예선 진행중',
    '본선 추첨중',
    '본선 진행중',
    '16강 배정중',
    '베팅 진행중',
    '16강 진행중',
    '8강 진행중',
    '4강 진행중',
    '결승 진행중',
];

// state 0 = magenta(없음), the rest are orange(진행중) per getTournament().
function stateVariant(state: number): 'gold' | 'crimson' | 'jade' | 'muted' {
    if (state <= 0) return 'muted';
    return 'gold';
}

// tnmt_type → 능력치 컬럼 표기 (tp2). b_tournament.php switch($admin['tnmt_type']).
//   전력전→종합 / 통솔전→통솔 / 일기토→무력 / 설전→지력
const ABILITY_LABEL: Record<string, string> = {
    전력전: '종합',
    통솔전: '통솔',
    일기토: '무력',
    설전: '지력',
};

// 一 ~ 八 (1조 ~ 8조). b_tournament.php $num.
const GROUP_NUM = ['一', '二', '三', '四', '五', '六', '七', '八'];

// 16강 single-elimination rounds, earliest → latest (round number = remaining slots).
// b_tournament.php prints 16강 / 8강 / 4강 / 결승 layers from grp>=20 down to grp>=60.
const BRACKET_ROUNDS: { round: number; label: string }[] = [
    { round: 16, label: '16강' },
    { round: 8, label: '8강' },
    { round: 4, label: '4강' },
    { round: 2, label: '결승' },
];

export default function TournamentPage() {
    const [data, setData] = useState<TournamentResponse | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState('');
    // F4 C2 — own identity + enroll modal (participation toggle).
    const [generalId, setGeneralId] = useState<number | null>(null);
    const [nationId, setNationId] = useState<number | null>(null);
    const [enrollOpen, setEnrollOpen] = useState<{ value: number } | null>(null);
    const [toast, setToast] = useState<string | null>(null);

    const fetchData = () => {
        setLoading(true);
        setError('');
        api
            .tournamentView()
            .then((res: TournamentResponse) => setData(res ?? null))
            .catch(() => setError('토너먼트 정보를 불러올 수 없습니다.'))
            .finally(() => setLoading(false));
    };

    useEffect(fetchData, []);

    useEffect(() => {
        api.frontInfo()
            .then((fi: FrontInfoResponse) => {
                setGeneralId(fi.general.generalId);
                setNationId(fi.general.nationId);
            })
            .catch(() => { /* identity optional — enroll button hides when absent */ });
    }, []);

    // EMPTY-SAFE accessors — the controller may zero-fill, but never trust shape at runtime.
    const entrants: TournamentEntrant[] = Array.isArray(data?.entrants) ? data!.entrants : [];
    const bracket: TournamentBracketMatch[] = Array.isArray(data?.bracket) ? data!.bracket : [];
    const tnmtType = data?.tnmtTypeText ?? '전력전';
    const abilityLabel = ABILITY_LABEL[tnmtType] ?? '종합';

    // 조별 순위 — group by `group` (0-7). Insertion order preserved (do NOT re-key by id);
    // the controller already orders rows within a group (승점/득실/시드). 8 groups always shown.
    const groups = useMemo(() => {
        const byGroup = new Map<number, TournamentEntrant[]>();
        for (const e of entrants) {
            const g = e.group ?? 0;
            if (!byGroup.has(g)) byGroup.set(g, []);
            byGroup.get(g)!.push(e);
        }
        return Array.from({ length: 8 }, (_, i) => ({
            group: i,
            label: `${GROUP_NUM[i]}조`,
            rows: byGroup.get(i) ?? [],
        }));
    }, [entrants]);

    // 16강 상황 — group bracket matches by round (16/8/4/2), preserving matchIdx order.
    const bracketRounds = useMemo(() => {
        return BRACKET_ROUNDS.map(({ round, label }) => ({
            round,
            label,
            matches: bracket
                .filter((m) => m.round === round)
                .slice()
                .sort((a, b) => a.matchIdx - b.matchIdx),
        }));
    }, [bracket]);

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
                    onClick={fetchData}
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
    const stateText = STATE_TEXT[state] ?? `TOURNAMENT_TYPE_ERR_${state}`;

    // 조별 순위 컬럼 — 순 · 장수 · {tp2} · 경 · 승 · 무 · 패 · 점 · 득 (printRow header).
    //   경 = 경기수(win+draw+lose) · 점 = 승점(win*3+draw) · 득 = 득실(gl).
    const groupHeaders = ['순', '장수', abilityLabel, '경', '승', '무', '패', '점', '득'];
    const groupRow = (e: TournamentEntrant, idx: number) => {
        const games = (e.win ?? 0) + (e.draw ?? 0) + (e.lose ?? 0);
        const points = (e.win ?? 0) * 3 + (e.draw ?? 0);
        // The standings ability column ({tp2}) tracks tnmt_type; the entrant contract only carries
        // win/draw/lose, so the ability + 득실 cells render '-' until the read shape supplies them.
        return [
            idx + 1,
            <span key={`g-${e.generalId}-${idx}`} style={{ color: e.nationColor || undefined }}>
                {e.generalName}
            </span>,
            '-',
            formatNumber(games),
            formatNumber(e.win ?? 0),
            formatNumber(e.draw ?? 0),
            formatNumber(e.lose ?? 0),
            formatNumber(points),
            '-',
        ];
    };

    // 4 ranking tables — 전력전(종합) / 통솔전(통솔) / 일기토(무력) / 설전(지력).
    const rankingTables: { title: string; rows: TournamentRankRow[] }[] = [
        { title: '전력전', rows: data?.rankings?.total ?? [] },
        { title: '통솔전', rows: data?.rankings?.leadership ?? [] },
        { title: '일기토', rows: data?.rankings?.strength ?? [] },
        { title: '설전', rows: data?.rankings?.intel ?? [] },
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
                        onClick={fetchData}
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

            {/* 조별 순위 (一조 ~ 八조) */}
            <h2
                style={{
                    fontSize: 'var(--text-lg)',
                    fontWeight: 600,
                    margin: 'var(--space-lg) 0 var(--space-sm)',
                }}
            >
                조별 순위
            </h2>
            {entrants.length === 0 ? (
                <p style={{ color: 'var(--text-muted)' }}>참가자가 없습니다.</p>
            ) : (
                <div
                    style={{
                        display: 'grid',
                        gridTemplateColumns: 'repeat(auto-fill, minmax(260px, 1fr))',
                        gap: 'var(--space-md)',
                    }}
                >
                    {groups.map((g) => (
                        <GameCard key={g.group}>
                            <h3
                                style={{
                                    fontSize: 'var(--text-sm)',
                                    fontWeight: 600,
                                    marginBottom: 'var(--space-sm)',
                                    color: 'var(--text-secondary)',
                                }}
                            >
                                {g.label}
                            </h3>
                            {g.rows.length === 0 ? (
                                <p style={{ color: 'var(--text-muted)', fontSize: 'var(--text-sm)' }}>-</p>
                            ) : (
                                <GameTable
                                    headers={groupHeaders}
                                    rows={g.rows.map((e, i) => groupRow(e, i))}
                                />
                            )}
                        </GameCard>
                    ))}
                </div>
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
                {rankingTables.map((t) => (
                    <GameCard key={t.title}>
                        <h3
                            style={{
                                fontSize: 'var(--text-sm)',
                                fontWeight: 600,
                                marginBottom: 'var(--space-sm)',
                                color: 'var(--text-secondary)',
                            }}
                        >
                            {t.title}
                        </h3>
                        {t.rows.length === 0 ? (
                            <p style={{ color: 'var(--text-muted)', fontSize: 'var(--text-sm)' }}>
                                순위 정보가 없습니다.
                            </p>
                        ) : (
                            <GameTable
                                headers={['순위', '장수', '국가', '점수']}
                                rows={t.rows.map((r) => [
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
