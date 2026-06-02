'use client';

import { useEffect, useState } from 'react';
import Shell from '../../../components/Shell';
import GameCard from '../../../components/GameCard';
import StatusBadge from '../../../components/StatusBadge';
import { api } from '../../../lib/api';
import { formatNumber } from '../../../lib/format';
import type { City } from '../../../types/game';

export default function CityPage() {
    const [city, setCity] = useState<City | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState('');

    const fetchData = async () => {
        setLoading(true);
        setError('');
        try {
            const res = await api.city<City>(0);
            setCity(res);
        } catch {
            setError('도시 정보를 불러올 수 없습니다.');
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
                    <h1>현재 도시</h1>
                    <p className="text-muted">로딩 중...</p>
                </div>
            </Shell>
        );
    }

    if (error) {
        return (
            <Shell>
                <div className="page-content">
                    <h1>현재 도시</h1>
                    <div className="error-state">
                        <p>{error}</p>
                        <button onClick={fetchData}>다시 시도</button>
                    </div>
                </div>
            </Shell>
        );
    }

    if (!city) {
        return (
            <Shell>
                <div className="page-content">
                    <h1>현재 도시</h1>
                    <p className="text-muted">도시 정보가 없습니다.</p>
                </div>
            </Shell>
        );
    }

    return (
        <Shell>
            <div className="page-content">
                <h1>현재 도시</h1>

                <GameCard className="city-detail">
                    <div className="card-header">
                        <h2>{city.name}</h2>
                        <StatusBadge variant="muted">Lv.{city.level}</StatusBadge>
                    </div>
                    <div className="stat-grid">
                        <div className="stat-item">
                            <span className="stat-label">인구</span>
                            <span className="stat-value">{formatNumber(city.pop)}</span>
                        </div>
                        <div className="stat-item">
                            <span className="stat-label">농업</span>
                            <span className="stat-value">{city.agri}</span>
                        </div>
                        <div className="stat-item">
                            <span className="stat-label">상업</span>
                            <span className="stat-value">{city.comm}</span>
                        </div>
                        <div className="stat-item">
                            <span className="stat-label">치안</span>
                            <span className="stat-value">{city.secu}</span>
                        </div>
                        <div className="stat-item">
                            <span className="stat-label">수비</span>
                            <span className="stat-value">{city.def}</span>
                        </div>
                        <div className="stat-item">
                            <span className="stat-label">성벽</span>
                            <span className="stat-value">{city.wall}</span>
                        </div>
                        <div className="stat-item">
                            <span className="stat-label">무역</span>
                            <span className="stat-value">{city.trade}</span>
                        </div>
                    </div>
                </GameCard>
            </div>
        </Shell>
    );
}
