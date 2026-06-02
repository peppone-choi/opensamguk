'use client';

import { useEffect, useState, useCallback } from 'react';
import Shell from '../../../components/Shell';
import GameCard from '../../../components/GameCard';
import StatusBadge from '../../../components/StatusBadge';
import { api } from '../../../lib/api';

interface BettingItem {
    bettingId: string;
    type: string;
    openYearMonth: string;
    targetNations: { id: number; name: string; odds: number }[];
    closeCondition: string;
    status: string;
    totalPool: number;
    isExclusivePayout: boolean;
}

interface NationOption {
    id: number;
    name: string;
    color: string;
}

const TYPE_LABEL: Record<string, string> = {
    NATION_STRENGTH: '국가 강약',
    TOURNAMENT: '토너먼트',
    CUSTOM: '커스텀',
};

const STATUS_VARIANT: Record<string, 'jade' | 'gold' | 'muted'> = {
    OPEN: 'jade',
    CLOSED: 'gold',
    PAYOUT_DONE: 'muted',
};

const STATUS_LABEL: Record<string, string> = {
    OPEN: '진행 중',
    CLOSED: '마감',
    PAYOUT_DONE: '보상 완료',
};

export default function BettingPage() {
    const [bettings, setBettings] = useState<BettingItem[]>([]);
    const [nations, setNations] = useState<NationOption[]>([]);
    const [generalId, setGeneralId] = useState<number>(1);
    const [betAmount, setBetAmount] = useState<Record<string, string>>({});
    const [selectedNation, setSelectedNation] = useState<Record<string, number>>({});
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string>('');
    const [toast, setToast] = useState<string>('');

    const fetchData = useCallback(async () => {
        setLoading(true);
        try {
            const [bData, nData] = await Promise.all([
                api.betting<BettingItem[]>(),
                api.rankings.kingdoms<NationOption[]>(),
            ]);
            setBettings(bData);
            setNations(nData);
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

    async function placeBet(bettingId: string) {
        const amount = Number(betAmount[bettingId]);
        const nationId = selectedNation[bettingId];
        if (!amount || amount <= 0) {
            setToast('베팅 금액을 입력하세요.');
            setTimeout(() => setToast(''), 3000);
            return;
        }
        if (!nationId) {
            setToast('베팅할 국가를 선택하세요.');
            setTimeout(() => setToast(''), 3000);
            return;
        }
        try {
            const data = await api.command<{ status: string; reason?: string }>('bet', { bettingId, nationId, amount });
            setToast(data.status === 'AVAILABLE' ? '베팅이 접수되었습니다.' : (data.reason ?? '베팅할 수 없습니다.'));
        } catch {
            setToast('베팅 요청에 실패했습니다.');
        }
        setTimeout(() => setToast(''), 3000);
        fetchData();
    }

    const openBettings = bettings.filter(b => b.status === 'OPEN');
    const closedBettings = bettings.filter(b => b.status !== 'OPEN');

    return (
        <Shell>
            <h1 style={{ fontSize: 'var(--text-2xl)', fontWeight: 700, marginBottom: 'var(--space-md)' }}>베팅</h1>

            <div style={{ display: 'flex', gap: 'var(--space-md)', marginBottom: 'var(--space-md)', flexWrap: 'wrap', alignItems: 'center' }}>
                <label style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-sm)' }}>
                    <span style={{ fontSize: 'var(--text-sm)', color: 'var(--text-secondary)' }}>장수 ID</span>
                    <input
                        type="number"
                        style={{ width: '5rem' }}
                        value={generalId}
                        onChange={e => setGeneralId(Number(e.target.value))}
                    />
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

            <h2 style={{ fontSize: 'var(--text-lg)', fontWeight: 600, marginBottom: 'var(--space-sm)' }}>진행 중인 베팅</h2>
            {openBettings.length === 0 && !loading && (
                <p style={{ color: 'var(--text-muted)', marginBottom: 'var(--space-xl)' }}>진행 중인 베팅이 없습니다.</p>
            )}
            <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-md)', marginBottom: 'var(--space-xl)' }}>
                {openBettings.map(b => {
                    const variant = STATUS_VARIANT[b.status] ?? 'muted';
                    return (
                        <GameCard key={b.bettingId}>
                            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 'var(--space-sm)' }}>
                                <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-sm)' }}>
                                    <StatusBadge variant="gold">{TYPE_LABEL[b.type] ?? b.type}</StatusBadge>
                                    <span style={{ fontWeight: 500 }}>{b.bettingId}</span>
                                </div>
                                <StatusBadge variant={variant}>{STATUS_LABEL[b.status] ?? b.status}</StatusBadge>
                            </div>
                            <div style={{ fontSize: 'var(--text-sm)', color: 'var(--text-muted)', marginBottom: 'var(--space-sm)' }}>
                                개시: {b.openYearMonth} · 총 풀: {b.totalPool.toLocaleString()} · {b.isExclusivePayout ? '독점 보상' : 'N등분'}
                            </div>

                            <div style={{ marginBottom: 'var(--space-sm)' }}>
                                <h3 style={{ fontSize: 'var(--text-sm)', fontWeight: 500, marginBottom: 'var(--space-xs)', color: 'var(--text-secondary)' }}>대상 국가 / 배당률</h3>
                                <div style={{ display: 'flex', flexWrap: 'wrap', gap: 'var(--space-sm)' }}>
                                    {b.targetNations.map(n => {
                                        const selected = selectedNation[b.bettingId] === n.id;
                                        return (
                                            <label
                                                key={n.id}
                                                style={{
                                                    display: 'flex',
                                                    alignItems: 'center',
                                                    gap: 'var(--space-xs)',
                                                    padding: 'var(--space-xs) var(--space-sm)',
                                                    borderRadius: 'var(--radius-sm)',
                                                    border: `1px solid ${selected ? 'var(--gold)' : 'var(--border-subtle)'}`,
                                                    background: selected ? 'rgba(201,162,39,0.1)' : 'var(--bg-hover)',
                                                    cursor: 'pointer',
                                                    fontSize: 'var(--text-sm)',
                                                }}
                                            >
                                                <input
                                                    type="radio"
                                                    name={`nation-${b.bettingId}`}
                                                    style={{ position: 'absolute', opacity: 0 }}
                                                    checked={selected}
                                                    onChange={() => setSelectedNation(prev => ({ ...prev, [b.bettingId]: n.id }))}
                                                />
                                                <span>{n.name}</span>
                                                <span style={{ color: 'var(--gold)' }}>x{n.odds.toFixed(2)}</span>
                                            </label>
                                        );
                                    })}
                                </div>
                            </div>

                            <div style={{ display: 'flex', gap: 'var(--space-sm)', alignItems: 'center' }}>
                                <input
                                    type="number"
                                    placeholder="베팅 금액"
                                    style={{ width: '8rem' }}
                                    value={betAmount[b.bettingId] ?? ''}
                                    onChange={e => setBetAmount(prev => ({ ...prev, [b.bettingId]: e.target.value }))}
                                />
                                <button onClick={() => placeBet(b.bettingId)}>베팅</button>
                            </div>
                        </GameCard>
                    );
                })}
            </div>

            {closedBettings.length > 0 && (
                <>
                    <h2 style={{ fontSize: 'var(--text-lg)', fontWeight: 600, marginBottom: 'var(--space-sm)', color: 'var(--text-secondary)' }}>종료된 베팅</h2>
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-sm)', opacity: 0.6 }}>
                        {closedBettings.map(b => {
                            const variant = STATUS_VARIANT[b.status] ?? 'muted';
                            return (
                                <GameCard key={b.bettingId}>
                                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                                        <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-sm)' }}>
                                            <StatusBadge variant="muted">{TYPE_LABEL[b.type] ?? b.type}</StatusBadge>
                                            <span style={{ fontWeight: 500 }}>{b.bettingId}</span>
                                        </div>
                                        <StatusBadge variant={variant}>{STATUS_LABEL[b.status] ?? b.status}</StatusBadge>
                                    </div>
                                    <div style={{ fontSize: 'var(--text-sm)', color: 'var(--text-muted)', marginTop: 'var(--space-xs)' }}>
                                        총 풀: {b.totalPool.toLocaleString()} · {b.closeCondition}
                                    </div>
                                </GameCard>
                            );
                        })}
                    </div>
                </>
            )}
        </Shell>
    );
}
