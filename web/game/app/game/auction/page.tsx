'use client';

import { useEffect, useState, useCallback } from 'react';
import Shell from '../../../components/Shell';
import GameCard from '../../../components/GameCard';
import StatusBadge from '../../../components/StatusBadge';
import CommandModal from '../../../components/CommandModal';
import { api } from '../../../lib/api';
import { formatRemaining } from '../../../lib/format';
import { useFrontInfo } from '../../../hooks/useFrontInfo';
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
    const { frontInfo, refresh } = useFrontInfo();
    const generalId = frontInfo?.general.generalId ?? null;
    const nationId = frontInfo?.general.nationId;
    const [auctions, setAuctions] = useState<AuctionItem[]>([]);
    // The auction id whose 입찰 modal is open (null = closed). amount sub-form lives in CommandModal.
    const [bidAuction, setBidAuction] = useState<AuctionItem | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string>('');
    const [toast, setToast] = useState<string>('');
    const [now, setNow] = useState<number>(Date.now());

    function showToast(msg: string) {
        setToast(msg);
        setTimeout(() => setToast(''), 3000);
    }

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
        const es = new EventSource('/api/game/sse/turn');
        es.addEventListener('turnCompleted', () => fetchAuctions());
        es.onerror = () => es.close();
        return () => es.close();
    }, [fetchAuctions]);

    useEffect(() => {
        const timer = setInterval(() => setNow(Date.now()), 1000);
        return () => clearInterval(timer);
    }, []);

    const activeAuctions = auctions.filter(a => !a.finished);
    const finishedAuctions = auctions.filter(a => a.finished);

    return (
        <Shell>
            <h1 style={{ fontSize: 'var(--text-2xl)', fontWeight: 700, marginBottom: 'var(--space-md)' }}>경매장</h1>

            <div className="control-bar" style={{ display: 'flex', gap: 'var(--space-md)', marginBottom: 'var(--space-md)', flexWrap: 'wrap', alignItems: 'center' }}>
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
                            <button
                                onClick={() => {
                                    if (generalId == null) {
                                        showToast('장수가 없어 입찰할 수 없습니다.');
                                        return;
                                    }
                                    setBidAuction(a);
                                }}
                            >
                                입찰
                            </button>
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

            {/* 입찰 — CommandModal pinned to auction_bid (amount sub-form). isUnique distinguishes a
                유니크 아이템 auction from a 금/쌀(자원) auction; auctionId is the page-fixed arg. */}
            {bidAuction && generalId != null && (
                <CommandModal
                    onClose={() => setBidAuction(null)}
                    onToast={(msg) => showToast(msg)}
                    generalId={generalId}
                    nationId={nationId}
                    pinnedCommand="auction_bid"
                    pinnedLabel={`입찰 (${TYPE_LABEL[bidAuction.type] ?? bidAuction.type})`}
                    pinnedArgType="amount"
                    amountMin={1}
                    extraArgs={{ auctionId: bidAuction.id, isUnique: bidAuction.type === 'uniqueItem' }}
                    onReserved={() => { refresh(); fetchAuctions(); }}
                />
            )}
        </Shell>
    );
}
