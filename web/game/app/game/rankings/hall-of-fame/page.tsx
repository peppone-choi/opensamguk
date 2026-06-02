'use client';

import { useEffect, useState } from 'react';
import Shell from '../../../../components/Shell';
import GameTable from '../../../../components/GameTable';
import StatusBadge from '../../../../components/StatusBadge';
import { api } from '../../../../lib/api';
import { formatDate, formatNumber } from '../../../../lib/format';

interface HallRecord {
  id: number;
  category: string;
  name: string;
  nation: string;
  nationColor: string;
  value: number;
  valueLabel: string;
  achievedAt: string;
  turn: number;
}

export default function HallOfFamePage() {
  const [data, setData] = useState<HallRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [filterCategory, setFilterCategory] = useState<string>('all');

  useEffect(() => {
    api.rankings.hallOfFame<HallRecord[]>()
      .then(setData)
      .catch(() => setError('데이터를 불러올 수 없습니다.'))
      .finally(() => setLoading(false));
  }, []);

  const categories = Array.from(new Set(data.map((r) => r.category))).sort();
  const filtered = filterCategory === 'all' ? data : data.filter((r) => r.category === filterCategory);

  if (loading) return (
    <Shell>
      <h1 style={{ fontSize: 'var(--text-2xl)', marginBottom: 'var(--space-lg)' }}>명예의 전당</h1>
      <p style={{ color: 'var(--text-muted)' }}>로딩 중...</p>
    </Shell>
  );

  if (error) return (
    <Shell>
      <h1 style={{ fontSize: 'var(--text-2xl)', marginBottom: 'var(--space-lg)' }}>명예의 전당</h1>
      <p style={{ color: 'var(--crimson)' }}>{error}</p>
    </Shell>
  );

  const headers = ['분류', '기록', '이름', '국가', '수치', '달성 시기', '턴'];
  const rows = filtered.map((r) => [
    <StatusBadge key="category" variant="muted">{r.category}</StatusBadge>,
    r.valueLabel,
    r.name,
    <span key="nation" style={{ color: r.nationColor }}>{r.nation}</span>,
    formatNumber(r.value),
    formatDate(r.achievedAt),
    r.turn,
  ]);

  return (
    <Shell>
      <h1 style={{ fontSize: 'var(--text-2xl)', marginBottom: 'var(--space-lg)' }}>명예의 전당</h1>

      <div style={{ marginBottom: 'var(--space-md)' }}>
        <label style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-sm)', fontSize: 'var(--text-sm)' }}>
          <span style={{ color: 'var(--text-secondary)' }}>분류 필터:</span>
          <select
            value={filterCategory}
            onChange={(e) => setFilterCategory(e.target.value)}
            style={{ minWidth: 140 }}
          >
            <option value="all">전체</option>
            {categories.map((c) => (
              <option key={c} value={c}>{c}</option>
            ))}
          </select>
          <span style={{ color: 'var(--text-muted)' }}>{filtered.length}건</span>
        </label>
      </div>

      <GameTable headers={headers} rows={rows} />
    </Shell>
  );
}
