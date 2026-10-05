'use client';

import { Suspense } from 'react';
import HistoricalScreen from '@/components/entry/HistoricalScreen';

/** 역사 인물 고르기(P-E03) — 입장 흐름(머리줄만, 레일 없음). 서버(#1137)가 없으면 화면이 「생성 대기」를 보인다. */
export default function HistoricalCreatePage() {
  return (
    <Suspense fallback={null}>
      <HistoricalScreen />
    </Suspense>
  );
}
