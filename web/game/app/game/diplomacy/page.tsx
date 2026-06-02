'use client';

import { useEffect, useState, useCallback } from 'react';
import Shell from '../../../components/Shell';
import GameCard from '../../../components/GameCard';
import StatusBadge from '../../../components/StatusBadge';
import GameTable from '../../../components/GameTable';
import { api } from '../../../lib/api';

interface Nation {
    id: number;
    name: string;
    color: string;
}

interface DiplomacyRow {
    id: number;
    srcNationId: number;
    destNationId: number;
    state: number;
    term: number;
}

const STATE_VARIANT: Record<number, 'crimson' | 'gold' | 'jade' | 'muted'> = {
    0: 'crimson',
    1: 'gold',
    2: 'jade',
    7: 'jade',
};

const STATE_LABEL: Record<number, string> = {
    0: '교전',
    1: '선전포고',
    2: '통상',
    7: '불가침',
};

export default function DiplomacyPage() {
    const [nations, setNations] = useState<Nation[]>([]);
    const [diplomacy, setDiplomacy] = useState<DiplomacyRow[]>([]);
    const [myNationId, setMyNationId] = useState<number>(1);
    const [generalId, setGeneralId] = useState<number>(1);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string>('');
    const [toast, setToast] = useState<string>('');

    const fetchData = useCallback(async () => {
        try {
            const [nData, dData] = await Promise.all([
                api.rankings.kingdoms<Nation[]>(),
                api.diplomacy<DiplomacyRow[]>(),
            ]);
            setNations(nData);
            setDiplomacy(dData);
            setError('');
        } catch {
            setError('데이터를 불러올 수 없습니다.');
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => {
        fetchData();
    }, [fetchData]);

    useEffect(() => {
        const base = process.env.NEXT_PUBLIC_GAME_API_URL ?? 'http://localhost:8081';
        const es = new EventSource(`${base}/realtime/events`);
        es.addEventListener('realtime', () => fetchData());
        es.onerror = () => es.close();
        return () => es.close();
    }, [fetchData]);

    async function sendCommand(code: string, argJson?: string) {
        try {
            const args = argJson ? JSON.parse(argJson) : {};
            const data = await api.command<{ status: string; reason?: string }>(code, args);
            setToast(data.status === 'AVAILABLE' ? '명령이 접수되었습니다.' : (data.reason ?? '명령을 실행할 수 없습니다.'));
        } catch {
            setToast('명령 요청에 실패했습니다.');
        }
        setTimeout(() => setToast(''), 3000);
    }

    function getState(src: number, dest: number): number {
        const row = diplomacy.find(d => d.srcNationId === src && d.destNationId === dest);
        return row?.state ?? -1;
    }

    function getTerm(src: number, dest: number): number {
        const row = diplomacy.find(d => d.srcNationId === src && d.destNationId === dest);
        return row?.term ?? 0;
    }

    const sortedNations = [...nations].sort((a, b) => a.id - b.id);

    return (
        <Shell>
            <h1 style={{ fontSize: 'var(--text-2xl)', fontWeight: 700, marginBottom: 'var(--space-md)' }}>외교 관계</h1>

            <div style={{ display: 'flex', gap: 'var(--space-md)', marginBottom: 'var(--space-md)', flexWrap: 'wrap' }}>
                <label style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-sm)' }}>
                    <span style={{ fontSize: 'var(--text-sm)', color: 'var(--text-secondary)' }}>내 국가</span>
                    <select value={myNationId} onChange={e => setMyNationId(Number(e.target.value))}>
                        {sortedNations.map(n => (
                            <option key={n.id} value={n.id}>{n.name}</option>
                        ))}
                    </select>
                </label>
                <label style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-sm)' }}>
                    <span style={{ fontSize: 'var(--text-sm)', color: 'var(--text-secondary)' }}>장수 ID</span>
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

            <GameCard>
                <div style={{ overflowX: 'auto' }}>
                    <table className="game-table">
                        <thead>
                            <tr>
                                <th>국가</th>
                                {sortedNations.map(n => (
                                    <th key={n.id} style={{ textAlign: 'center', minWidth: 80 }}>{n.name}</th>
                                ))}
                            </tr>
                        </thead>
                        <tbody>
                            {sortedNations.map(src => (
                                <tr key={src.id}>
                                    <td style={{ fontWeight: 500, color: src.color }}>{src.name}</td>
                                    {sortedNations.map(dest => {
                                        if (src.id === dest.id) {
                                            return <td key={dest.id} style={{ textAlign: 'center', color: 'var(--text-muted)' }}>—</td>;
                                        }
                                        const state = getState(src.id, dest.id);
                                        const term = getTerm(src.id, dest.id);
                                        const variant = STATE_VARIANT[state] ?? 'muted';
                                        return (
                                            <td key={dest.id} style={{ textAlign: 'center' }}>
                                                <div style={{ marginBottom: 'var(--space-xs)' }}>
                                                    <StatusBadge variant={variant}>
                                                        {STATE_LABEL[state] ?? '중립'}
                                                        {term > 0 && <span style={{ marginLeft: 4, opacity: 0.8 }}>({term})</span>}
                                                    </StatusBadge>
                                                </div>
                                                {src.id === myNationId && (
                                                    <div style={{ display: 'flex', gap: 4, justifyContent: 'center', flexWrap: 'wrap' }}>
                                                        {state === 0 && (
                                                            <button
                                                                style={{ fontSize: 'var(--text-xs)', padding: '2px 6px' }}
                                                                onClick={() => sendCommand('che_종전제의', JSON.stringify({ targetNationId: dest.id }))}
                                                            >
                                                                종전
                                                            </button>
                                                        )}
                                                        {state === 7 && (
                                                            <button
                                                                style={{ fontSize: 'var(--text-xs)', padding: '2px 6px' }}
                                                                onClick={() => sendCommand('che_불가침파기제의', JSON.stringify({ targetNationId: dest.id }))}
                                                            >
                                                                파기
                                                            </button>
                                                        )}
                                                        {(state === -1 || state === 2) && (
                                                            <>
                                                                <button
                                                                    style={{ fontSize: 'var(--text-xs)', padding: '2px 6px' }}
                                                                    onClick={() => sendCommand('che_불가침제의', JSON.stringify({ targetNationId: dest.id }))}
                                                                >
                                                                    불가침
                                                                </button>
                                                                <button
                                                                    style={{ fontSize: 'var(--text-xs)', padding: '2px 6px' }}
                                                                    onClick={() => sendCommand('che_선전포고', JSON.stringify({ targetNationId: dest.id }))}
                                                                >
                                                                    선전
                                                                </button>
                                                            </>
                                                        )}
                                                    </div>
                                                )}
                                            </td>
                                        );
                                    })}
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
            </GameCard>
        </Shell>
    );
}
