'use client';

import { useCallback, useEffect, useState } from 'react';
import Shell from '../../../../components/Shell';
import GameTable from '../../../../components/GameTable';
import StatusBadge from '../../../../components/StatusBadge';
import { api } from '../../../../lib/api';
import { formatDate } from '../../../../lib/format';
import { useServerGameUrl } from '../../../../lib/serverGameUrl';
import { useTurnRefresh } from '../../../../hooks/useTurnRefresh';
import type { EmperorRecord } from '../../../../types/game';

export default function EmperorPage() {
  const emperorBaseHref = useServerGameUrl('rankings/emperor');
  const [data, setData] = useState<EmperorRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  // OPENSAM-196 — background=true면 로딩 화면을 다시 띄우지 않는다.
  const fetchData = useCallback((background = false) => {
    if (!background) setLoading(true);
    api.rankings.emperor<EmperorRecord[]>()
      .then(setData)
      .catch(() => setError('데이터를 불러올 수 없습니다.'))
      .finally(() => { if (!background) setLoading(false); });
  }, []);

  useEffect(() => { fetchData(); }, [fetchData]);

  // OPENSAM-196 — 턴 종료 시 황제 목록 재조회.
  useTurnRefresh(() => fetchData(true));

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
    <a key="emperor" href={`${emperorBaseHref}/${encodeURIComponent(String(e.id))}`} style={{ color: 'var(--gold)' }}>{e.name}</a>,
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
