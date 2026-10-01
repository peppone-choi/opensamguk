'use client';

import { Suspense } from 'react';
import GameShell from '@/components/GameShell';
import RecordsScreen from '@/components/records/RecordsScreen';

/**
 * 기록 5분류 — 보드 V31K5Records(P-H01). 천하 정세는 장수가 없어도 보이므로 셸의 「장수 필요」 막음을 쓰지 않는다.
 * 비공개 네 분류의 막힘 · 빈 상태는 화면이 분류마다 알린다.
 */
export default function RecordsPage() {
  return (
    <GameShell title="기록" requiresHwiha={false}>
      <Suspense fallback={null}>
        <RecordsScreen />
      </Suspense>
    </GameShell>
  );
}
