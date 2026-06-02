'use client';

import { useEffect, useState, useCallback } from 'react';

const API_BASE = process.env.NEXT_PUBLIC_GAME_API_URL ?? 'http://localhost:8081';

interface Nation {
    id: number;
    name: string;
    color: string;
    gold: number;
    rice: number;
    tech: number;
    level: number;
    typeCode: string;
    gennum: number;
    capset: number;
    power: number;
}

interface City {
    id: number;
    name: string;
    nationId: number;
    pop: number;
    trade: number;
}

const INHERIT_BUFFS = [
    { key: 'warAvoidRatio', label: '전투 회피율', desc: '자신의 전투 회피율 +1% per level' },
    { key: 'warCriticalRatio', label: '전투 필살율', desc: '자신의 전투 필살율 +1% per level' },
    { key: 'warMagicTrialProb', label: '계략 시도 확률', desc: '자신의 계략 시도 확률 +1% per level' },
    { key: 'success', label: '내정 성공 확률', desc: '내정 성공 확률 +1% per level' },
    { key: 'fail', label: '내정 실패 감소', desc: '내정 실패 확률 -1% per level' },
    { key: 'warAvoidRatioOppose', label: '상대 회피율 감소', desc: '상대 회피율 -1% per level' },
    { key: 'warCriticalRatioOppose', label: '상대 필살율 감소', desc: '상대 필살율 -1% per level' },
    { key: 'warMagicTrialProbOppose', label: '상대 계략 확률 감소', desc: '상대 계략 시도 확률 -1% per level' },
];

const INHERIT_COSTS = [0, 200, 600, 1200, 2000, 3000];

export default function NationPage() {
    const [nation, setNation] = useState<Nation | null>(null);
    const [cities, setCities] = useState<City[]>([]);
    const [myBuffs, setMyBuffs] = useState<Record<string, number>>({});
    const [nationId, setNationId] = useState<number>(1);
    const [generalId, setGeneralId] = useState<number>(1);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string>('');
    const [toast, setToast] = useState<string>('');

    const fetchData = useCallback(async () => {
        setLoading(true);
        try {
            const [nRes, cRes, gRes] = await Promise.all([
                fetch(`${API_BASE}/api/nations/${nationId}`),
                fetch(`${API_BASE}/api/cities?nationId=${nationId}`),
                fetch(`${API_BASE}/api/generals/${generalId}`),
            ]);
            if (nRes.ok) setNation(await nRes.json());
            if (cRes.ok) setCities(await cRes.json());
            if (gRes.ok) {
                const g = await gRes.json();
                const buffMap = (g.meta?.inheritBuff ?? {}) as Record<string, number>;
                setMyBuffs(buffMap);
            }
            setError('');
        } catch {
            setError('데이터를 불러올 수 없습니다.');
        } finally {
            setLoading(false);
        }
    }, [nationId, generalId]);

    useEffect(() => {
        fetchData();
    }, [fetchData]);

    useEffect(() => {
        const es = new EventSource(`${API_BASE}/realtime/events`);
        es.addEventListener('realtime', () => fetchData());
        es.onerror = () => es.close();
        return () => es.close();
    }, [fetchData]);

    async function buyBuff(buffKey: string, level: number) {
        const prevLevel = myBuffs[buffKey] ?? 0;
        if (prevLevel >= level) {
            setToast('이미 구입했거나 더 높은 등급을 보유 중입니다.');
            setTimeout(() => setToast(''), 3000);
            return;
        }
        const res = await fetch(`${API_BASE}/api/command/BuyHiddenBuff?generalId=${generalId}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ buffKey, level, prevLevel }),
        });
        const data = await res.json();
        setToast(data.status === 'AVAILABLE' ? '구매가 접수되었습니다.' : (data.reason ?? '구매할 수 없습니다.'));
        setTimeout(() => setToast(''), 3000);
        fetchData();
    }

    async function buyRandomUnique() {
        const res = await fetch(`${API_BASE}/api/command/BuyRandomUnique?generalId=${generalId}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: '{}',
        });
        const data = await res.json();
        setToast(data.status === 'AVAILABLE' ? '구매가 접수되었습니다.' : (data.reason ?? '구매할 수 없습니다.'));
        setTimeout(() => setToast(''), 3000);
        fetchData();
    }

    const nationCities = cities.filter(c => c.nationId === nationId);

    return (
        <main className="min-h-screen bg-gray-900 text-gray-100 p-4">
            <h1 className="text-2xl font-bold mb-4">국가 정보</h1>

            <div className="flex gap-4 mb-4 flex-wrap items-center">
                <label className="flex items-center gap-2">
                    <span className="text-sm text-gray-400">국가 ID</span>
                    <input
                        type="number"
                        className="bg-gray-800 border border-gray-600 rounded px-2 py-1 text-sm w-20"
                        value={nationId}
                        onChange={e => setNationId(Number(e.target.value))}
                    />
                </label>
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

            {nation && (
                <div className="grid grid-cols-1 md:grid-cols-2 gap-4 mb-6">
                    <div className="border border-gray-600 rounded p-4 bg-gray-800">
                        <h2 className="text-lg font-semibold mb-3" style={{ color: nation.color }}>
                            {nation.name}
                        </h2>
                        <div className="grid grid-cols-2 gap-2 text-sm">
                            <div className="text-gray-400">레벨</div>
                            <div className="font-medium">{nation.level}</div>
                            <div className="text-gray-400">국력</div>
                            <div className="font-medium">{nation.power.toLocaleString()}</div>
                            <div className="text-gray-400">금</div>
                            <div className="font-medium">{nation.gold.toLocaleString()}</div>
                            <div className="text-gray-400">쌀</div>
                            <div className="font-medium">{nation.rice.toLocaleString()}</div>
                            <div className="text-gray-400">기술</div>
                            <div className="font-medium">{nation.tech.toFixed(2)}</div>
                            <div className="text-gray-400">장수 수</div>
                            <div className="font-medium">{nation.gennum}</div>
                            <div className="text-gray-400">도시 수</div>
                            <div className="font-medium">{nationCities.length}</div>
                        </div>
                    </div>

                    <div className="border border-gray-600 rounded p-4 bg-gray-800">
                        <h2 className="text-lg font-semibold mb-3 text-gray-200">도시 목록</h2>
                        {nationCities.length === 0 ? (
                            <p className="text-gray-500 text-sm">도시가 없습니다.</p>
                        ) : (
                            <div className="max-h-48 overflow-y-auto space-y-1">
                                {nationCities.map(c => (
                                    <div key={c.id} className="flex justify-between text-sm px-2 py-1 bg-gray-700/50 rounded">
                                        <span>{c.name}</span>
                                        <span className="text-gray-400">
                                            인구 {c.pop.toLocaleString()} · 무역 {c.trade}
                                        </span>
                                    </div>
                                ))}
                            </div>
                        )}
                    </div>
                </div>
            )}

            <div className="border border-gray-600 rounded p-4 bg-gray-800 mb-6">
                <h2 className="text-lg font-semibold mb-3 text-gray-200">유산 버프 구매</h2>
                <p className="text-sm text-gray-400 mb-4">
                    각 버프는 레벨 1~5까지 구매 가능합니다. 비용은 누적 차액입니다.
                </p>
                <div className="space-y-3">
                    {INHERIT_BUFFS.map(buff => {
                        const currentLevel = myBuffs[buff.key] ?? 0;
                        return (
                            <div key={buff.key} className="border border-gray-700 rounded p-3">
                                <div className="flex justify-between items-start mb-2">
                                    <div>
                                        <span className="font-medium">{buff.label}</span>
                                        <span className="text-xs text-gray-400 ml-2">{buff.desc}</span>
                                    </div>
                                    <span className="text-sm">
                                        현재 레벨:{' '}
                                        <span className="font-bold text-yellow-400">{currentLevel}</span>/5
                                    </span>
                                </div>
                                <div className="flex gap-1 flex-wrap">
                                    {[1, 2, 3, 4, 5].map(lvl => {
                                        const cost = INHERIT_COSTS[lvl] - INHERIT_COSTS[currentLevel];
                                        const disabled = currentLevel >= lvl;
                                        return (
                                            <button
                                                key={lvl}
                                                onClick={() => buyBuff(buff.key, lvl)}
                                                disabled={disabled}
                                                className={`text-xs px-2 py-1 rounded border ${
                                                    disabled
                                                        ? 'border-gray-600 bg-gray-700 text-gray-500 cursor-not-allowed'
                                                        : 'border-yellow-600 bg-yellow-900/20 text-yellow-300 hover:bg-yellow-900/40'
                                                }`}
                                            >
                                                L{lvl} ({cost.toLocaleString()}P)
                                            </button>
                                        );
                                    })}
                                </div>
                            </div>
                        );
                    })}
                </div>
            </div>

            <div className="border border-gray-600 rounded p-4 bg-gray-800">
                <h2 className="text-lg font-semibold mb-3 text-gray-200">기타 유산 구매</h2>
                <div className="flex gap-2">
                    <button
                        onClick={buyRandomUnique}
                        className="bg-purple-600 hover:bg-purple-500 text-white text-sm px-4 py-2 rounded"
                    >
                        랜덤 유니크 아이템 구매
                    </button>
                </div>
            </div>
        </main>
    );
}
