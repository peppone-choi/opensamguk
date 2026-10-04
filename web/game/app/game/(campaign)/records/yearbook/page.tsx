'use client';

import { Suspense } from 'react';
import GameShell from '@/components/GameShell';
import YearbookScreen from '@/components/records/YearbookScreen';

/**
 * 연감(P-H02) — 보드 V31K5Yearbook. 공개 화면이라 셸의 「장수 필요」 막음을 쓰지 않는다.
 * 메뉴(NAV31 「연감」)는 계약판 K5-08 서버가 올 때까지 옛 연감(/game/history)을 가리킨다 — 이 화면은 주소로만 연다(CEO 10-05).
 */
export default function YearbookPage() {
    return (
        <GameShell title="기록" requiresHwiha={false}>
            <Suspense fallback={null}>
                <YearbookScreen />
            </Suspense>
        </GameShell>
    );
}
