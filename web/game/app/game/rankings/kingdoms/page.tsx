'use client';

import { useEffect, useState } from 'react';
import Shell from '../../../../components/Shell';
import GameCard from '../../../../components/GameCard';
import GameTable from '../../../../components/GameTable';
import StatusBadge from '../../../../components/StatusBadge';
import { api } from '../../../../lib/api';
import { formatNumber } from '../../../../lib/format';

interface KingdomRank {
  rank: number;
  nationId: number;
  name: string;
  color: string;
  level: number;
  gold: number;
  rice: number;
  pop: number;
  genNum: number;
  power: number;
  cityCount: number;
  capitalName: string;
}

export default function KingdomsPage() {
  const [data, setData] = useState<KingdomRank[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    api.rankings.kingdoms<KingdomRank[]>()
      .then(setData)
      .catch(() => setError('데이터를 불러올 수 없습니다.'))
      .finally(() => setLoading(false));
  }, []);

  if (loading) return (
    <Shell>
      <h1 style={{ fontSize: 'var(--text-2xl)', marginBottom: 'var(--space-lg)' }}>세력 순위</h1>
      <p style={{ color: 'var(--text-muted)' }}>로딩 중...</p>
    </Shell>
  );

  if (error) return (
    <Shell>
      <h1 style={{ fontSize: 'var(--text-2xl)', marginBottom: 'var(--space-lg)' }}>세력 순위</h1>
      <p style={{ color: 'var(--crimson)' }}>{error}</p>
    </Shell>
  );

  const headers = ['순위', '국가', '등급', '금', '쌀', '인구', '장수', '병력', '도시', '수도'];
  const rows = data.map((k) => [
    k.rank <= 3
      ? <StatusBadge variant={k.rank === 1 ? 'gold' : k.rank === 2 ? 'jade' : 'muted'}>{k.rank}</StatusBadge>
      : k.rank,
    <span key="nation" style={{ color: k.color, fontWeight: 600 }}>{k.name}</span>,
    k.level,
    formatNumber(k.gold),
    formatNumber(k.rice),
    formatNumber(k.pop),
    k.genNum,
    formatNumber(k.power),
    k.cityCount,
    k.capitalName,
  ]);

  return (
    <Shell>
      <h1 style={{ fontSize: 'var(--text-2xl)', marginBottom: 'var(--space-lg)' }}>세력 순위</h1>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(140px, 1fr))', gap: 'var(--space-md)', marginBottom: 'var(--space-lg)' }}>
        {data.slice(0, 3).map((k) => (
          <GameCard key={k.nationId}>
            <div style={{ textAlign: 'center' }}>
              <StatusBadge variant={k.rank === 1 ? 'gold' : k.rank === 2 ? 'jade' : 'muted'}>
                {k.rank}위
              </StatusBadge>
              <div style={{ color: k.color, fontSize: 'var(--text-xl)', fontWeight: 700, marginTop: 'var(--space-sm)' }}>
                {k.name}
              </div>
              <div style={{ color: 'var(--text-muted)', fontSize: 'var(--text-sm)', marginTop: 'var(--space-xs)' }}>
                병력 {formatNumber(k.power)} / 도시 {k.cityCount}
              </div>
            </div>
          </GameCard>
        ))}
      </div>

      <GameTable headers={headers} rows={rows} />
    </Shell>
  );
}
