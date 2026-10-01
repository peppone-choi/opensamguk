'use client';

// 황실(P-K09) — /game/<서버>/court/imperial. 내용은 components/imperial/ImperialScreen.
// 황제 소재지는 로그인 없이도 열리는 공개 읽기라 장수가 없어도 보인다(requiresHwiha=false).
import GameShell from '@/components/GameShell';
import ImperialScreen from '@/components/imperial/ImperialScreen';

export default function ImperialPage() {
    return (
        <GameShell title="황실" requiresHwiha={false}>
            <ImperialScreen />
        </GameShell>
    );
}
