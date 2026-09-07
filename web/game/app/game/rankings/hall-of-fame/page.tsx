'use client';

import { useCallback, useEffect, useState } from 'react';
import Shell from '../../../../components/Shell';
import PageHead from '../../../../components/PageHead';
import RecordsTabs from '../../../../components/records/RecordsTabs';
import GameTable from '../../../../components/GameTable';
import StatusBadge from '../../../../components/StatusBadge';
import { api } from '../../../../lib/api';
import { formatDate, formatNumber } from '../../../../lib/format';
import { useTurnRefresh } from '../../../../hooks/useTurnRefresh';
import type { HallRecord } from '../../../../types/game';

export default function HallOfFamePage() {
  const [data, setData] = useState<HallRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [filterCategory, setFilterCategory] = useState<string>('all');

  // OPENSAM-196 — background=true면 로딩 화면을 다시 띄우지 않는다.
  const fetchData = useCallback((background = false) => {
    if (!background) setLoading(true);
    api.rankings.hallOfFame<HallRecord[]>()
      .then(setData)
      .catch(() => setError('데이터를 불러올 수 없습니다.'))
      .finally(() => { if (!background) setLoading(false); });
  }, []);

  useEffect(() => { fetchData(); }, [fetchData]);

  // OPENSAM-196 — 턴 종료 시 명예의 전당 재조회.
  useTurnRefresh(() => fetchData(true));

  const categories = Array.from(new Set(data.map((r) => r.category))).sort();
  const filtered = filterCategory === 'all' ? data : data.filter((r) => r.category === filterCategory);

  if (loading) return (
    <Shell>
      <PageHead title="명예의 전당" tabs={<RecordsTabs />} />
      <p style={{ color: 'var(--text-muted)' }}>로딩 중...</p>
    </Shell>
  );

  if (error) return (
    <Shell>
      <PageHead title="명예의 전당" tabs={<RecordsTabs />} />
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
      <PageHead title="명예의 전당" tabs={<RecordsTabs />} />

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
