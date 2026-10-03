'use client';

import GameShell from '@/components/GameShell';
import { CaptivesScreen } from '@/components/people/CaptivesScreen';
import { campaignHref } from '@/lib/campaign-screens';
import { useGameSession } from '@/lib/campaign-session';

/**
 * 부 › 포로 · 등용(P-R05) — `/game/<서버>/retinue/captives`. 인재(등용 옵션 · 인재탐색)는 지금 읽기로 채우고,
 * 포로 목록 읽기(K4-12)가 오기 전에는 「잡은 포로」 칸이 서버 대기다.
 */
export default function CaptivesPage() {
    const { serverId } = useGameSession();
    return (
        <GameShell title="포로 · 등용">
            <CaptivesScreen hrefs={{ flowBase: campaignHref('', serverId), yuedan: campaignHref('retinue/yuedan', serverId) }} />
        </GameShell>
    );
}
