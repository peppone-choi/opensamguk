'use client';

import { useEffect, useState } from 'react';
import { Portrait } from '@opensamguk/ui';
import Shell from '@/components/Shell';
import PageHead from '@/components/PageHead';
import GameCard from '@/components/GameCard';
import GeneralBasicCard from '@/components/game/GeneralBasicCard';
import MyInfoLogPanel from '@/components/game/MyInfoLogPanel';
import { api } from '@/lib/api';
import { formatNumber } from '@/lib/format';
import { useTurnRefresh } from '@/hooks/useTurnRefresh';
import { resolveServerGamePath } from '@/lib/serverGameUrl';
import type { FrontInfoResponse, MyPageResponse } from '@/lib/types';

function InfoGrid({ rows }: { rows: [string, React.ReactNode][] }) {
  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, minmax(0, 1fr))', gap: 1, background: 'var(--border-subtle)', fontSize: 'var(--text-sm)' }}>
      {rows.map(([label, value]) => (
        <div key={label} className="basic-card-row" style={{ display: 'contents' }}>
          <div className="basic-card-head">{label}</div>
          <div className="basic-card-body">{value}</div>
        </div>
      ))}
    </div>
  );
}

export default function MyPage() {
  const [frontInfo, setFrontInfo] = useState<FrontInfoResponse | null>(null);
  const [myPage, setMyPage] = useState<MyPageResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const fetchData = (background = false) => {
    if (!background) setLoading(true);
    setError('');
    Promise.all([api.frontInfo(), api.myPage<MyPageResponse>()])
      .then(([front, mine]) => { setFrontInfo(front); setMyPage(mine); })
      .catch(() => setError('내 정보를 불러올 수 없습니다.'))
      .finally(() => { if (!background) setLoading(false); });
  };

  useEffect(fetchData, []);
  useTurnRefresh(() => fetchData(true));

  if (loading || error || !frontInfo || !myPage || !frontInfo.general.hasGeneral) {
    return (
      <Shell>
        <div className="page-content">
          <PageHead title="내 정보" />
          {loading ? <p className="text-muted">로딩 중...</p> : (
            <div className="error-state">
              <p>{error || '장수 정보가 없습니다.'}</p>
              <button type="button" onClick={() => fetchData()}>다시 시도</button>
            </div>
          )}
        </div>
      </Shell>
    );
  }

  const rows: [string, React.ReactNode][] = [
    ['소속', myPage.nationName ?? '재야'],
    ['현재 도시', myPage.cityName ?? '-'],
    ['통솔', myPage.leadership],
    ['무력', myPage.strength],
    ['지력', myPage.intel],
    ['정치', myPage.politics ?? '-'],
    ['매력', myPage.charm ?? '-'],
    ['부상', `${myPage.injury}%`],
    ['자금', formatNumber(myPage.gold)],
    ['군량', formatNumber(myPage.rice)],
    ['병사', formatNumber(myPage.crew)],
    ['훈련/사기', `${myPage.train} / ${myPage.atmos}`],
  ];
  const retinueHref = frontInfo.global.serverId
    ? resolveServerGamePath(undefined, frontInfo.global.serverId, '/game', 'retinue')
    : '/game/retinue';

  return (
    <Shell>
      <div className="page-content">
        <PageHead title="내 정보" chip={myPage.nationName ?? '재야'} />
        <section className="me-hero" aria-label={`${myPage.name} 개요`}>
          <Portrait
            picture={frontInfo.general.picture}
            imageServer={frontInfo.general.imageServer}
            size="hero"
            alt=""
            frameClassName="me-hero__portrait"
            ring={frontInfo.nation?.color ? { color: frontInfo.nation.color, reason: 'self' } : null}
          />
          <div className="me-hero__text">
            <div className="me-hero__name">{myPage.name}</div>
            <div className="me-hero__meta">
              <span>{myPage.nationName ?? '재야'}</span>
              <span>{myPage.cityName ?? '-'}</span>
              {frontInfo.general.officerLevelText && <span>{frontInfo.general.officerLevelText}</span>}
            </div>
          </div>
        </section>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(320px, 1fr))', gap: 'var(--space-md)', marginBottom: 'var(--space-md)' }}>
          <GeneralBasicCard general={frontInfo.general} nation={frontInfo.nation} />
          <GameCard>
            <div className="basic-card-name">{myPage.name}</div>
            <InfoGrid rows={rows} />
          </GameCard>
        </div>
        <a className="os-button os-button--primary" href={retinueHref}>휘하 편성</a>
        <MyInfoLogPanel generalId={myPage.generalId} />
      </div>
    </Shell>
  );
}
