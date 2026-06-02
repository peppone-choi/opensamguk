'use client';

import { useEffect, useState } from 'react';
import Shell from '../../../components/Shell';
import GameTable from '../../../components/GameTable';
import StatusBadge from '../../../components/StatusBadge';
import { api } from '../../../lib/api';
import { formatNumber } from '../../../lib/format';
import type { City } from '../../../types/game';

export default function MyCitiesPage() {
    const [cities, setCities] = useState<City[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState('');

    const fetchData = async () => {
        setLoading(true);
        setError('');
        try {
            const res = await api.myCities<City[]>();
            setCities(res);
        } catch {
            setError('도시 목록을 불러올 수 없습니다.');
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
                    <h1>내 도시</h1>
                    <p className="text-muted">로딩 중...</p>
                </div>
            </Shell>
        );
    }

    if (error) {
        return (
            <Shell>
                <div className="page-content">
                    <h1>내 도시</h1>
                    <div className="error-state">
                        <p>{error}</p>
                        <button onClick={fetchData}>다시 시도</button>
                    </div>
                </div>
            </Shell>
        );
    }

    const headers = ['도시명', '레벨', '인구', '농업', '상업', '치안', '수비', '성벽', '무역'];
    const rows = cities.map(c => [
        c.name,
        <StatusBadge key={`lvl-${c.id}`} variant="muted">Lv.{c.level}</StatusBadge>,
        formatNumber(c.pop),
        c.agri,
        c.comm,
        c.secu,
        c.def,
        c.wall,
        c.trade,
    ]);

    return (
        <Shell>
            <div className="page-content">
                <h1>내 도시</h1>
                <p className="page-subtitle">총 {cities.length}개</p>
                {cities.length === 0 ? (
                    <p className="text-muted">소속 도시가 없습니다.</p>
                ) : (
                    <GameTable headers={headers} rows={rows} />
                )}
            </div>
        </Shell>
    );
}
