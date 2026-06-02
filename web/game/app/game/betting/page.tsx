'use client';

import { useEffect, useState, useCallback } from 'react';
import Shell from '../../../components/Shell';
import GameCard from '../../../components/GameCard';
import StatusBadge from '../../../components/StatusBadge';
import CommandModal from '../../../components/CommandModal';
import { api } from '../../../lib/api';
import { useFrontInfo } from '../../../hooks/useFrontInfo';

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
    const { frontInfo, refresh } = useFrontInfo();
    const generalId = frontInfo?.general.generalId ?? null;
    const nationId = frontInfo?.general.nationId;
    const [bettings, setBettings] = useState<BettingItem[]>([]);
    const [nations, setNations] = useState<NationOption[]>([]);
    const [selectedNation, setSelectedNation] = useState<Record<string, number>>({});
    // The betting whose 베팅 modal is open (null = closed). amount sub-form lives in CommandModal.
    const [betTarget, setBetTarget] = useState<BettingItem | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string>('');
    const [toast, setToast] = useState<string>('');

    function showToast(msg: string) {
        setToast(msg);
        setTimeout(() => setToast(''), 3000);
    }

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
        const es = new EventSource('/api/game/sse/turn');
        es.addEventListener('turnCompleted', () => fetchData());
        es.onerror = () => es.close();
        return () => es.close();
    }, [fetchData]);

    function openBetModal(b: BettingItem) {
        if (generalId == null) {
            showToast('장수가 없어 베팅할 수 없습니다.');
            return;
        }
        if (!selectedNation[b.bettingId]) {
            showToast('베팅할 국가를 선택하세요.');
            return;
        }
        setBetTarget(b);
    }

    const openBettings = bettings.filter(b => b.status === 'OPEN');
    const closedBettings = bettings.filter(b => b.status !== 'OPEN');

    return (
        <Shell>
            <h1 style={{ fontSize: 'var(--text-2xl)', fontWeight: 700, marginBottom: 'var(--space-md)' }}>베팅</h1>

            <div style={{ display: 'flex', gap: 'var(--space-md)', marginBottom: 'var(--space-md)', flexWrap: 'wrap', alignItems: 'center' }}>
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
                                <button onClick={() => openBetModal(b)}>베팅</button>
                                <span style={{ fontSize: 'var(--text-xs)', color: 'var(--text-muted)' }}>
                                    500원을 초과해 베팅할 수 있습니다.
                                </span>
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

            {/* 베팅 — CommandModal pinned to bet (amount sub-form). bettingId + the radio-selected
                nationId are page-fixed args; legacy requires a bet > 500원 (amountMin 501). */}
            {betTarget && generalId != null && (
                <CommandModal
                    onClose={() => setBetTarget(null)}
                    onToast={(msg) => showToast(msg)}
                    generalId={generalId}
                    nationId={nationId}
                    pinnedCommand="bet"
                    pinnedLabel="베팅"
                    pinnedArgType="amount"
                    amountMin={501}
                    extraArgs={{ bettingId: betTarget.bettingId, nationId: selectedNation[betTarget.bettingId] }}
                    onReserved={() => { refresh(); fetchData(); }}
                />
            )}
        </Shell>
    );
}
