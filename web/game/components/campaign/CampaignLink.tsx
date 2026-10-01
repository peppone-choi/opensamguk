'use client';

import Link from 'next/link';
import type { ComponentProps } from 'react';
import { campaignHref } from '@/lib/campaign-screens';
import { useGameSession } from '@/lib/campaign-session';

export type CampaignLinkProps = Omit<ComponentProps<typeof Link>, 'href' | 'prefetch'> & {
    /** 캠페인 화면 slug(`campaign-screens.ts`). */
    readonly slug: string;
    /** 뒤에 붙일 쿼리(`?person=3`). */
    readonly query?: string;
};

/**
 * 캠페인 화면 링크 — 주소는 늘 `campaignHref(slug, serverId)` 로 만든다.
 *
 * 서버 식별자는 `sam_server` 쿠키를 첫 렌더 뒤에 읽어서 정해진다. 그 전의 첫 렌더에서는 주소가 서버 없는
 * `/game/<화면>` 인데, 운영 게이트웨이는 `/game/<첫 조각>` 을 서버 이름으로 읽어 이 주소에 502 를 낸다
 * (K10 pep 측정 2026-09-30: 모바일 미리 불러오기 `/game/yuedan?_rsc=…` 등 5건). 그래서 서버를 모르는 동안은
 * 미리 불러오지 않는다. 서버가 정해지면 주소가 `/game/<서버>/<화면>` 으로 바뀌고 미리 불러오기가 켜진다.
 */
export default function CampaignLink({ slug, query = '', ...rest }: CampaignLinkProps) {
    const { serverId } = useGameSession();
    return <Link {...rest} href={`${campaignHref(slug, serverId)}${query}`} prefetch={serverId ? undefined : false} />;
}
