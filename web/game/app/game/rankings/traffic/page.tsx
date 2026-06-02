'use client';

import { useEffect, useState } from 'react';
import Shell from '../../../../components/Shell';
import GameCard from '../../../../components/GameCard';
import GameTable from '../../../../components/GameTable';
import StatusBadge from '../../../../components/StatusBadge';
import { api } from '../../../../lib/api';
import { formatDate, formatNumber } from '../../../../lib/format';

interface TrafficStat {
  date: string;
  uniqueVisitors: number;
  pageViews: number;
  avgSessionMin: number;
  peakConcurrent: number;
}

interface TrafficSummary {
  todayUnique: number;
  todayViews: number;
  weekUnique: number;
  weekViews: number;
  monthUnique: number;
  monthViews: number;
  peakConcurrent: number;
  currentOnline: number;
  history: TrafficStat[];
}

export default function TrafficPage() {
  const [data, setData] = useState<TrafficSummary | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    api.rankings.traffic<TrafficSummary>()
      .then(setData)
      .catch(() => setError('데이터를 불러올 수 없습니다.'))
      .finally(() => setLoading(false));
  }, []);

  if (loading) return (
    <Shell>
      <h1 style={{ fontSize: 'var(--text-2xl)', marginBottom: 'var(--space-lg)' }}>접속 통계</h1>
      <p style={{ color: 'var(--text-muted)' }}>로딩 중...</p>
    </Shell>
  );

  if (error || !data) return (
    <Shell>
      <h1 style={{ fontSize: 'var(--text-2xl)', marginBottom: 'var(--space-lg)' }}>접속 통계</h1>
      <p style={{ color: 'var(--crimson)' }}>{error || '데이터를 찾을 수 없습니다.'}</p>
    </Shell>
  );

  const summaryCards = [
    { label: '현재 접속', value: data.currentOnline, variant: 'jade' as const },
    { label: '오늘 방문자', value: data.todayUnique, variant: 'gold' as const },
    { label: '오늘 페이지뷰', value: data.todayViews, variant: 'muted' as const },
    { label: '이번 주 방문자', value: data.weekUnique, variant: 'muted' as const },
    { label: '이번 주 페이지뷰', value: data.weekViews, variant: 'muted' as const },
    { label: '이번 달 방문자', value: data.monthUnique, variant: 'muted' as const },
    { label: '이번 달 페이지뷰', value: data.monthViews, variant: 'muted' as const },
    { label: '최고 동시접속', value: data.peakConcurrent, variant: 'crimson' as const },
  ];

  const headers = ['날짜', '방문자', '페이지뷰', '평균 체류', '최고 동시접속'];
  const rows = data.history.map((h) => [
    h.date,
    formatNumber(h.uniqueVisitors),
    formatNumber(h.pageViews),
    `${h.avgSessionMin}분`,
    formatNumber(h.peakConcurrent),
  ]);

  return (
    <Shell>
      <h1 style={{ fontSize: 'var(--text-2xl)', marginBottom: 'var(--space-lg)' }}>접속 통계</h1>

      <div style={{
        display: 'grid',
        gridTemplateColumns: 'repeat(auto-fill, minmax(160px, 1fr))',
        gap: 'var(--space-md)',
        marginBottom: 'var(--space-lg)',
      }}>
        {summaryCards.map((c) => (
          <GameCard key={c.label}>
            <div style={{ textAlign: 'center' }}>
              <div style={{ fontSize: 'var(--text-sm)', color: 'var(--text-muted)' }}>{c.label}</div>
              <div style={{ fontSize: 'var(--text-xl)', fontWeight: 700, marginTop: 'var(--space-xs)' }}>
                <StatusBadge variant={c.variant}>{formatNumber(c.value)}</StatusBadge>
              </div>
            </div>
          </GameCard>
        ))}
      </div>

      <h2 style={{ fontSize: 'var(--text-xl)', marginBottom: 'var(--space-md)' }}>일자별 통계</h2>
      <GameTable headers={headers} rows={rows} />
    </Shell>
  );
}
