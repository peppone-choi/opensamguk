import { Suspense } from 'react';
import GameEntry from '@/components/campaign/GameEntry';
import GameLayout from './hwiha/layout';

export default function GameMainPage() {
  return (
    <GameLayout>
      <Suspense fallback={null}>
        <GameEntry />
      </Suspense>
    </GameLayout>
  );
}
