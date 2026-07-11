'use client';

import { useEffect, useState } from 'react';
import Shell from '../../../../components/Shell';
import GameTable from '../../../../components/GameTable';
import { api } from '../../../../lib/api';
import { formatNumber } from '../../../../lib/format';
import type { TrafficSummary } from '../../../../types/game';

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

  const refreshRows = data.history.map((h) => [
    `${h.year}년 ${h.month}월`,
    h.date,
    formatNumber(h.refresh),
  ]);
  const onlineRows = data.history.map((h) => [
    `${h.year}년 ${h.month}월`,
    h.date,
    formatNumber(h.online),
  ]);
  const refresherRows = [
    ['접속자 총합', formatNumber(data.totalRefreshScore), formatNumber(data.totalRefresh)],
    ...data.topRefreshers.map((user) => [
      user.name,
      formatNumber(user.refreshScoreTotal),
      formatNumber(user.refresh),
    ]),
  ];

  return (
    <Shell>
      <h1 style={{ fontSize: 'var(--text-2xl)', marginBottom: 'var(--space-lg)' }}>접속 통계</h1>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 360px), 1fr))', gap: 'var(--space-lg)' }}>
        <section>
          <h2 style={{ fontSize: 'var(--text-xl)', marginBottom: 'var(--space-sm)' }}>접 속 량</h2>
          <GameTable headers={['연월', '시각', '갱신']} rows={refreshRows} />
          <p style={{ marginTop: 'var(--space-sm)', color: 'var(--text-muted)' }}>
            현재 {formatNumber(data.refresh)} · 최고 {formatNumber(data.maxRefresh)}
          </p>
        </section>
        <section>
          <h2 style={{ fontSize: 'var(--text-xl)', marginBottom: 'var(--space-sm)' }}>접 속 자</h2>
          <GameTable headers={['연월', '시각', '접속자']} rows={onlineRows} />
          <p style={{ marginTop: 'var(--space-sm)', color: 'var(--text-muted)' }}>
            현재 {formatNumber(data.currentOnline)} · 최고 {formatNumber(data.maxOnline)}
          </p>
        </section>
      </div>

      <section style={{ marginTop: 'var(--space-xl)' }}>
        <h2 style={{ fontSize: 'var(--text-xl)', marginBottom: 'var(--space-md)' }}>주 의 대 상 자 (순간과도갱신)</h2>
        <GameTable headers={['장수', '누적 벌점', '갱신']} rows={refresherRows} />
      </section>
    </Shell>
  );
}
