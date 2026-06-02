'use client';

import { useEffect, useState } from 'react';
import { useParams } from 'next/navigation';
import Link from 'next/link';
import Shell from '../../../../../components/Shell';
import GameCard from '../../../../../components/GameCard';
import GameTable from '../../../../../components/GameTable';
import StatusBadge from '../../../../../components/StatusBadge';
import { api } from '../../../../../lib/api';
import { formatDate, formatNumber } from '../../../../../lib/format';
import type { EmperorDetail } from '../../../../../types/game';

export default function EmperorDetailPage() {
  const params = useParams();
  const id = Number(params.id);
  const [data, setData] = useState<EmperorDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    if (!id || isNaN(id)) {
      setError('잘못된 ID입니다.');
      setLoading(false);
      return;
    }
    api.rankings.emperorDetail<EmperorDetail>(id)
      .then(setData)
      .catch(() => setError('데이터를 불러올 수 없습니다.'))
      .finally(() => setLoading(false));
  }, [id]);

  if (loading) return (
    <Shell>
      <h1 style={{ fontSize: 'var(--text-2xl)', marginBottom: 'var(--space-lg)' }}>황제 상세</h1>
      <p style={{ color: 'var(--text-muted)' }}>로딩 중...</p>
    </Shell>
  );

  if (error || !data) return (
    <Shell>
      <h1 style={{ fontSize: 'var(--text-2xl)', marginBottom: 'var(--space-lg)' }}>황제 상세</h1>
      <p style={{ color: 'var(--crimson)' }}>{error || '데이터를 찾을 수 없습니다.'}</p>
      <Link href="/game/rankings/emperor" style={{ color: 'var(--gold)', marginTop: 'var(--space-md)', display: 'inline-block' }}>
        ← 황제 목록으로
      </Link>
    </Shell>
  );

  const genHeaders = ['장수', '통솔', '묠력', '지력'];
  const genRows = data.generals.map((g) => [
    g.name,
    formatNumber(g.leadership),
    formatNumber(g.strength),
    formatNumber(g.intel),
  ]);

  const cityHeaders = ['도시', '등급', '인구'];
  const cityRows = data.cities.map((c) => [
    c.name,
    c.level,
    formatNumber(c.pop),
  ]);

  return (
    <Shell>
      <h1 style={{ fontSize: 'var(--text-2xl)', marginBottom: 'var(--space-lg)' }}>
        <StatusBadge variant="gold">{data.id}대</StatusBadge>{' '}
        {data.name} — 황제 상세
      </h1>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: 'var(--space-md)', marginBottom: 'var(--space-lg)' }}>
        <GameCard>
          <div style={{ color: 'var(--text-muted)', fontSize: 'var(--text-sm)' }}>국가</div>
          <div style={{ color: data.nationColor, fontSize: 'var(--text-xl)', fontWeight: 600 }}>{data.nation}</div>
        </GameCard>
        <GameCard>
          <div style={{ color: 'var(--text-muted)', fontSize: 'var(--text-sm)' }}>통일 시기</div>
          <div style={{ fontSize: 'var(--text-xl)', fontWeight: 600 }}>{formatDate(data.unifiedAt)}</div>
        </GameCard>
        <GameCard>
          <div style={{ color: 'var(--text-muted)', fontSize: 'var(--text-sm)' }}>턴</div>
          <div style={{ fontSize: 'var(--text-xl)', fontWeight: 600 }}>{data.year}년 {data.month}월</div>
        </GameCard>
        <GameCard>
          <div style={{ color: 'var(--text-muted)', fontSize: 'var(--text-sm)' }}>장수 / 도시</div>
          <div style={{ fontSize: 'var(--text-xl)', fontWeight: 600 }}>{data.generalCount} / {data.cityCount}</div>
        </GameCard>
        <GameCard>
          <div style={{ color: 'var(--text-muted)', fontSize: 'var(--text-sm)' }}>금 / 쌀 / 인구</div>
          <div style={{ fontSize: 'var(--text-xl)', fontWeight: 600 }}>{formatNumber(data.totalGold)} / {formatNumber(data.totalRice)} / {formatNumber(data.totalPop)}</div>
        </GameCard>
      </div>

      <h2 style={{ fontSize: 'var(--text-xl)', marginBottom: 'var(--space-md)' }}>수하 장수</h2>
      <GameTable headers={genHeaders} rows={genRows} />

      <h2 style={{ fontSize: 'var(--text-xl)', margin: 'var(--space-lg) 0 var(--space-md)' }}>지배 도시</h2>
      <GameTable headers={cityHeaders} rows={cityRows} />

      <Link href="/game/rankings/emperor" style={{ color: 'var(--gold)', marginTop: 'var(--space-lg)', display: 'inline-block' }}>
        ← 황제 목록으로
      </Link>
    </Shell>
  );
}
