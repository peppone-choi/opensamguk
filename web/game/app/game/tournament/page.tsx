'use client';

import { useEffect, useState } from 'react';
import Shell from '../../../components/Shell';
import GameCard from '../../../components/GameCard';
import GameTable from '../../../components/GameTable';
import StatusBadge from '../../../components/StatusBadge';
import { api } from '../../../lib/api';
import { formatNumber } from '../../../lib/format';

interface TournamentEntry {
    rank: number;
    generalName: string;
    nationName: string;
    wins: number;
    losses: number;
    score: number;
}

interface BracketMatch {
    p1: string;
    p2: string;
    winner?: string;
}

interface BracketRound {
    round: number;
    matches: BracketMatch[];
}

interface TournamentData {
    name: string;
    round: number;
    totalRounds: number;
    status: 'upcoming' | 'ongoing' | 'finished';
    entries: TournamentEntry[];
    bracket?: BracketRound[];
}

export default function TournamentPage() {
    const [data, setData] = useState<TournamentData | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState('');

    const fetchData = async () => {
        setLoading(true);
        setError('');
        try {
            const res = await api.tournament<TournamentData>();
            setData(res);
        } catch {
            setError('토너먼트 정보를 불러올 수 없습니다.');
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        fetchData();
    }, []);

    if (loading) {
        return (
            <Shell>
                <div className="page-content">
                    <h1>토너먼트</h1>
                    <p className="text-muted">로딩 중...</p>
                </div>
            </Shell>
        );
    }

    if (error) {
        return (
            <Shell>
                <div className="page-content">
                    <h1>토너먼트</h1>
                    <div className="error-state">
                        <p>{error}</p>
                        <button onClick={fetchData}>다시 시도</button>
                    </div>
                </div>
            </Shell>
        );
    }

    if (!data) {
        return (
            <Shell>
                <div className="page-content">
                    <h1>토너먼트</h1>
                    <p className="text-muted">진행 중인 토너먼트가 없습니다.</p>
                </div>
            </Shell>
        );
    }

    const statusVariant = data.status === 'ongoing' ? 'jade' : data.status === 'finished' ? 'muted' : 'gold';
    const statusLabel = data.status === 'ongoing' ? '진행 중' : data.status === 'finished' ? '종료' : '예정';

    const headers = ['순위', '장수', '국가', '승', '패', '점수'];
    const rows = data.entries.map(e => [
        e.rank,
        e.generalName,
        e.nationName,
        e.wins,
        e.losses,
        formatNumber(e.score),
    ]);

    return (
        <Shell>
            <div className="page-content">
                <h1>토너먼트</h1>

                <GameCard className="tournament-header">
                    <div className="card-header">
                        <h2>{data.name}</h2>
                        <StatusBadge variant={statusVariant}>{statusLabel}</StatusBadge>
                    </div>
                    <div className="tournament-meta">
                        <span>라운드: {data.round} / {data.totalRounds}</span>
                        <span>참가자: {data.entries.length}명</span>
                    </div>
                </GameCard>

                <h2>순위</h2>
                {data.entries.length === 0 ? (
                    <p className="text-muted">참가자가 없습니다.</p>
                ) : (
                    <GameTable headers={headers} rows={rows} />
                )}

                {data.bracket && data.bracket.length > 0 && (
                    <>
                        <h2>대진표</h2>
                        <div className="bracket">
                            {data.bracket.map((round, ri) => (
                                <div key={ri} className="bracket-round">
                                    <h3>{round.round}라운드</h3>
                                    {round.matches.map((m, mi) => (
                                        <div key={mi} className="bracket-match">
                                            <span className={m.winner === m.p1 ? 'bracket-winner' : ''}>{m.p1}</span>
                                            <span className="bracket-vs">vs</span>
                                            <span className={m.winner === m.p2 ? 'bracket-winner' : ''}>{m.p2}</span>
                                        </div>
                                    ))}
                                </div>
                            ))}
                        </div>
                    </>
                )}
            </div>
        </Shell>
    );
}
