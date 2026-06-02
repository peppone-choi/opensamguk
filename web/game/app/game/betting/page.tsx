'use client';

import { useEffect, useState, useCallback } from 'react';

const API_BASE = process.env.NEXT_PUBLIC_GAME_API_URL ?? 'http://localhost:8081';

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

const TYPE_LABEL: Record<string, string> = {
    NATION_STRENGTH: '국가 강약',
    TOURNAMENT: '토너먼트',
    CUSTOM: '커스텀',
};

const STATUS_LABEL: Record<string, { label: string; color: string }> = {
    OPEN: { label: '진행 중', color: 'text-green-400' },
    CLOSED: { label: '마감', color: 'text-orange-400' },
    PAYOUT_DONE: { label: '보상 완료', color: 'text-gray-400' },
};

export default function BettingPage() {
    const [bettings, setBettings] = useState<BettingItem[]>([]);
    const [nations, setNations] = useState<{ id: number; name: string; color: string }[]>([]);
    const [generalId, setGeneralId] = useState<number>(1);
    const [betAmount, setBetAmount] = useState<Record<string, string>>({});
    const [selectedNation, setSelectedNation] = useState<Record<string, number>>({});
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string>('');
    const [toast, setToast] = useState<string>('');

    const fetchData = useCallback(async () => {
        setLoading(true);
        try {
            const [bRes, nRes] = await Promise.all([
                fetch(`${API_BASE}/api/bettings`),
                fetch(`${API_BASE}/api/nations`),
            ]);
            if (bRes.ok) setBettings(await bRes.json());
            if (nRes.ok) setNations(await nRes.json());
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
        const es = new EventSource(`${API_BASE}/realtime/events`);
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
        const res = await fetch(`${API_BASE}/api/command/bet?generalId=${generalId}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ bettingId, nationId, amount }),
        });
        const data = await res.json();
        setToast(data.status === 'AVAILABLE' ? '베팅이 접수되었습니다.' : (data.reason ?? '베팅할 수 없습니다.'));
        setTimeout(() => setToast(''), 3000);
        fetchData();
    }

    const openBettings = bettings.filter(b => b.status === 'OPEN');
    const closedBettings = bettings.filter(b => b.status !== 'OPEN');

    return (
        <main className="min-h-screen bg-gray-900 text-gray-100 p-4">
            <h1 className="text-2xl font-bold mb-4">베팅</h1>

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
                    onClick={fetchData}
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

            <h2 className="text-lg font-semibold mb-2 text-gray-200">진행 중인 베팅</h2>
            {openBettings.length === 0 && !loading && (
                <p className="text-gray-500 mb-6">진행 중인 베팅이 없습니다.</p>
            )}
            <div className="space-y-4 mb-8">
                {openBettings.map(b => {
                    const status = STATUS_LABEL[b.status] ?? { label: b.status, color: 'text-gray-400' };
                    return (
                        <div key={b.bettingId} className="border border-gray-600 rounded p-4 bg-gray-800">
                            <div className="flex justify-between items-start mb-3">
                                <div>
                                    <span className="text-xs bg-purple-700 text-white px-1.5 py-0.5 rounded mr-2">
                                        {TYPE_LABEL[b.type] ?? b.type}
                                    </span>
                                    <span className="font-medium">{b.bettingId}</span>
                                </div>
                                <span className={`text-sm font-medium ${status.color}`}>{status.label}</span>
                            </div>
                            <div className="text-sm text-gray-400 mb-3">
                                개시: {b.openYearMonth} · 총 풀: {b.totalPool.toLocaleString()} ·{' '}
                                {b.isExclusivePayout ? '독점 보상' : 'N등분'}
                            </div>

                            <div className="mb-3">
                                <h3 className="text-sm font-medium text-gray-300 mb-1">대상 국가 / 배당률</h3>
                                <div className="flex flex-wrap gap-2">
                                    {b.targetNations.map(n => (
                                        <label
                                            key={n.id}
                                            className={`flex items-center gap-1 px-2 py-1 rounded border cursor-pointer text-sm ${
                                                selectedNation[b.bettingId] === n.id
                                                    ? 'border-purple-500 bg-purple-900/30'
                                                    : 'border-gray-600 bg-gray-700'
                                            }`}
                                        >
                                            <input
                                                type="radio"
                                                name={`nation-${b.bettingId}`}
                                                className="sr-only"
                                                checked={selectedNation[b.bettingId] === n.id}
                                                onChange={() =>
                                                    setSelectedNation(prev => ({ ...prev, [b.bettingId]: n.id }))
                                                }
                                            />
                                            <span>{n.name}</span>
                                            <span className="text-yellow-400">x{n.odds.toFixed(2)}</span>
                                        </label>
                                    ))}
                                </div>
                            </div>

                            <div className="flex gap-2 items-center">
                                <input
                                    type="number"
                                    placeholder="베팅 금액"
                                    className="bg-gray-700 border border-gray-600 rounded px-2 py-1 text-sm w-32"
                                    value={betAmount[b.bettingId] ?? ''}
                                    onChange={e =>
                                        setBetAmount(prev => ({ ...prev, [b.bettingId]: e.target.value }))
                                    }
                                />
                                <button
                                    onClick={() => placeBet(b.bettingId)}
                                    className="bg-purple-600 hover:bg-purple-500 text-white text-sm px-3 py-1 rounded"
                                >
                                    베팅
                                </button>
                            </div>
                        </div>
                    );
                })}
            </div>

            {closedBettings.length > 0 && (
                <>
                    <h2 className="text-lg font-semibold mb-2 text-gray-400">종료된 베팅</h2>
                    <div className="space-y-2 opacity-60">
                        {closedBettings.map(b => {
                            const status = STATUS_LABEL[b.status] ?? { label: b.status, color: 'text-gray-400' };
                            return (
                                <div key={b.bettingId} className="border border-gray-700 rounded p-3 bg-gray-800/50">
                                    <div className="flex justify-between items-center">
                                        <div>
                                            <span className="text-xs bg-gray-600 text-white px-1.5 py-0.5 rounded mr-2">
                                                {TYPE_LABEL[b.type] ?? b.type}
                                            </span>
                                            <span className="font-medium">{b.bettingId}</span>
                                        </div>
                                        <span className={`text-sm ${status.color}`}>{status.label}</span>
                                    </div>
                                    <div className="text-sm text-gray-400 mt-1">
                                        총 풀: {b.totalPool.toLocaleString()} · {b.closeCondition}
                                    </div>
                                </div>
                            );
                        })}
                    </div>
                </>
            )}
        </main>
    );
}
