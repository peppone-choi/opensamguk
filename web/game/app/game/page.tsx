'use client';

import { useEffect, useState } from 'react';
import Shell from '../../components/Shell';
import GameCard from '../../components/GameCard';
import StatusBadge from '../../components/StatusBadge';
import { api } from '../../lib/api';
import { formatNumber, formatTurn } from '../../lib/format';
import type { MyPageData } from '../../types/game';

export default function MyPage() {
    const [data, setData] = useState<MyPageData | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState('');

    const fetchData = async () => {
        setLoading(true);
        setError('');
        try {
            const res = await api.myPage<MyPageData>();
            setData(res);
        } catch {
            setError('내 정보를 불러올 수 없습니다.');
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
                    <h1>내 정보</h1>
                    <p className="text-muted">로딩 중...</p>
                </div>
            </Shell>
        );
    }

    if (error) {
        return (
            <Shell>
                <div className="page-content">
                    <h1>내 정보</h1>
                    <div className="error-state">
                        <p>{error}</p>
                        <button onClick={fetchData}>다시 시도</button>
                    </div>
                </div>
            </Shell>
        );
    }

    if (!data) return null;

    const { general, nation, city, turn, notifications } = data;

    return (
        <Shell>
            <div className="page-content">
                <h1>내 정보</h1>

                <div className="page-grid">
                    {/* General card */}
                    <GameCard className="general-card">
                        <div className="card-header">
                            <h2>{general.name}</h2>
                            <StatusBadge variant="gold">{general.officerLevel}급</StatusBadge>
                        </div>
                        <div className="stat-grid">
                            <div className="stat-item">
                                <span className="stat-label">통솔</span>
                                <span className="stat-value">{general.leadership}</span>
                            </div>
                            <div className="stat-item">
                                <span className="stat-label">묠력</span>
                                <span className="stat-value">{general.strength}</span>
                            </div>
                            <div className="stat-item">
                                <span className="stat-label">지력</span>
                                <span className="stat-value">{general.intel}</span>
                            </div>
                            <div className="stat-item">
                                <span className="stat-label">경험</span>
                                <span className="stat-value">{formatNumber(general.experience)}</span>
                            </div>
                            <div className="stat-item">
                                <span className="stat-label">충성</span>
                                <span className="stat-value">{general.devotion}</span>
                            </div>
                            <div className="stat-item">
                                <span className="stat-label">병사</span>
                                <span className="stat-value">{formatNumber(general.crew)}</span>
                            </div>
                        </div>
                        <div className="resource-bar">
                            <span>금: {formatNumber(general.gold)}</span>
                            <span>쌀: {formatNumber(general.rice)}</span>
                        </div>
                    </GameCard>

                    {/* Nation card */}
                    <GameCard className="nation-card">
                        <div className="card-header">
                            <h2>{nation.name}</h2>
                            <StatusBadge variant="jade">Lv.{nation.level}</StatusBadge>
                        </div>
                        <div className="stat-grid">
                            <div className="stat-item">
                                <span className="stat-label">장수</span>
                                <span className="stat-value">{nation.genNum}명</span>
                            </div>
                            <div className="stat-item">
                                <span className="stat-label">세력</span>
                                <span className="stat-value">{formatNumber(nation.power)}</span>
                            </div>
                            <div className="stat-item">
                                <span className="stat-label">인구</span>
                                <span className="stat-value">{formatNumber(nation.pop)}</span>
                            </div>
                        </div>
                        <div className="resource-bar">
                            <span>국고: {formatNumber(nation.gold)}</span>
                            <span>국고미: {formatNumber(nation.rice)}</span>
                        </div>
                    </GameCard>

                    {/* City card */}
                    <GameCard className="city-card">
                        <div className="card-header">
                            <h2>{city.name}</h2>
                            <StatusBadge variant="muted">Lv.{city.level}</StatusBadge>
                        </div>
                        <div className="stat-grid">
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
                        <div className="resource-bar">
                            <span>인구: {formatNumber(city.pop)}</span>
                        </div>
                    </GameCard>

                    {/* Turn state */}
                    <GameCard className="turn-card">
                        <div className="card-header">
                            <h2>턴 정보</h2>
                            <StatusBadge variant="gold">{formatTurn(turn.turn)}</StatusBadge>
                        </div>
                        <div className="turn-detail">
                            <p>턴: {turn.turn}</p>
                            <p>년도: {turn.year}년</p>
                            <p>월: {turn.month}월</p>
                        </div>
                    </GameCard>
                </div>

                {/* Notifications */}
                {notifications.length > 0 && (
                    <div className="notifications">
                        <h2>알림</h2>
                        {notifications.map((n, i) => (
                            <div key={i} className="notification-item">
                                <span className="notification-dot" />
                                {n}
                            </div>
                        ))}
                    </div>
                )}
            </div>
        </Shell>
    );
}
