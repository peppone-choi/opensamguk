'use client';

import { useEffect, useState } from 'react';
import Shell from '../../../../components/Shell';
import GameTable from '../../../../components/GameTable';
import StatusBadge from '../../../../components/StatusBadge';
import { api } from '../../../../lib/api';
import { formatNumber } from '../../../../lib/format';

interface NpcGeneral {
  generalId: number;
  name: string;
  nation: string;
  nationColor: string;
  officerLevel: number;
  leadership: number;
  strength: number;
  intel: number;
  experience: number;
  devotion: number;
  crew: number;
  cityName: string;
}

export default function NpcsPage() {
  const [data, setData] = useState<NpcGeneral[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [filterNation, setFilterNation] = useState<string>('all');

  useEffect(() => {
    api.rankings.npcs<NpcGeneral[]>()
      .then(setData)
      .catch(() => setError('데이터를 불러올 수 없습니다.'))
      .finally(() => setLoading(false));
  }, []);

  const nations = Array.from(new Set(data.map((n) => n.nation))).sort();
  const filtered = filterNation === 'all' ? data : data.filter((n) => n.nation === filterNation);

  if (loading) return (
    <Shell>
      <h1 style={{ fontSize: 'var(--text-2xl)', marginBottom: 'var(--space-lg)' }}>NPC 일람</h1>
      <p style={{ color: 'var(--text-muted)' }}>로딩 중...</p>
    </Shell>
  );

  if (error) return (
    <Shell>
      <h1 style={{ fontSize: 'var(--text-2xl)', marginBottom: 'var(--space-lg)' }}>NPC 일람</h1>
      <p style={{ color: 'var(--crimson)' }}>{error}</p>
    </Shell>
  );

  const headers = ['장수', '국가', '직위', '통솔', '묠력', '지력', '경험', '충성', '병력', '도시'];
  const rows = filtered.map((g) => [
    g.name,
    <span key="nation" style={{ color: g.nationColor }}>{g.nation}</span>,
    g.officerLevel,
    formatNumber(g.leadership),
    formatNumber(g.strength),
    formatNumber(g.intel),
    formatNumber(g.experience),
    formatNumber(g.devotion),
    formatNumber(g.crew),
    g.cityName,
  ]);

  return (
    <Shell>
      <h1 style={{ fontSize: 'var(--text-2xl)', marginBottom: 'var(--space-lg)' }}>NPC 일람</h1>

      <div style={{ marginBottom: 'var(--space-md)' }}>
        <label style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-sm)', fontSize: 'var(--text-sm)' }}>
          <span style={{ color: 'var(--text-secondary)' }}>국가 필터:</span>
          <select
            value={filterNation}
            onChange={(e) => setFilterNation(e.target.value)}
            style={{ minWidth: 140 }}
          >
            <option value="all">전체</option>
            {nations.map((n) => (
              <option key={n} value={n}>{n}</option>
            ))}
          </select>
          <span style={{ color: 'var(--text-muted)' }}>{filtered.length}명</span>
        </label>
      </div>

      <GameTable headers={headers} rows={rows} />
    </Shell>
  );
}
