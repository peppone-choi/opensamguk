'use client';

// 참모 제안(P-K05) — /game/<서버>/court/proposals(조정 묶음 한 화면, D58). 내용은 components/proposals/ProposalsScreen(지금은 서버 대기 골격).
import GameShell from '@/components/GameShell';
import ProposalsScreen from '@/components/proposals/ProposalsScreen';

export default function ProposalsPage() {
    return (
        <GameShell title="참모 제안">
            <ProposalsScreen />
        </GameShell>
    );
}
