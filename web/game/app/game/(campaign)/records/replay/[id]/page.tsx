'use client';

import { useParams } from 'next/navigation';
import GameShell from '@/components/GameShell';
import ReplayScreen from '@/components/records/ReplayScreen';

/**
 * 다시 보기(P-H03 격자 리플레이) — 보드 V31K5ReplayWait. 계약판 K5-09(C2) 읽기가 아직 없어 서버 대기로만 그린다.
 * 들어오는 길은 기록의 전투 목록 「다시 보기」(P-H01 RL6)다. 메뉴에는 없다.
 */
export default function ReplayPage() {
    const params = useParams<{ id: string }>();
    return (
        <GameShell title="기록" requiresHwiha={false}>
            <ReplayScreen rawId={typeof params?.id === 'string' ? params.id : null} />
        </GameShell>
    );
}
