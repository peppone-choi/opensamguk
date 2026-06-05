'use client';

import { useEffect, useState } from 'react';
import Shell from '../../../components/Shell';
import GameCard from '../../../components/GameCard';
import StatusBadge from '../../../components/StatusBadge';
import { api } from '../../../lib/api';
import { formatNumber } from '../../../lib/format';
import type { General } from '../../../types/game';

export default function MyBossPage() {
    const [boss, setBoss] = useState<General | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState('');

    const fetchData = async () => {
        setLoading(true);
        setError('');
        try {
            const res = await api.myBoss<General>();
            setBoss(res);
        } catch {
            setError('상관 정보를 불러올 수 없습니다.');
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        fetchData();
    }, []);

    if (loading) {
        return (
            <Shell>
                <div className="page-content">
                    <h1>내 상관</h1>
                    <p className="text-muted">로딩 중...</p>
                </div>
            </Shell>
        );
    }

    if (error) {
        return (
            <Shell>
                <div className="page-content">
                    <h1>내 상관</h1>
                    <div className="error-state">
                        <p>{error}</p>
                        <button onClick={fetchData}>다시 시도</button>
                    </div>
                </div>
            </Shell>
        );
    }

    if (!boss) {
        return (
            <Shell>
                <div className="page-content">
                    <h1>내 상관</h1>
                    <p className="text-muted">상관 정보가 없습니다.</p>
                </div>
            </Shell>
        );
    }

    return (
        <Shell>
            <div className="page-content">
                <h1>내 상관</h1>
                <GameCard className="boss-card">
                    <div className="card-header">
                        <h2>{boss.name}</h2>
                        <StatusBadge variant="gold">{boss.officerLevel}급</StatusBadge>
                    </div>
                    <div className="stat-grid">
                        <div className="stat-item">
                            <span className="stat-label">통솔</span>
                            <span className="stat-value">{boss.leadership}</span>
                        </div>
                        <div className="stat-item">
                            <span className="stat-label">무력</span>
                            <span className="stat-value">{boss.strength}</span>
                        </div>
                        <div className="stat-item">
                            <span className="stat-label">지력</span>
                            <span className="stat-value">{boss.intel}</span>
                        </div>
                        <div className="stat-item">
                            <span className="stat-label">경험</span>
                            <span className="stat-value">{formatNumber(boss.experience)}</span>
                        </div>
                        <div className="stat-item">
                            <span className="stat-label">충성</span>
                            <span className="stat-value">{boss.devotion}</span>
                        </div>
                        <div className="stat-item">
                            <span className="stat-label">병사</span>
                            <span className="stat-value">{formatNumber(boss.crew)}</span>
                        </div>
                    </div>
                    <div className="resource-bar">
                        <span>금: {formatNumber(boss.gold)}</span>
                        <span>쌀: {formatNumber(boss.rice)}</span>
                    </div>
                </GameCard>
            </div>
        </Shell>
    );
}
