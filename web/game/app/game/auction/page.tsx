'use client';

import { useEffect, useState, useCallback } from 'react';
import Shell from '../../../components/Shell';
import GameCard from '../../../components/GameCard';
import StatusBadge from '../../../components/StatusBadge';
import { api } from '../../../lib/api';
import { formatRemaining } from '../../../lib/format';
import type { AuctionItem } from '../../../types/game';

const TYPE_LABEL: Record<string, string> = {
    buyRice: '쌀 구매',
    sellRice: '쌀 판매',
    uniqueItem: '유니크 아이템',
};

const RES_LABEL: Record<string, string> = {
    gold: '금',
    rice: '쌀',
    inheritPoint: '유산 포인트',
};

export default function AuctionPage() {
    const [auctions, setAuctions] = useState<AuctionItem[]>([]);
    const [generalId, setGeneralId] = useState<number>(1);
    const [bidAmount, setBidAmount] = useState<Record<number, string>>({});
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string>('');
    const [toast, setToast] = useState<string>('');
    const [now, setNow] = useState<number>(Date.now());

    const fetchAuctions = useCallback(async () => {
        setLoading(true);
        try {
            const data = await api.auctions<AuctionItem[]>();
            setAuctions(data);
            setError('');
        } catch {
            setError('경매 목록을 불러올 수 없습니다.');
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => {
        fetchAuctions();
    }, [fetchAuctions]);

    useEffect(() => {
        const es = new EventSource(`${process.env.NEXT_PUBLIC_GAME_API_URL ?? 'http://localhost:8081'}/realtime/events`);
        es.addEventListener('realtime', () => fetchAuctions());
        es.onerror = () => es.close();
        return () => es.close();
    }, [fetchAuctions]);

    useEffect(() => {
        const timer = setInterval(() => setNow(Date.now()), 1000);
        return () => clearInterval(timer);
    }, []);

    async function placeBid(auctionId: number) {
        const amount = Number(bidAmount[auctionId]);
        if (!amount || amount <= 0) {
            setToast('입찰가를 입력하세요.');
            setTimeout(() => setToast(''), 3000);
            return;
        }
        try {
            const data = await api.command<{ status: string; reason?: string }>('auction_bid', { auctionId, amount });
            setToast(data.status === 'AVAILABLE' ? '입찰이 접수되었습니다.' : (data.reason ?? '입찰할 수 없습니다.'));
        } catch {
            setToast('입찰 요청에 실패했습니다.');
        }
        setTimeout(() => setToast(''), 3000);
        fetchAuctions();
    }

    const activeAuctions = auctions.filter(a => !a.finished);
    const finishedAuctions = auctions.filter(a => a.finished);

    return (
        <Shell>
            <h1 style={{ fontSize: 'var(--text-2xl)', fontWeight: 700, marginBottom: 'var(--space-md)' }}>경매장</h1>

            <div className="control-bar" style={{ display: 'flex', gap: 'var(--space-md)', marginBottom: 'var(--space-md)', flexWrap: 'wrap', alignItems: 'center' }}>
                <label style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-sm)' }}>
                    <span style={{ fontSize: 'var(--text-sm)', color: 'var(--text-secondary)' }}>장수 ID</span>
                    <input
                        type="number"
                        style={{ width: '5rem' }}
                        value={generalId}
                        onChange={e => setGeneralId(Number(e.target.value))}
                    />
                </label>
                <button onClick={fetchAuctions}>새로고침</button>
            </div>

            {loading && <p style={{ color: 'var(--text-muted)' }}>로딩 중...</p>}
            {error && <p style={{ color: 'var(--crimson)' }}>{error}</p>}

            {toast && (
                <div className="toast" style={{ position: 'fixed', top: 'var(--space-md)', right: 'var(--space-md)', zIndex: 200 }}>
                    {toast}
                </div>
            )}

            <h2 style={{ fontSize: 'var(--text-lg)', fontWeight: 600, marginBottom: 'var(--space-sm)', color: 'var(--text-primary)' }}>진행 중인 경매</h2>
            {activeAuctions.length === 0 && !loading && (
                <p style={{ color: 'var(--text-muted)', marginBottom: 'var(--space-xl)' }}>진행 중인 경매가 없습니다.</p>
            )}
            <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-sm)', marginBottom: 'var(--space-xl)' }}>
                {activeAuctions.map(a => (
                    <GameCard key={a.id}>
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 'var(--space-sm)' }}>
                            <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-sm)' }}>
                                <StatusBadge variant="gold">{TYPE_LABEL[a.type] ?? a.type}</StatusBadge>
                                <span style={{ fontWeight: 500 }}>{a.title}</span>
                            </div>
                            <span style={{ fontSize: 'var(--text-xs)', color: 'var(--text-muted)' }}>등록자: {a.hostName}</span>
                        </div>
                        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(140px, 1fr))', gap: 'var(--space-sm)', fontSize: 'var(--text-sm)', marginBottom: 'var(--space-sm)' }}>
                            <div><span style={{ color: 'var(--text-muted)' }}>수량:</span> <strong>{a.amount.toLocaleString()}</strong></div>
                            <div><span style={{ color: 'var(--text-muted)' }}>결제:</span> <strong>{RES_LABEL[a.reqResource] ?? a.reqResource}</strong></div>
                            <div><span style={{ color: 'var(--text-muted)' }}>최고 입찰:</span> <strong>{a.highestBid?.toLocaleString() ?? '없음'}</strong></div>
                            <div><span style={{ color: 'var(--text-muted)' }}>남은 시간:</span> <strong style={{ color: 'var(--gold)' }}>{formatRemaining(a.closeDate, now)}</strong></div>
                        </div>
                        <div style={{ display: 'flex', gap: 'var(--space-sm)', alignItems: 'center' }}>
                            <input
                                type="number"
                                placeholder="입찰가"
                                style={{ width: '8rem' }}
                                value={bidAmount[a.id] ?? ''}
                                onChange={e => setBidAmount(prev => ({ ...prev, [a.id]: e.target.value }))}
                            />
                            <button onClick={() => placeBid(a.id)}>입찰</button>
                        </div>
                    </GameCard>
                ))}
            </div>

            {finishedAuctions.length > 0 && (
                <>
                    <h2 style={{ fontSize: 'var(--text-lg)', fontWeight: 600, marginBottom: 'var(--space-sm)', color: 'var(--text-secondary)' }}>종료된 경매</h2>
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-sm)', opacity: 0.6 }}>
                        {finishedAuctions.map(a => (
                            <GameCard key={a.id}>
                                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                                    <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-sm)' }}>
                                        <StatusBadge variant="muted">{TYPE_LABEL[a.type] ?? a.type}</StatusBadge>
                                        <span style={{ fontWeight: 500 }}>{a.title}</span>
                                    </div>
                                    <StatusBadge variant="muted">종료</StatusBadge>
                                </div>
                                <div style={{ fontSize: 'var(--text-sm)', color: 'var(--text-muted)', marginTop: 'var(--space-xs)' }}>
                                    최고 입찰: {a.highestBid?.toLocaleString() ?? '없음'}
                                    {a.highestBidder && ` (${a.highestBidder})`}
                                </div>
                            </GameCard>
                        ))}
                    </div>
                </>
            )}
        </Shell>
    );
}
