'use client';

import { useEffect, useState, useCallback } from 'react';
import Shell from '../../../components/Shell';
import GameCard from '../../../components/GameCard';
import StatusBadge from '../../../components/StatusBadge';
import { api } from '../../../lib/api';

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
            const [nData, cData, gData] = await Promise.all([
                api.rankings.kingdoms<Nation[]>().then(ns => ns.find(n => n.id === nationId)),
                api.myCities<City[]>(),
                api.myGenerals<{ id: number; meta?: { inheritBuff?: Record<string, number> } }[]>().then(gs => gs.find(g => g.id === generalId)),
            ]);
            if (nData) setNation(nData);
            if (cData) setCities(cData);
            if (gData) {
                setMyBuffs(gData.meta?.inheritBuff ?? {});
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
        const base = process.env.NEXT_PUBLIC_GAME_API_URL ?? 'http://localhost:8081';
        const es = new EventSource(`${base}/realtime/events`);
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
        try {
            const data = await api.command<{ status: string; reason?: string }>('BuyHiddenBuff', { buffKey, level, prevLevel });
            setToast(data.status === 'AVAILABLE' ? '구매가 접수되었습니다.' : (data.reason ?? '구매할 수 없습니다.'));
        } catch {
            setToast('구매 요청에 실패했습니다.');
        }
        setTimeout(() => setToast(''), 3000);
        fetchData();
    }

    async function buyRandomUnique() {
        try {
            const data = await api.command<{ status: string; reason?: string }>('BuyRandomUnique', {});
            setToast(data.status === 'AVAILABLE' ? '구매가 접수되었습니다.' : (data.reason ?? '구매할 수 없습니다.'));
        } catch {
            setToast('구매 요청에 실패했습니다.');
        }
        setTimeout(() => setToast(''), 3000);
        fetchData();
    }

    const nationCities = cities.filter(c => c.nationId === nationId);

    return (
        <Shell>
            <h1 style={{ fontSize: 'var(--text-2xl)', fontWeight: 700, marginBottom: 'var(--space-md)' }}>국가 정보</h1>

            <div style={{ display: 'flex', gap: 'var(--space-md)', marginBottom: 'var(--space-md)', flexWrap: 'wrap', alignItems: 'center' }}>
                <label style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-sm)' }}>
                    <span style={{ fontSize: 'var(--text-sm)', color: 'var(--text-secondary)' }}>국가 ID</span>
                    <input type="number" style={{ width: '5rem' }} value={nationId} onChange={e => setNationId(Number(e.target.value))} />
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

            {nation && (
                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))', gap: 'var(--space-md)', marginBottom: 'var(--space-md)' }}>
                    <GameCard>
                        <h2 style={{ fontSize: 'var(--text-lg)', fontWeight: 600, marginBottom: 'var(--space-sm)', color: nation.color }}>
                            {nation.name}
                        </h2>
                        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 'var(--space-xs) var(--space-sm)', fontSize: 'var(--text-sm)' }}>
                            <span style={{ color: 'var(--text-muted)' }}>레벨</span><strong>{nation.level}</strong>
                            <span style={{ color: 'var(--text-muted)' }}>국력</span><strong>{nation.power.toLocaleString()}</strong>
                            <span style={{ color: 'var(--text-muted)' }}>금</span><strong>{nation.gold.toLocaleString()}</strong>
                            <span style={{ color: 'var(--text-muted)' }}>쌀</span><strong>{nation.rice.toLocaleString()}</strong>
                            <span style={{ color: 'var(--text-muted)' }}>기술</span><strong>{nation.tech.toFixed(2)}</strong>
                            <span style={{ color: 'var(--text-muted)' }}>장수 수</span><strong>{nation.gennum}</strong>
                            <span style={{ color: 'var(--text-muted)' }}>도시 수</span><strong>{nationCities.length}</strong>
                        </div>
                    </GameCard>

                    <GameCard>
                        <h2 style={{ fontSize: 'var(--text-lg)', fontWeight: 600, marginBottom: 'var(--space-sm)' }}>도시 목록</h2>
                        {nationCities.length === 0 ? (
                            <p style={{ color: 'var(--text-muted)', fontSize: 'var(--text-sm)' }}>도시가 없습니다.</p>
                        ) : (
                            <div style={{ maxHeight: '12rem', overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: 'var(--space-xs)' }}>
                                {nationCities.map(c => (
                                    <div key={c.id} style={{ display: 'flex', justifyContent: 'space-between', fontSize: 'var(--text-sm)', padding: 'var(--space-xs) var(--space-sm)', background: 'var(--bg-hover)', borderRadius: 'var(--radius-sm)' }}>
                                        <span>{c.name}</span>
                                        <span style={{ color: 'var(--text-muted)' }}>인구 {c.pop.toLocaleString()} · 무역 {c.trade}</span>
                                    </div>
                                ))}
                            </div>
                        )}
                    </GameCard>
                </div>
            )}

            <GameCard className="mb-md">
                <h2 style={{ fontSize: 'var(--text-lg)', fontWeight: 600, marginBottom: 'var(--space-sm)' }}>유산 버프 구매</h2>
                <p style={{ fontSize: 'var(--text-sm)', color: 'var(--text-muted)', marginBottom: 'var(--space-md)' }}>
                    각 버프는 레벨 1~5까지 구매 가능합니다. 비용은 누적 차액입니다.
                </p>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-sm)' }}>
                    {INHERIT_BUFFS.map(buff => {
                        const currentLevel = myBuffs[buff.key] ?? 0;
                        return (
                            <div key={buff.key} style={{ border: '1px solid var(--border-subtle)', borderRadius: 'var(--radius-sm)', padding: 'var(--space-sm)' }}>
                                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 'var(--space-xs)' }}>
                                    <div>
                                        <strong>{buff.label}</strong>
                                        <span style={{ fontSize: 'var(--text-xs)', color: 'var(--text-muted)', marginLeft: 'var(--space-sm)' }}>{buff.desc}</span>
                                    </div>
                                    <span style={{ fontSize: 'var(--text-sm)' }}>
                                        현재 레벨: <strong style={{ color: 'var(--gold)' }}>{currentLevel}</strong>/5
                                    </span>
                                </div>
                                <div style={{ display: 'flex', gap: 'var(--space-xs)', flexWrap: 'wrap' }}>
                                    {[1, 2, 3, 4, 5].map(lvl => {
                                        const cost = INHERIT_COSTS[lvl] - INHERIT_COSTS[currentLevel];
                                        const disabled = currentLevel >= lvl;
                                        return (
                                            <button
                                                key={lvl}
                                                onClick={() => buyBuff(buff.key, lvl)}
                                                disabled={disabled}
                                                style={{
                                                    fontSize: 'var(--text-xs)',
                                                    padding: 'var(--space-xs) var(--space-sm)',
                                                    border: `1px solid ${disabled ? 'var(--border-subtle)' : 'var(--gold-dim)'}`,
                                                    background: disabled ? 'var(--bg-hover)' : 'rgba(201,162,39,0.1)',
                                                    color: disabled ? 'var(--text-muted)' : 'var(--gold)',
                                                }}
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
            </GameCard>

            <GameCard>
                <h2 style={{ fontSize: 'var(--text-lg)', fontWeight: 600, marginBottom: 'var(--space-sm)' }}>기타 유산 구매</h2>
                <div style={{ display: 'flex', gap: 'var(--space-sm)' }}>
                    <button onClick={buyRandomUnique}>랜덤 유니크 아이템 구매</button>
                </div>
            </GameCard>
        </Shell>
    );
}
