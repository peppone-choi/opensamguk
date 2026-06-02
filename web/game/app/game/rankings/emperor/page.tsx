'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import Shell from '../../../../components/Shell';
import GameTable from '../../../../components/GameTable';
import StatusBadge from '../../../../components/StatusBadge';
import { api } from '../../../../lib/api';
import { formatDate } from '../../../../lib/format';
import type { EmperorRecord } from '../../../../types/game';

export default function EmperorPage() {
  const [data, setData] = useState<EmperorRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    api.rankings.emperor<EmperorRecord[]>()
      .then(setData)
      .catch(() => setError('데이터를 불러올 수 없습니다.'))
      .finally(() => setLoading(false));
  }, []);

  if (loading) return (
    <Shell>
      <h1 style={{ fontSize: 'var(--text-2xl)', marginBottom: 'var(--space-lg)' }}>황제 정보</h1>
      <p style={{ color: 'var(--text-muted)' }}>로딩 중...</p>
    </Shell>
  );

  if (error) return (
    <Shell>
      <h1 style={{ fontSize: 'var(--text-2xl)', marginBottom: 'var(--space-lg)' }}>황제 정보</h1>
      <p style={{ color: 'var(--crimson)' }}>{error}</p>
    </Shell>
  );

  const headers = ['대수', '황제', '국가', '통일 시기', '턴', '장수', '도시'];
  const rows = data.map((e) => [
    e.id <= 3
      ? <StatusBadge variant={e.id === 1 ? 'gold' : e.id === 2 ? 'jade' : 'muted'}>{e.id}</StatusBadge>
      : e.id,
    <Link key="emperor" href={`/game/rankings/emperor/${e.id}`} style={{ color: 'var(--gold)' }}>{e.name}</Link>,
    <span key="nation" style={{ color: e.nationColor }}>{e.nation}</span>,
    formatDate(e.unifiedAt),
    `${e.year}년 ${e.month}월`,
    e.generalCount,
    e.cityCount,
  ]);

  return (
    <Shell>
      <h1 style={{ fontSize: 'var(--text-2xl)', marginBottom: 'var(--space-lg)' }}>황제 정보</h1>
      <GameTable headers={headers} rows={rows} />
    </Shell>
  );
}
