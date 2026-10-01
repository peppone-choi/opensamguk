'use client';

import GameShell from '@/components/GameShell';
import { CourtScreen } from '@/components/court/CourtScreen';
import { campaignHref } from '@/lib/campaign-screens';
import { useGameSession } from '@/lib/campaign-session';

/**
 * 조정(P-K01) — 받은 요청 · 발령 · 포상 · 조정 결정을 한 화면에. 옛 /game/orders(→ ?tab=orders) · 조정 구상 화면을 합쳤다.
 * 정치 동의 응답(선양 · 결의를 받은 쪽)은 「받은 요청」 띠가 받는다 — 옛 명령 창(CommandModal) 밖의 유일한 새 길.
 */
export default function CourtPage() {
    const { serverId } = useGameSession();
    return (
        <GameShell title="조정">
            <CourtScreen hrefs={{ territory: campaignHref('territory', serverId) }} />
        </GameShell>
    );
}
