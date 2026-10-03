'use client';

// 역정보(P-K06) — /game/<서버>/stratagem/counter-intel(계책 묶음, K0 확정). 내용은 components/counter-intel/CounterIntelScreen(지금은 서버 대기 골격).
import GameShell from '@/components/GameShell';
import CounterIntelScreen from '@/components/counter-intel/CounterIntelScreen';

export default function CounterIntelPage() {
    return (
        <GameShell title="역정보">
            <CounterIntelScreen />
        </GameShell>
    );
}
