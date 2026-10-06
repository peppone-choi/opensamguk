'use client';

import { Suspense } from 'react';
import CreateScreen from '@/components/entry/CreateScreen';

/** 새 장수 만들기(P-E02) — 입장 흐름(머리줄만, 레일 없음). 서버(#1137)가 없으면 화면이 「생성 대기」를 보인다. */
export default function CreatePage() {
  return (
    <Suspense fallback={null}>
      <CreateScreen />
    </Suspense>
  );
}
