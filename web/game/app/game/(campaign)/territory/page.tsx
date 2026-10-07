'use client';

import { useSearchParams } from 'next/navigation';
import GameShell from '@/components/GameShell';
import { TerritoryScreen, territoryPlacementCounty, territoryView } from '@/components/territory/TerritoryScreen';
import { campaignHref } from '@/lib/campaign-screens';
import { useGameSession } from '@/lib/campaign-session';

/**
 * 영지 — 배치 · 방침 · 공사(P-T01). 셋 다 12순 슬롯을 쓰지 않는 지속 입력이다(`POST /api/commands/{placement|policy|work}/…`).
 * 출병은 직접 행동이라 이 화면에서 뺐다 — 명령 흐름(작전실) · 군단으로 간다(설계 P-T01).
 * `?view=placement|policy|work` 는 처음 펼칠 칸(도움말 첫걸음 「공사」 바로가기 → `?view=work`).
 */
export default function TerritoryPage() {
    const { serverId } = useGameSession();
    const query = useSearchParams();
    const view = territoryView(query?.get('view'));
    const countyId = view === 'placement' ? territoryPlacementCounty(query?.get('countyId')) : null;
    return (
        <GameShell title="영지">
            <TerritoryScreen initialView={view} initialCountyId={countyId} hrefs={{ supply: campaignHref('territory/supply', serverId), court: campaignHref('court', serverId) }} />
        </GameShell>
    );
}
