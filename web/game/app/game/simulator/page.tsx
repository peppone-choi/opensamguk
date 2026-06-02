'use client';

import { useState, useCallback } from 'react';
import Shell from '../../../components/Shell';
import GameCard from '../../../components/GameCard';
import StatusBadge from '../../../components/StatusBadge';
import { api } from '../../../lib/api';

interface GeneralOption {
    id: number;
    name: string;
    leadership: number;
    strength: number;
    intel: number;
    crew: number;
    train: number;
    atmos: number;
}

interface SimResult {
    attackerWon: boolean;
    attackerDamage: number;
    defenderDamage: number;
    attackerCrewLeft: number;
    defenderCrewLeft: number;
    log: string[];
}

export default function SimulatorPage() {
    const [generals, setGenerals] = useState<GeneralOption[]>([]);
    const [attackerId, setAttackerId] = useState<number | ''>('');
    const [defenderId, setDefenderId] = useState<number | ''>('');
    const [result, setResult] = useState<SimResult | null>(null);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string>('');
    const [toast, setToast] = useState<string>('');

    const fetchGenerals = useCallback(async () => {
        try {
            const data = await api.generals<GeneralOption[]>();
            setGenerals(data);
        } catch {
            setError('장수 목록을 불러올 수 없습니다.');
        }
    }, []);

    async function simulate() {
        if (!attackerId || !defenderId) {
            setToast('공격자와 방어자를 모두 선택하세요.');
            setTimeout(() => setToast(''), 3000);
            return;
        }
        if (attackerId === defenderId) {
            setToast('같은 장수를 선택할 수 없습니다.');
            setTimeout(() => setToast(''), 3000);
            return;
        }
        setLoading(true);
        setError('');
        try {
            const data = await api.simulateBattle<SimResult>({
                attackerGeneralId: attackerId,
                defenderGeneralId: defenderId,
            });
            setResult(data);
        } catch {
            setError('시뮬레이션에 실패했습니다.');
        } finally {
            setLoading(false);
        }
    }

    const attacker = generals.find(g => g.id === Number(attackerId));
    const defender = generals.find(g => g.id === Number(defenderId));

    return (
        <Shell>
            <h1 style={{ fontSize: 'var(--text-2xl)', fontWeight: 700, marginBottom: 'var(--space-md)' }}>
                전투 시뮬레이터
            </h1>

            <div style={{ display: 'flex', gap: 'var(--space-md)', marginBottom: 'var(--space-md)', flexWrap: 'wrap', alignItems: 'center' }}>
                <button onClick={fetchGenerals}>장수 목록 불러오기</button>
            </div>

            {error && <p style={{ color: 'var(--crimson)' }}>{error}</p>}

            {toast && (
                <div className="toast" style={{ position: 'fixed', top: 'var(--space-md)', right: 'var(--space-md)', zIndex: 200 }}>
                    {toast}
                </div>
            )}

            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))', gap: 'var(--space-md)', marginBottom: 'var(--space-md)' }}>
                {/* Attacker selector */}
                <GameCard>
                    <h2 style={{ fontSize: 'var(--text-lg)', fontWeight: 600, marginBottom: 'var(--space-sm)', color: 'var(--crimson)' }}>
                        공격자
                    </h2>
                    <label style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-xs)', fontSize: 'var(--text-sm)', color: 'var(--text-secondary)', marginBottom: 'var(--space-sm)' }}>
                        장수 선택
                        <select
                            value={attackerId}
                            onChange={e => setAttackerId(e.target.value ? Number(e.target.value) : '')}
                        >
                            <option value="">선택...</option>
                            {generals.map(g => (
                                <option key={g.id} value={g.id}>{g.name} (무{g.strength} 지{g.intel})</option>
                            ))}
                        </select>
                    </label>
                    {attacker && (
                        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 'var(--space-xs)', fontSize: 'var(--text-sm)' }}>
                            <span style={{ color: 'var(--text-muted)' }}>통솔</span><strong>{attacker.leadership}</strong>
                            <span style={{ color: 'var(--text-muted)' }}>묠력</span><strong>{attacker.strength}</strong>
                            <span style={{ color: 'var(--text-muted)' }}>지력</span><strong>{attacker.intel}</strong>
                            <span style={{ color: 'var(--text-muted)' }}>병력</span><strong>{attacker.crew.toLocaleString()}</strong>
                            <span style={{ color: 'var(--text-muted)' }}>훈련</span><strong>{attacker.train}</strong>
                            <span style={{ color: 'var(--text-muted)' }}>사기</span><strong>{attacker.atmos}</strong>
                        </div>
                    )}
                </GameCard>

                {/* Defender selector */}
                <GameCard>
                    <h2 style={{ fontSize: 'var(--text-lg)', fontWeight: 600, marginBottom: 'var(--space-sm)', color: 'var(--jade)' }}>
                        방어자
                    </h2>
                    <label style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-xs)', fontSize: 'var(--text-sm)', color: 'var(--text-secondary)', marginBottom: 'var(--space-sm)' }}>
                        장수 선택
                        <select
                            value={defenderId}
                            onChange={e => setDefenderId(e.target.value ? Number(e.target.value) : '')}
                        >
                            <option value="">선택...</option>
                            {generals.map(g => (
                                <option key={g.id} value={g.id}>{g.name} (무{g.strength} 지{g.intel})</option>
                            ))}
                        </select>
                    </label>
                    {defender && (
                        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 'var(--space-xs)', fontSize: 'var(--text-sm)' }}>
                            <span style={{ color: 'var(--text-muted)' }}>통솔</span><strong>{defender.leadership}</strong>
                            <span style={{ color: 'var(--text-muted)' }}>묠력</span><strong>{defender.strength}</strong>
                            <span style={{ color: 'var(--text-muted)' }}>지력</span><strong>{defender.intel}</strong>
                            <span style={{ color: 'var(--text-muted)' }}>병력</span><strong>{defender.crew.toLocaleString()}</strong>
                            <span style={{ color: 'var(--text-muted)' }}>훈련</span><strong>{defender.train}</strong>
                            <span style={{ color: 'var(--text-muted)' }}>사기</span><strong>{defender.atmos}</strong>
                        </div>
                    )}
                </GameCard>
            </div>

            <div style={{ marginBottom: 'var(--space-md)' }}>
                <button
                    onClick={simulate}
                    disabled={loading || !attackerId || !defenderId}
                    style={{
                        background: 'var(--gold)',
                        color: 'var(--bg-base)',
                        fontWeight: 600,
                        padding: 'var(--space-sm) var(--space-lg)',
                    }}
                >
                    {loading ? '시뮬레이션 중...' : '전투 시뮬레이션'}
                </button>
            </div>

            {result && (
                <GameCard>
                    <h2 style={{ fontSize: 'var(--text-lg)', fontWeight: 600, marginBottom: 'var(--space-sm)' }}>
                        시뮬레이션 결과
                    </h2>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-sm)', marginBottom: 'var(--space-md)' }}>
                        <StatusBadge variant={result.attackerWon ? 'gold' : 'muted'}>
                            {result.attackerWon ? '공격자 승리' : '방어자 승리'}
                        </StatusBadge>
                    </div>
                    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: 'var(--space-md)', marginBottom: 'var(--space-md)', fontSize: 'var(--text-sm)' }}>
                        <div>
                            <h3 style={{ color: 'var(--crimson)', fontWeight: 600, marginBottom: 'var(--space-xs)' }}>공격자</h3>
                            <div>피해: <strong>{result.attackerDamage.toLocaleString()}</strong></div>
                            <div>잔여 병력: <strong>{result.attackerCrewLeft.toLocaleString()}</strong></div>
                        </div>
                        <div>
                            <h3 style={{ color: 'var(--jade)', fontWeight: 600, marginBottom: 'var(--space-xs)' }}>방어자</h3>
                            <div>피해: <strong>{result.defenderDamage.toLocaleString()}</strong></div>
                            <div>잔여 병력: <strong>{result.defenderCrewLeft.toLocaleString()}</strong></div>
                        </div>
                    </div>
                    {result.log.length > 0 && (
                        <div style={{ border: '1px solid var(--border-subtle)', borderRadius: 'var(--radius-sm)', padding: 'var(--space-sm)', background: 'var(--bg-base)', maxHeight: '16rem', overflowY: 'auto' }}>
                            <h3 style={{ fontSize: 'var(--text-sm)', fontWeight: 600, marginBottom: 'var(--space-xs)', color: 'var(--text-secondary)' }}>전투 로그</h3>
                            <div style={{ display: 'flex', flexDirection: 'column', gap: 2, fontSize: 'var(--text-xs)', fontFamily: 'var(--font-mono)' }}>
                                {result.log.map((line, i) => (
                                    <div key={i} style={{ color: 'var(--text-secondary)' }}>{line}</div>
                                ))}
                            </div>
                        </div>
                    )}
                </GameCard>
            )}
        </Shell>
    );
}
