'use client';

import GameShell from '@/components/GameShell';
import { CommanderyScreen } from '@/components/commandery/CommanderyScreen';
import { useGameSession } from '@/lib/campaign-session';
import { commanderyHrefs } from './commandery-hrefs';

/**
 * 영지 › 군 내정 현황(P-T03) 탭 첫 화면 — 군을 고르기 전이라 우리 세력 전체의 현을 한 표로 본다.
 * 한 군은 `/territory/commandery/<군 id>`(현 상세 · 지도에서 들어온다).
 */
export default function CommanderyIndexPage() {
    const { serverId } = useGameSession();
    return (
        <GameShell title="영지">
            <CommanderyScreen commanderyId={null} hrefs={commanderyHrefs(serverId)} />
        </GameShell>
    );
}
