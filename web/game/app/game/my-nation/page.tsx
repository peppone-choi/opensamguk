'use client';

import { useEffect, useState } from 'react';
import Shell from '../../../components/Shell';
import GameCard from '../../../components/GameCard';
import GameTable from '../../../components/GameTable';
import StatusBadge from '../../../components/StatusBadge';
import { api } from '../../../lib/api';
import { formatNumber } from '../../../lib/format';
import type { General, City, Nation } from '../../../types/game';

interface MyNationData {
    nation: Nation;
    generals: General[];
    cities: City[];
}

export default function MyNationPage() {
    const [data, setData] = useState<MyNationData | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState('');

    const fetchData = async () => {
        setLoading(true);
        setError('');
        try {
            const res = await api.myNationDetail<MyNationData>();
            setData(res);
        } catch {
            setError('국가 정보를 불러올 수 없습니다.');
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
                    <h1>내 국가</h1>
                    <p className="text-muted">로딩 중...</p>
                </div>
            </Shell>
        );
    }

    if (error) {
        return (
            <Shell>
                <div className="page-content">
                    <h1>내 국가</h1>
                    <div className="error-state">
                        <p>{error}</p>
                        <button onClick={fetchData}>다시 시도</button>
                    </div>
                </div>
            </Shell>
        );
    }

    if (!data) return null;

    const { nation, generals, cities } = data;

    const genHeaders = ['이름', '계급', '통솔', '묠력', '지력', '병사'];
    const genRows = generals.map(g => [
        g.name,
        <StatusBadge key={`gl-${g.id}`} variant="gold">{g.officerLevel}급</StatusBadge>,
        g.leadership,
        g.strength,
        g.intel,
        formatNumber(g.crew),
    ]);

    const cityHeaders = ['도시명', '레벨', '인구', '농업', '상업', '수비'];
    const cityRows = cities.map(c => [
        c.name,
        <StatusBadge key={`cl-${c.id}`} variant="muted">Lv.{c.level}</StatusBadge>,
        formatNumber(c.pop),
        c.agri,
        c.comm,
        c.def,
    ]);

    return (
        <Shell>
            <div className="page-content">
                <h1>내 국가</h1>

                <GameCard className="nation-overview">
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
                        <div className="stat-item">
                            <span className="stat-label">국고</span>
                            <span className="stat-value">{formatNumber(nation.gold)}</span>
                        </div>
                        <div className="stat-item">
                            <span className="stat-label">국고미</span>
                            <span className="stat-value">{formatNumber(nation.rice)}</span>
                        </div>
                    </div>
                </GameCard>

                <h2>장수 ({generals.length}명)</h2>
                {generals.length === 0 ? (
                    <p className="text-muted">소속 장수가 없습니다.</p>
                ) : (
                    <GameTable headers={genHeaders} rows={genRows} />
                )}

                <h2>도시 ({cities.length}개)</h2>
                {cities.length === 0 ? (
                    <p className="text-muted">소속 도시가 없습니다.</p>
                ) : (
                    <GameTable headers={cityHeaders} rows={cityRows} />
                )}
            </div>
        </Shell>
    );
}
