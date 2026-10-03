'use client';

// 세력(P-K10) — /game/<서버>/court/realm. 내용은 components/realm/RealmScreen.
// 옛 /game/my-nation 은 지우고 여기로 308 한다(lib/legacyRoutes). 그 화면의 작전 진행 칸(4X-B)은 옮기지 않았다 — 세력 작전의 새 자리는 군단 「세력 작전」 탭(K6 P-C01)이다(K0 10-01 22:4x).
import GameShell from '@/components/GameShell';
import RealmScreen from '@/components/realm/RealmScreen';

export default function RealmPage() {
    return (
        <GameShell title="세력">
            <RealmScreen />
        </GameShell>
    );
}
