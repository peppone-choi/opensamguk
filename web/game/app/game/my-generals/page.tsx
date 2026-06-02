'use client';

import { useEffect, useState } from 'react';
import Shell from '../../../components/Shell';
import GameTable from '../../../components/GameTable';
import StatusBadge from '../../../components/StatusBadge';
import { api } from '../../../lib/api';
import { formatNumber } from '../../../lib/format';
import type { General } from '../../../types/game';

export default function MyGeneralsPage() {
    const [generals, setGenerals] = useState<General[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState('');

    const fetchData = async () => {
        setLoading(true);
        setError('');
        try {
            const res = await api.myGenerals<General[]>();
            setGenerals(res);
        } catch {
            setError('장수 목록을 불러올 수 없습니다.');
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
                    <h1>내 장수</h1>
                    <p className="text-muted">로딩 중...</p>
                </div>
            </Shell>
        );
    }

    if (error) {
        return (
            <Shell>
                <div className="page-content">
                    <h1>내 장수</h1>
                    <div className="error-state">
                        <p>{error}</p>
                        <button onClick={fetchData}>다시 시도</button>
                    </div>
                </div>
            </Shell>
        );
    }

    const headers = ['이름', '계급', '통솔', '묠력', '지력', '경험', '충성', '병사', '금', '쌀'];
    const rows = generals.map(g => [
        g.name,
        <StatusBadge key={`lvl-${g.id}`} variant="gold">{g.officerLevel}급</StatusBadge>,
        g.leadership,
        g.strength,
        g.intel,
        formatNumber(g.experience),
        g.devotion,
        formatNumber(g.crew),
        formatNumber(g.gold),
        formatNumber(g.rice),
    ]);

    return (
        <Shell>
            <div className="page-content">
                <h1>내 장수</h1>
                <p className="page-subtitle">총 {generals.length}명</p>
                {generals.length === 0 ? (
                    <p className="text-muted">소속 장수가 없습니다.</p>
                ) : (
                    <GameTable headers={headers} rows={rows} />
                )}
            </div>
        </Shell>
    );
}
