'use client';

import { useEffect, useState, useCallback } from 'react';

const API_BASE = process.env.NEXT_PUBLIC_GAME_API_URL ?? 'http://localhost:8081';

interface AuctionItem {
    id: number;
    type: string;
    title: string;
    hostName: string;
    amount: number;
    reqResource: string;
    closeDate: string;
    finished: boolean;
    highestBid?: number;
    highestBidder?: string;
}

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
            const res = await fetch(`${API_BASE}/api/auctions`);
            if (res.ok) {
                setAuctions(await res.json());
            } else {
                setError('경매 목록을 불러올 수 없습니다.');
            }
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
        const es = new EventSource(`${API_BASE}/realtime/events`);
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
        const res = await fetch(`${API_BASE}/api/command/auction_bid?generalId=${generalId}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ auctionId, amount }),
        });
        const data = await res.json();
        setToast(data.status === 'AVAILABLE' ? '입찰이 접수되었습니다.' : (data.reason ?? '입찰할 수 없습니다.'));
        setTimeout(() => setToast(''), 3000);
        fetchAuctions();
    }

    function formatRemaining(closeDate: string): string {
        const diff = new Date(closeDate).getTime() - now;
        if (diff <= 0) return '마감';
        const h = Math.floor(diff / 3600000);
        const m = Math.floor((diff % 3600000) / 60000);
        const s = Math.floor((diff % 60000) / 1000);
        if (h > 0) return `${h}시간 ${m}분`;
        return `${m}분 ${s}초`;
    }

    const activeAuctions = auctions.filter(a => !a.finished);
    const finishedAuctions = auctions.filter(a => a.finished);

    return (
        <main className="min-h-screen bg-gray-900 text-gray-100 p-4">
            <h1 className="text-2xl font-bold mb-4">경매장</h1>

            <div className="flex gap-4 mb-4 flex-wrap items-center">
                <label className="flex items-center gap-2">
                    <span className="text-sm text-gray-400">장수 ID</span>
                    <input
                        type="number"
                        className="bg-gray-800 border border-gray-600 rounded px-2 py-1 text-sm w-20"
                        value={generalId}
                        onChange={e => setGeneralId(Number(e.target.value))}
                    />
                </label>
                <button
                    onClick={fetchAuctions}
                    className="bg-blue-600 hover:bg-blue-500 text-white text-sm px-3 py-1 rounded"
                >
                    새로고침
                </button>
            </div>

            {loading && <p className="text-gray-400">로딩 중...</p>}
            {error && <p className="text-red-400">{error}</p>}

            {toast && (
                <div className="fixed top-4 right-4 bg-gray-800 border border-gray-600 text-white px-4 py-2 rounded shadow-lg z-50">
                    {toast}
                </div>
            )}

            <h2 className="text-lg font-semibold mb-2 text-gray-200">진행 중인 경매</h2>
            {activeAuctions.length === 0 && !loading && (
                <p className="text-gray-500 mb-6">진행 중인 경매가 없습니다.</p>
            )}
            <div className="space-y-3 mb-8">
                {activeAuctions.map(a => (
                    <div key={a.id} className="border border-gray-600 rounded p-3 bg-gray-800">
                        <div className="flex justify-between items-start mb-2">
                            <div>
                                <span className="text-xs bg-blue-700 text-white px-1.5 py-0.5 rounded mr-2">
                                    {TYPE_LABEL[a.type] ?? a.type}
                                </span>
                                <span className="font-medium">{a.title}</span>
                            </div>
                            <span className="text-xs text-gray-400">
                                등록자: {a.hostName}
                            </span>
                        </div>
                        <div className="grid grid-cols-2 md:grid-cols-4 gap-2 text-sm mb-3">
                            <div>
                                <span className="text-gray-500">수량:</span>{' '}
                                <span className="font-medium">{a.amount.toLocaleString()}</span>
                            </div>
                            <div>
                                <span className="text-gray-500">결제:</span>{' '}
                                <span className="font-medium">{RES_LABEL[a.reqResource] ?? a.reqResource}</span>
                            </div>
                            <div>
                                <span className="text-gray-500">최고 입찰:</span>{' '}
                                <span className="font-medium">{a.highestBid?.toLocaleString() ?? '없음'}</span>
                            </div>
                            <div>
                                <span className="text-gray-500">남은 시간:</span>{' '}
                                <span className="font-medium text-orange-400">{formatRemaining(a.closeDate)}</span>
                            </div>
                        </div>
                        <div className="flex gap-2 items-center">
                            <input
                                type="number"
                                placeholder="입찰가"
                                className="bg-gray-700 border border-gray-600 rounded px-2 py-1 text-sm w-32"
                                value={bidAmount[a.id] ?? ''}
                                onChange={e => setBidAmount(prev => ({ ...prev, [a.id]: e.target.value }))}
                            />
                            <button
                                onClick={() => placeBid(a.id)}
                                className="bg-green-600 hover:bg-green-500 text-white text-sm px-3 py-1 rounded"
                            >
                                입찰
                            </button>
                        </div>
                    </div>
                ))}
            </div>

            {finishedAuctions.length > 0 && (
                <>
                    <h2 className="text-lg font-semibold mb-2 text-gray-400">종료된 경매</h2>
                    <div className="space-y-2 opacity-60">
                        {finishedAuctions.map(a => (
                            <div key={a.id} className="border border-gray-700 rounded p-3 bg-gray-800/50">
                                <div className="flex justify-between items-center">
                                    <div>
                                        <span className="text-xs bg-gray-600 text-white px-1.5 py-0.5 rounded mr-2">
                                            {TYPE_LABEL[a.type] ?? a.type}
                                        </span>
                                        <span className="font-medium">{a.title}</span>
                                    </div>
                                    <span className="text-xs text-gray-500">종료</span>
                                </div>
                                <div className="text-sm text-gray-400 mt-1">
                                    최고 입찰: {a.highestBid?.toLocaleString() ?? '없음'}
                                    {a.highestBidder && ` (${a.highestBidder})`}
                                </div>
                            </div>
                        ))}
                    </div>
                </>
            )}
        </main>
    );
}
