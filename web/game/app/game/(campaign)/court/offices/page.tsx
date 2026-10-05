'use client';

// 관직 · 봉신(P-K03 · P-K04) — /game/<서버>/court/offices. 내용은 components/offices/OfficesScreen(지금은 서버 대기 골격).
import GameShell from '@/components/GameShell';
import OfficesScreen from '@/components/offices/OfficesScreen';

export default function OfficesPage() {
    return (
        <GameShell title="관직 · 봉신">
            <OfficesScreen />
        </GameShell>
    );
}
