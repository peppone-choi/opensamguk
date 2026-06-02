'use client';

import { useEffect, useState } from 'react';
import Shell from '../../../../components/Shell';
import GameTable from '../../../../components/GameTable';
import StatusBadge from '../../../../components/StatusBadge';
import { api } from '../../../../lib/api';
import { formatNumber } from '../../../../lib/format';
import type { BestGeneral } from '../../../../types/game';

export default function BestGeneralsPage() {
  const [data, setData] = useState<BestGeneral[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    api.rankings.bestGenerals<BestGeneral[]>()
      .then(setData)
      .catch(() => setError('데이터를 불러올 수 없습니다.'))
      .finally(() => setLoading(false));
  }, []);

  if (loading) return (
    <Shell>
      <h1 style={{ fontSize: 'var(--text-2xl)', marginBottom: 'var(--space-lg)' }}>명장 순위</h1>
      <p style={{ color: 'var(--text-muted)' }}>로딩 중...</p>
    </Shell>
  );

  if (error) return (
    <Shell>
      <h1 style={{ fontSize: 'var(--text-2xl)', marginBottom: 'var(--space-lg)' }}>명장 순위</h1>
      <p style={{ color: 'var(--crimson)' }}>{error}</p>
    </Shell>
  );

  const headers = ['순위', '장수', '국가', '통솔', '묠력', '지력', '합계'];
  const rows = data.map((g) => [
    g.rank <= 3
      ? <StatusBadge variant={g.rank === 1 ? 'gold' : g.rank === 2 ? 'jade' : 'muted'}>{g.rank}</StatusBadge>
      : g.rank,
    g.name,
    <span key="nation" style={{ color: g.nationColor }}>{g.nation}</span>,
    formatNumber(g.leadership),
    formatNumber(g.strength),
    formatNumber(g.intel),
    <strong key="total">{formatNumber(g.total)}</strong>,
  ]);

  return (
    <Shell>
      <h1 style={{ fontSize: 'var(--text-2xl)', marginBottom: 'var(--space-lg)' }}>명장 순위</h1>
      <GameTable headers={headers} rows={rows} />
    </Shell>
  );
}
