'use client';

import { useEffect, useState } from 'react';
import Shell from '../../../components/Shell';
import GameTable from '../../../components/GameTable';
import StatusBadge from '../../../components/StatusBadge';
import { api } from '../../../lib/api';
import { formatNumber } from '../../../lib/format';
import type { General } from '../../../types/game';

export default function GeneralsPage() {
    const [allGenerals, setAllGenerals] = useState<General[]>([]);
    const [filtered, setFiltered] = useState<General[]>([]);
    const [search, setSearch] = useState('');
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState('');

    const fetchData = async () => {
        setLoading(true);
        setError('');
        try {
            const res = await api.generals<General[]>();
            setAllGenerals(res);
            setFiltered(res);
        } catch {
            setError('장수 목록을 불러올 수 없습니다.');
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        fetchData();
    }, []);

    useEffect(() => {
        const term = search.trim().toLowerCase();
        if (!term) {
            setFiltered(allGenerals);
            return;
        }
        setFiltered(allGenerals.filter(g =>
            g.name.toLowerCase().includes(term) ||
            g.leadership.toString().includes(term) ||
            g.strength.toString().includes(term) ||
            g.intel.toString().includes(term)
        ));
    }, [search, allGenerals]);

    if (loading) {
        return (
            <Shell>
                <div className="page-content">
                    <h1>전체 장수</h1>
                    <p className="text-muted">로딩 중...</p>
                </div>
            </Shell>
        );
    }

    if (error) {
        return (
            <Shell>
                <div className="page-content">
                    <h1>전체 장수</h1>
                    <div className="error-state">
                        <p>{error}</p>
                        <button onClick={fetchData}>다시 시도</button>
                    </div>
                </div>
            </Shell>
        );
    }

    const headers = ['이름', '국가', '계급', '통솔', '묠력', '지력', '경험', '충성', '병사'];
    const rows = filtered.map(g => [
        g.name,
        g.nation,
        <StatusBadge key={`lvl-${g.id}`} variant="gold">{g.officerLevel}급</StatusBadge>,
        g.leadership,
        g.strength,
        g.intel,
        formatNumber(g.experience),
        g.devotion,
        formatNumber(g.crew),
    ]);

    return (
        <Shell>
            <div className="page-content">
                <h1>전체 장수</h1>
                <div className="search-bar">
                    <input
                        type="text"
                        placeholder="장수 검색 (이름, 통솔, 묠력, 지력)"
                        value={search}
                        onChange={e => setSearch(e.target.value)}
                    />
                    <span className="search-count">{filtered.length} / {allGenerals.length}명</span>
                </div>
                {filtered.length === 0 ? (
                    <p className="text-muted">검색 결과가 없습니다.</p>
                ) : (
                    <GameTable headers={headers} rows={rows} />
                )}
            </div>
        </Shell>
    );
}
