'use client';

// 천하 형세(P-H04) — /game/<서버>/records/unification. 내용은 components/unification/UnificationScreen(지금은 서버 대기 골격).
// 통일까지의 거리는 모두에게 보이는 정보라 장수가 없어도 연다(requiresHwiha=false). 공개 범위 결정은 Q8(서버).
import GameShell from '@/components/GameShell';
import UnificationScreen from '@/components/unification/UnificationScreen';

export default function UnificationPage() {
    return (
        <GameShell title="천하 형세" requiresHwiha={false}>
            <UnificationScreen />
        </GameShell>
    );
}
