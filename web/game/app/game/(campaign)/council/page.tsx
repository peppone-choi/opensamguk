'use client';

import { Suspense } from 'react';
import GameShell from '@/components/GameShell';
import CouncilScreen from '@/components/council/CouncilScreen';

/** 회의실 · 기밀실(P-Q01) — 보드 V31K5Council. 옛 /game/board 는 여기로 308(lib/legacyRoutes). 장수가 있어야 쓰는 화면이다. */
export default function CouncilPage() {
    return (
        <GameShell title="광장">
            <Suspense fallback={null}>
                <CouncilScreen />
            </Suspense>
        </GameShell>
    );
}
