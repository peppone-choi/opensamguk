'use client';

import { Suspense } from 'react';
import { useSearchParams } from 'next/navigation';
import Shell from '../../components/Shell';
import GameChrome from '../../components/game/GameChrome';

function GameMainContent() {
  const entryMode = useSearchParams().get('entry') === 'possession' ? 'possession' : undefined;

  return (
    <Shell>
      {/* 「지난 순」은 GameChrome 이 좌측 레일(.ib-records)에서 그린다. 여기서 한 번 더 넘기면
          같은 기록이 보드 바닥에 패널 없이 한 벌 더 붙어, 작전실이 두 조각으로 읽혔다. */}
      <GameChrome entryMode={entryMode} />
    </Shell>
  );
}

export default function GameMainPage() {
  return (
    <Suspense fallback={null}>
      <GameMainContent />
    </Suspense>
  );
}
