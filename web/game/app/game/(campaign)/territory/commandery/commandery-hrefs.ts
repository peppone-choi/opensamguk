import { campaignHref } from '@/lib/campaign-screens';

/** 군 내정 현황(P-T03) 화면의 주소 — 현 상세(P-T02) · 명령 흐름(대상 미리 채움). */
export function commanderyHrefs(serverId: string | undefined) {
    return {
        county: (cityId: number) => campaignHref(`territory/county/${cityId}`, serverId),
        flow: (inputId: string, target: string) => `${campaignHref('', serverId)}?${new URLSearchParams({ do: inputId, target }).toString()}`,
    };
}
