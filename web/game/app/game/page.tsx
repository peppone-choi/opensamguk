import { Suspense } from 'react';
import GameEntry from '@/components/campaign/GameEntry';

export default function GameMainPage() {
  return (
    <Suspense fallback={null}>
      <GameEntry />
    </Suspense>
  );
}
