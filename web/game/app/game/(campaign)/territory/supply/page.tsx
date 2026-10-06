'use client';

import GameShell from '@/components/GameShell';
import { SupplyScreen } from '@/components/territory/SupplyScreen';
import { campaignHref } from '@/lib/campaign-screens';
import { useGameSession } from '@/lib/campaign-session';

/**
 * 영지 › 창고망 · 보급(P-T04). 재고(`GET /api/warehouses`, 수도 먼저) · 끊긴 곳 · 물자조달(명령 흐름 `?do=action.transport`).
 * 끊긴 까닭 · 망 조각(K4-06) · 녹봉 전망(K4-14)은 서버 읽기가 없어 「준비 중」이다.
 */
export default function SupplyPage() {
    const { serverId } = useGameSession();
    return (
        <GameShell title="영지">
            <SupplyScreen hrefs={{ transport: `${campaignHref('', serverId)}?do=action.transport` }} />
        </GameShell>
    );
}
