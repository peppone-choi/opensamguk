'use client';

import { useEffect, useState } from 'react';
import Shell from '../../../../components/Shell';
import GameTable from '../../../../components/GameTable';
import StatusBadge from '../../../../components/StatusBadge';
import { api } from '../../../../lib/api';
import { formatNumber } from '../../../../lib/format';

interface GeneralRank {
  rank: number;
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
}

type SortKey = 'rank' | 'leadership' | 'strength' | 'intel' | 'experience' | 'devotion' | 'crew';

export default function GeneralsPage() {
  const [data, setData] = useState<GeneralRank[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [sortKey, setSortKey] = useState<SortKey>('rank');
  const [sortDesc, setSortDesc] = useState(false);

  useEffect(() => {
    api.rankings.allGenerals<GeneralRank[]>()
      .then(setData)
      .catch(() => setError('데이터를 불러올 수 없습니다.'))
      .finally(() => setLoading(false));
  }, []);

  function toggleSort(key: SortKey) {
    if (sortKey === key) {
      setSortDesc(!sortDesc);
    } else {
      setSortKey(key);
      setSortDesc(key !== 'rank');
    }
  }

  const sorted = [...data].sort((a, b) => {
    const av = a[sortKey];
    const bv = b[sortKey];
    if (typeof av === 'number' && typeof bv === 'number') {
      return sortDesc ? bv - av : av - bv;
    }
    return sortDesc ? String(bv).localeCompare(String(av)) : String(av).localeCompare(String(bv));
  });

  if (loading) return (
    <Shell>
      <h1 style={{ fontSize: 'var(--text-2xl)', marginBottom: 'var(--space-lg)' }}>장수 일람</h1>
      <p style={{ color: 'var(--text-muted)' }}>로딩 중...</p>
    </Shell>
  );

  if (error) return (
    <Shell>
      <h1 style={{ fontSize: 'var(--text-2xl)', marginBottom: 'var(--space-lg)' }}>장수 일람</h1>
      <p style={{ color: 'var(--crimson)' }}>{error}</p>
    </Shell>
  );

  const headers = ['순위', '장수', '국가', '직위', '통솔', '묠력', '지력', '경험', '충성', '병력'];
  const rows = sorted.map((g) => [
    g.rank <= 3
      ? <StatusBadge variant={g.rank === 1 ? 'gold' : g.rank === 2 ? 'jade' : 'muted'}>{g.rank}</StatusBadge>
      : g.rank,
    g.name,
    <span key="nation" style={{ color: g.nationColor }}>{g.nation}</span>,
    g.officerLevel,
    formatNumber(g.leadership),
    formatNumber(g.strength),
    formatNumber(g.intel),
    formatNumber(g.experience),
    formatNumber(g.devotion),
    formatNumber(g.crew),
  ]);

  return (
    <Shell>
      <h1 style={{ fontSize: 'var(--text-2xl)', marginBottom: 'var(--space-lg)' }}>장수 일람</h1>
      <div style={{ marginBottom: 'var(--space-md)', display: 'flex', gap: 'var(--space-sm)', flexWrap: 'wrap' }}>
        {(['rank', 'leadership', 'strength', 'intel', 'experience', 'devotion', 'crew'] as SortKey[]).map((k) => (
          <button
            key={k}
            onClick={() => toggleSort(k)}
            style={{
              background: sortKey === k ? 'var(--gold)' : 'var(--bg-hover)',
              color: sortKey === k ? '#000' : 'var(--text-primary)',
              fontSize: 'var(--text-sm)',
              padding: 'var(--space-xs) var(--space-sm)',
            }}
          >
            {k === 'rank' ? '순위' : k === 'leadership' ? '통솔' : k === 'strength' ? '묠력' : k === 'intel' ? '지력' : k === 'experience' ? '경험' : k === 'devotion' ? '충성' : '병력'}
            {sortKey === k && (sortDesc ? ' ▼' : ' ▲')}
          </button>
        ))}
      </div>
      <GameTable headers={headers} rows={rows} />
    </Shell>
  );
}
