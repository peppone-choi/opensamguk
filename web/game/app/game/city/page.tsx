'use client';

import { Suspense, useEffect, useState } from 'react';
import { useSearchParams } from 'next/navigation';
import Shell from '../../../components/Shell';
import GameCard from '../../../components/GameCard';
import StatusBadge from '../../../components/StatusBadge';
import Gauge from '../../../components/game/Gauge';
import { api } from '../../../lib/api';
import type { CityDetailResponse } from '../../../lib/types';

// 도시 상세 본문 — 쿼리 ?id=<도시번호>로 특정 도시를 조회한다(MapViewer에서 도시 클릭 시 진입).
// id가 없거나 0이면 현재 장수 소재 도시(서버가 0을 현재 도시로 해석).
//
// 첩보(fog) 패러티: 자기 국가 도시가 아니고(공백지 포함) 첩보·주둔도 없으면 서버가 내정/방어 수치를 null로
// 마스킹(visible=false)한다. 그 경우 게이지 대신 "첩보 없음" 안내를 렌더(수치 날조 없음).
function CityDetail() {
    const searchParams = useSearchParams();
    const idParam = searchParams.get('id');
    const cityId = idParam != null && idParam !== '' ? Number(idParam) : 0;

    const [city, setCity] = useState<CityDetailResponse | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState('');

    const fetchData = async () => {
        setLoading(true);
        setError('');
        try {
            const res = await api.city<CityDetailResponse>(Number.isFinite(cityId) ? cityId : 0);
            setCity(res);
        } catch {
            setError('도시 정보를 불러올 수 없습니다.');
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        fetchData();
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

    const neutral = city.nationId === 0;
    // 헤더 라벨 — 레거시 CityBasicCard.vue 【지역 | 등급】 도시명.
    const header = `【${city.regionName} | ${city.levelName}】 ${city.name}`;

    return (
        <div className="page-content">
            <h1>도시 정보</h1>

            <GameCard className="city-detail">
                <div className="card-header" style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-sm)', flexWrap: 'wrap' }}>
                    <h2 style={{ margin: 0 }}>{header}</h2>
                    <StatusBadge variant={neutral ? 'muted' : 'jade'}>{neutral ? '공 백 지' : '지배 도시'}</StatusBadge>
                    {city.supplyState === 0 && !neutral && <StatusBadge variant="crimson">보급 끊김</StatusBadge>}
                </div>

                {city.visible ? (
                    <div className="gauge-metrics">
                        <Gauge label="주민" now={city.population ?? 0} max={city.populationMax ?? 0} />
                        <Gauge label="민심" now={city.trust ?? 0} max={100} barOnly />
                        <Gauge label="농업" now={city.agriculture ?? 0} max={city.agricultureMax ?? 0} />
                        <Gauge label="상업" now={city.commerce ?? 0} max={city.commerceMax ?? 0} />
                        <Gauge label="치안" now={city.security ?? 0} max={city.securityMax ?? 0} />
                        <Gauge label="수비" now={city.defense ?? 0} max={city.defenseMax ?? 0} />
                        <Gauge label="성벽" now={city.wall ?? 0} max={city.wallMax ?? 0} />
                        {/* 시세(trade %) — 레거시 (trade-95)*10 클램프. trade==null(상인 없음)이면 텍스트만. */}
                        <div className="mcd-metric gauge-metric">
                            <div className="mcd-metric-head">시세</div>
                            <div className="mcd-metric-body">
                                {city.trade != null && (
                                    <div className="mcd-bar">
                                        <div className="mcd-bar-fill" style={{ width: `${Math.min(100, Math.max(0, (city.trade - 95) * 10))}%` }} />
                                    </div>
                                )}
                                <div className="mcd-metric-text">{city.trade != null ? `${city.trade}%` : '상인 없음'}</div>
                            </div>
                        </div>
                        <div className="mcd-metric gauge-metric">
                            <div className="mcd-metric-head">주둔 장수</div>
                            <div className="mcd-metric-body">
                                <div className="mcd-metric-text">{city.officers ?? 0}명</div>
                            </div>
                        </div>
                    </div>
                ) : (
                    <div className="mcd-empty" style={{ padding: 'var(--space-lg)', textAlign: 'center', color: 'var(--text-muted)' }}>
                        {neutral ? '공백지입니다.' : '다른 세력의 도시입니다.'} 첩보가 없어 내정 정보를 볼 수 없습니다.
                    </div>
                )}
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
