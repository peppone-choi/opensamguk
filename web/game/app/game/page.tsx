import { Suspense } from 'react';
import GameEntry from '@/components/campaign/GameEntry';
import GameLayout from './(campaign)/layout';

export default function GameMainPage() {
  return (
    <GameLayout>
      <Suspense fallback={null}>
        <GameEntry />
      </Suspense>
    </GameLayout>
  );
}
