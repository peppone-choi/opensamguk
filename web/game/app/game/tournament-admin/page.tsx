'use client';

import { useEffect, useState, useCallback } from 'react';
import Shell from '../../../components/Shell';
import GameCard from '../../../components/GameCard';
import GameTable from '../../../components/GameTable';
import StatusBadge from '../../../components/StatusBadge';
import { api } from '../../../lib/api';
import {
    TOAST_DURATION_MS,
    TOURNAMENT_STATUS_LABEL,
    TOURNAMENT_STATUS_VARIANT,
    DEFAULT_ADMIN_GENERAL_ID,
} from '../../../lib/constants';

interface TournamentEntry {
    id: number;
    generalId: number;
    generalName: string;
    nationId: number;
    nationName: string;
    round: number;
    seed: number;
    eliminated: boolean;
}

interface TournamentMatch {
    id: number;
    round: number;
    bracket: string;
    attackerId: number;
    attackerName: string;
    defenderId: number;
    defenderName: string;
    winnerId?: number;
    winnerName?: string;
    status: string;
}

interface TournamentData {
    entries: TournamentEntry[];
    matches: TournamentMatch[];
}



export default function TournamentAdminPage() {
    const [entries, setEntries] = useState<TournamentEntry[]>([]);
    const [matches, setMatches] = useState<TournamentMatch[]>([]);
    const [generalId, setGeneralId] = useState<number>(DEFAULT_ADMIN_GENERAL_ID);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string>('');
    const [toast, setToast] = useState<string>('');
    const [activeTab, setActiveTab] = useState<'entries' | 'matches' | 'admin'>('entries');

    const fetchData = useCallback(async () => {
        setLoading(true);
        try {
            const data = await api.tournament<TournamentData>();
            setEntries(data.entries ?? []);
            setMatches(data.matches ?? []);
            setError('');
        } catch {
            setError('토너먼트 데이터를 불러올 수 없습니다.');
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => {
        fetchData();
    }, [fetchData]);

    // TODO: tournament_start/advance/reset는 BE handler 미포팅 (silent no-op).
    //       PHP hwe/sammo/API/Admin/Tournament.php 포팅 후 제거.
    async function startTournament() {
        setToast('토너먼트 시작은 아직 구현되지 않았습니다.');
        setTimeout(() => setToast(''), TOAST_DURATION_MS);
    }

    async function advanceRound() {
        setToast('라운드 진행은 아직 구현되지 않았습니다.');
        setTimeout(() => setToast(''), TOAST_DURATION_MS);
    }

    async function resetTournament() {
        setToast('토너먼트 초기화는 아직 구현되지 않았습니다.');
        setTimeout(() => setToast(''), TOAST_DURATION_MS);
    }

    const activeEntries = entries.filter(e => !e.eliminated);
    const eliminatedEntries = entries.filter(e => e.eliminated);

    const entryRows = activeEntries.map(e => [
        e.seed.toString(),
        e.generalName,
        e.nationName,
        `R${e.round}`,
        <StatusBadge key={e.id} variant="jade">생존</StatusBadge>,
    ]);

    const matchRows = matches.map(m => [
        `R${m.round}`,
        m.bracket,
        m.attackerName,
        m.defenderName,
        m.winnerName ?? '-',
        <StatusBadge key={m.id} variant={TOURNAMENT_STATUS_VARIANT[m.status] ?? 'muted'}>
            {TOURNAMENT_STATUS_LABEL[m.status] ?? m.status}
        </StatusBadge>,
    ]);

    return (
        <Shell>
            <h1 style={{ fontSize: 'var(--text-2xl)', fontWeight: 700, marginBottom: 'var(--space-md)' }}>
                토너먼트 관리
            </h1>

            <div style={{ display: 'flex', gap: 'var(--space-md)', marginBottom: 'var(--space-md)', flexWrap: 'wrap', alignItems: 'center' }}>
                <label style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-sm)' }}>
                    <span style={{ fontSize: 'var(--text-sm)', color: 'var(--text-secondary)' }}>관리자 장수 ID</span>
                    <input type="number" style={{ width: '5rem' }} value={generalId} onChange={e => setGeneralId(Number(e.target.value))} />
                </label>
                <button onClick={fetchData}>새로고침</button>
            </div>

            {loading && <p style={{ color: 'var(--text-muted)' }}>로딩 중...</p>}
            {error && <p style={{ color: 'var(--crimson)' }}>{error}</p>}

            {toast && (
                <div className="toast" style={{ position: 'fixed', top: 'var(--space-md)', right: 'var(--space-md)', zIndex: 200 }}>
                    {toast}
                </div>
            )}

            {/* Tabs */}
            <div style={{ display: 'flex', gap: 'var(--space-xs)', marginBottom: 'var(--space-md)', borderBottom: '1px solid var(--border-subtle)' }}>
                {(['entries', 'matches', 'admin'] as const).map(tab => (
                    <button
                        key={tab}
                        onClick={() => setActiveTab(tab)}
                        style={{
                            background: 'transparent',
                            borderBottom: activeTab === tab ? '2px solid var(--gold)' : '2px solid transparent',
                            color: activeTab === tab ? 'var(--gold)' : 'var(--text-secondary)',
                            borderRadius: 0,
                            padding: 'var(--space-sm) var(--space-md)',
                        }}
                    >
                        {tab === 'entries' && '참가자'}
                        {tab === 'matches' && '대진표'}
                        {tab === 'admin' && '관리'}
                    </button>
                ))}
            </div>

            {activeTab === 'entries' && (
                <>
                    <h2 style={{ fontSize: 'var(--text-lg)', fontWeight: 600, marginBottom: 'var(--space-sm)' }}>
                        참가자 목록
                        <span style={{ fontSize: 'var(--text-sm)', color: 'var(--text-muted)', marginLeft: 'var(--space-sm)' }}>
                            ({activeEntries.length}명 생존 / {eliminatedEntries.length}명 탈락)
                        </span>
                    </h2>
                    {entryRows.length > 0 ? (
                        <GameTable
                            headers={['시드', '장수', '국가', '라운드', '상태']}
                            rows={entryRows}
                        />
                    ) : (
                        <p style={{ color: 'var(--text-muted)' }}>참가자가 없습니다.</p>
                    )}

                    {eliminatedEntries.length > 0 && (
                        <>
                            <h2 style={{ fontSize: 'var(--text-lg)', fontWeight: 600, marginBottom: 'var(--space-sm)', marginTop: 'var(--space-lg)', color: 'var(--text-secondary)' }}>
                                탈락자
                            </h2>
                            <GameTable
                                headers={['시드', '장수', '국가', '라운드', '상태']}
                                rows={eliminatedEntries.map(e => [
                                    e.seed.toString(),
                                    e.generalName,
                                    e.nationName,
                                    `R${e.round}`,
                                    <StatusBadge key={e.id} variant="muted">탈락</StatusBadge>,
                                ])}
                            />
                        </>
                    )}
                </>
            )}

            {activeTab === 'matches' && (
                <>
                    <h2 style={{ fontSize: 'var(--text-lg)', fontWeight: 600, marginBottom: 'var(--space-sm)' }}>
                        대진표
                    </h2>
                    {matchRows.length > 0 ? (
                        <GameTable
                            headers={['라운드', '브래킷', '공격자', '방어자', '승자', '상태']}
                            rows={matchRows}
                        />
                    ) : (
                        <p style={{ color: 'var(--text-muted)' }}>대진이 없습니다.</p>
                    )}
                </>
            )}

            {activeTab === 'admin' && (
                <GameCard>
                    <h2 style={{ fontSize: 'var(--text-lg)', fontWeight: 600, marginBottom: 'var(--space-md)' }}>
                        토너먼트 관리
                    </h2>
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-sm)' }}>
                        <div style={{ display: 'flex', gap: 'var(--space-sm)', flexWrap: 'wrap' }}>
                            <button
                                onClick={startTournament}
                                style={{ background: 'var(--jade)', color: 'white', fontWeight: 600 }}
                            >
                                토너먼트 시작
                            </button>
                            <button
                                onClick={advanceRound}
                                style={{ background: 'var(--gold)', color: 'var(--bg-base)', fontWeight: 600 }}
                            >
                                다음 라운드 진행
                            </button>
                            <button
                                onClick={resetTournament}
                                style={{ background: 'var(--crimson)', color: 'white', fontWeight: 600 }}
                            >
                                초기화
                            </button>
                        </div>
                        <p style={{ fontSize: 'var(--text-sm)', color: 'var(--text-muted)' }}>
                            토너먼트 시작: 참가자 등록 및 1라운드 대진 생성<br />
                            다음 라운드: 현재 라운드 종료 후 다음 라운드 진행<br />
                            초기화: 모든 토너먼트 데이터 삭제 (되돌릴 수 없음)
                        </p>
                    </div>
                </GameCard>
            )}
        </Shell>
    );
}
