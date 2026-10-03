'use client';

// 시즌 결산(P-H05) — /game/<서버>/records/season. 내용은 components/season-result/SeasonResultScreen(지금은 서버 대기 골격).
// 시즌 결과는 모두에게 보이는 정보라 장수가 없어도 연다(requiresHwiha=false, 천하 형세와 같다).
import GameShell from '@/components/GameShell';
import SeasonResultScreen from '@/components/season-result/SeasonResultScreen';

export default function SeasonResultPage() {
    return (
        <GameShell title="시즌 결산" requiresHwiha={false}>
            <SeasonResultScreen />
        </GameShell>
    );
}
