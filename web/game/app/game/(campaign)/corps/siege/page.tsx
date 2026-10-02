'use client';

import { useSearchParams } from 'next/navigation';
import GameShell from '@/components/GameShell';
import { SiegeScreen } from '@/components/siege/SiegeScreen';
import { campaignHref } from '@/lib/campaign-screens';
import { useGameSession } from '@/lib/campaign-session';

/**
 * 군단 › 공성(P-C02). 포위한 성 · 도로 보루의 형편을 보고 강공 · 항복 권고 · 보루 포위를 명령 흐름(작전실 `?do=`)으로 넣는다.
 * 순은 흐름에서 고른다 — 옛 화면처럼 첫 빈 순에 자동으로 넣지 않는다(설계 P-C02, K6 Q9). `?county=<현 id>` 는 처음 고를 포위.
 */
export default function SiegePage() {
    const { serverId } = useGameSession();
    const warRoom = campaignHref('', serverId);
    const rawCounty = useSearchParams()?.get('county');
    const county = rawCounty && /^\d{1,9}$/.test(rawCounty) ? Number(rawCounty) : null;
    return (
        <GameShell title="군단">
            <SiegeScreen initialCounty={county} hrefs={{ flow: (inputId) => `${warRoom}?do=${inputId}`, stratagem: campaignHref('stratagem', serverId) }} />
        </GameShell>
    );
}
