'use client';

import { Suspense } from 'react';
import { useSearchParams } from 'next/navigation';
import Shell from '../../components/Shell';
import GameChrome from '../../components/game/GameChrome';
import MainRecordZone from '../../components/game/MainRecordZone';

function GameMainContent() {
    const entryMode = useSearchParams().get('entry') === 'possession' ? 'possession' : undefined;

    return (
        <Shell>
            <GameChrome entryMode={entryMode}>
                {(frontInfo) => <MainRecordZone recentRecord={frontInfo.recentRecord} />}
            </GameChrome>
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
