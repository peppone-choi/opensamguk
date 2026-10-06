import { campaignHref } from '@/lib/campaign-screens';
import type { CountyScreenProps } from '@/components/county/CountyScreen';

/** 현 상세(P-T02)가 넘어가는 곳 — 영지(칸) · 조정 발령 · 기록 · 명령 흐름(작전실 쿼리). */
export function countyHrefs(serverId: string | undefined): CountyScreenProps['hrefs'] {
    const territory = campaignHref('territory', serverId);
    const warRoom = campaignHref('', serverId);
    return {
        territory: (view) => (view ? `${territory}?view=${view}` : territory),
        court: campaignHref('court?tab=orders', serverId),
        records: campaignHref('records', serverId),
        flow: (query) => `${warRoom}?${query}`,
    };
}
