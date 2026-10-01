'use client';

import GameShell from '@/components/GameShell';
import { TerritoryScreen } from '@/components/territory/TerritoryScreen';
import { campaignHref } from '@/lib/campaign-screens';
import { useGameSession } from '@/lib/campaign-session';

/**
 * 영지 — 배치 · 방침 · 공사(P-T01). 셋 다 12순 슬롯을 쓰지 않는 지속 입력이다(`POST /api/commands/{placement|policy|work}/…`).
 * 출병은 직접 행동이라 이 화면에서 뺐다 — 명령 흐름(작전실) · 군단으로 간다(설계 P-T01).
 */
export default function TerritoryPage() {
    const { serverId } = useGameSession();
    return (
        <GameShell title="영지">
            <TerritoryScreen hrefs={{ supply: campaignHref('territory/supply', serverId), court: campaignHref('court', serverId) }} />
        </GameShell>
    );
}
