'use client';

import { useRouter } from 'next/navigation';
import GameShell from '@/components/GameShell';
import { BattleRoomUnavailable } from '@/components/battle/BattleHub';
import { useServerGameUrl } from '@/lib/serverGameUrl';

/**
 * 전투 방(P-C03 참가 대기 · 배치 / P-C05 실시간 전투, 한 경로의 단계) — K6 설계서 §3.5.
 * 서버가 전투를 열기 전(K6-11 · CONTRACT:CAMPAIGN_BATTLE_PRODUCER)에는 어떤 전투 번호로 와도 「전투가 열리지 않습니다」뿐이다.
 */
export default function BattleRoomPage() {
    const router = useRouter();
    const hubHref = useServerGameUrl('corps/battle');
    return (
        <GameShell title="전투">
            <BattleRoomUnavailable onBack={() => router.push(hubHref)} />
        </GameShell>
    );
}
