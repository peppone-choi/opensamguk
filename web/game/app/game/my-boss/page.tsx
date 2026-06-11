'use client';

import { useEffect, useState } from 'react';
import Shell from '../../../components/Shell';
import GameCard from '../../../components/GameCard';
import StatusBadge from '../../../components/StatusBadge';
import { api } from '../../../lib/api';
import type { MyBossResponse } from '../../../lib/types';

export default function MyBossPage() {
    const [boss, setBoss] = useState<MyBossResponse | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState('');

    const fetchData = async () => {
        setLoading(true);
        setError('');
        try {
            const res = await api.myBoss<MyBossResponse>();
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

    if (boss == null || boss.nationId === 0 || !boss.hasBoss) {
        return (
            <Shell>
                <div className="page-content">
                    <h1>내 상관</h1>
                    <p className="text-muted">재야입니다.</p>
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
                        <h2>{boss.bossName}</h2>
                        <StatusBadge variant="gold">{boss.bossOfficerLevel}급</StatusBadge>
                    </div>
                </GameCard>
            </div>
        </Shell>
    );
}
