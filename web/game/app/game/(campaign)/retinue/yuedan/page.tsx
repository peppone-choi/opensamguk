'use client';

import GameShell from '@/components/GameShell';
import { YuedanScreen } from '@/components/yuedan/YuedanScreen';
import { campaignHref } from '@/lib/campaign-screens';
import { useGameSession } from '@/lib/campaign-session';

/** 월단평(P-R04) — 셸 머리 + 본문 하나. 매월 상순 명망 갱신 · 순위 발표(설계 §2.8 · §5.2)는 `GET /api/yuedan` 에서 읽는다. */
export default function YuedanPage() {
    const { serverId } = useGameSession();
    return (
        <GameShell title="월단평">
            <YuedanScreen retinueHref={campaignHref('retinue', serverId)} />
        </GameShell>
    );
}
