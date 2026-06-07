'use client';

import { Suspense, useEffect, useState } from 'react';
import { useSearchParams } from 'next/navigation';
import Shell from '../../../components/Shell';
import GameCard from '../../../components/GameCard';
import StatusBadge from '../../../components/StatusBadge';
import { api } from '../../../lib/api';
import { formatNumber } from '../../../lib/format';
import type { City } from '../../../types/game';

// 도시 상세 본문 — 쿼리 ?id=<도시번호>로 특정 도시를 조회한다(MapViewer에서 도시 클릭 시 진입).
// id가 없거나 0이면 현재 장수 소재 도시(서버가 0을 현재 도시로 해석).
function CityDetail() {
    const searchParams = useSearchParams();
    const idParam = searchParams.get('id');
    const cityId = idParam != null && idParam !== '' ? Number(idParam) : 0;

    const [city, setCity] = useState<City | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState('');

    const fetchData = async () => {
        setLoading(true);
        setError('');
        try {
            const res = await api.city<City>(Number.isFinite(cityId) ? cityId : 0);
            setCity(res);
        } catch {
            setError('도시 정보를 불러올 수 없습니다.');
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        fetchData();
        // 쿼리 id가 바뀌면 다른 도시를 다시 조회.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [cityId]);

    if (loading) {
        return (
            <div className="page-content">
                <h1>도시 정보</h1>
                <p className="text-muted">로딩 중...</p>
            </div>
        );
    }

    if (error) {
        return (
            <div className="page-content">
                <h1>도시 정보</h1>
                <div className="error-state">
                    <p>{error}</p>
                    <button onClick={fetchData}>다시 시도</button>
                </div>
            </div>
        );
    }

    if (!city) {
        return (
            <div className="page-content">
                <h1>도시 정보</h1>
                <p className="text-muted">도시 정보가 없습니다.</p>
            </div>
        );
    }

    return (
        <div className="page-content">
            <h1>도시 정보</h1>

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
    );
}

export default function CityPage() {
    // useSearchParams는 Suspense 경계가 필요(app router CSR bailout).
    return (
        <Shell>
            <Suspense fallback={<div className="page-content"><h1>도시 정보</h1><p className="text-muted">로딩 중...</p></div>}>
                <CityDetail />
            </Suspense>
        </Shell>
    );
}
