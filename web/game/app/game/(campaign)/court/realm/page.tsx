'use client';

// 세력(P-K10) — /game/<서버>/court/realm. 내용은 components/realm/RealmScreen.
// 옛 /game/my-nation 은 아직 남긴다 — 그 화면의 작전 진행 칸이 옮겨 갈 군단 · 세력 작전(K6 P-C01)이 아직 없다. 308 은 그 뒤에 켠다(lib/legacyRoutes).
import GameShell from '@/components/GameShell';
import RealmScreen from '@/components/realm/RealmScreen';

export default function RealmPage() {
    return (
        <GameShell title="세력">
            <RealmScreen />
        </GameShell>
    );
}
