import { Suspense } from 'react';
import GameEntry from '@/components/hwiha/GameEntry';
import HwihaLayout from './hwiha/layout';

export default function GameMainPage() {
  return (
    <HwihaLayout>
      <Suspense fallback={null}>
        <GameEntry />
      </Suspense>
    </HwihaLayout>
  );
}
