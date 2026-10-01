'use client';

import { useRouter } from 'next/navigation';
import { useState } from 'react';
import GameShell from '@/components/GameShell';
import { BattleHub, type AbsenceLoad } from '@/components/battle/BattleHub';
import { api } from '@/lib/api';
import { toAbsence } from '@/lib/battle/absence';
import { useCampaignRead } from '@/lib/campaign-reads';
import { useGameSession } from '@/lib/campaign-session';
import { useServerGameUrl } from '@/lib/serverGameUrl';

/**
 * 전투 · 부재 대비(P-C04) — K6 설계서 §3.5, 보드 V31K6Battles · MBattles. 새 화면(옛 battle-center 는 삼모 · K9 삭제 대상).
 *
 * 왼쪽 「내 전투」는 서버가 전투를 열지 않아(K6-11 · CONTRACT:CAMPAIGN_BATTLE_PRODUCER) 영역 전체가 서버 대기다.
 * 오른쪽 「부재 대비」는 방침 읽기(`/api/policies`)로 내 출전 군단 · 내가 맡은 현의 방침을 보이고, 고치기는 배치 · 방침
 * 화면(영지, P-T01)과 계책 덱(P-S01)으로 간다. 입력은 없다(연결만).
 */
export default function BattlePage() {
    const session = useGameSession();
    const router = useRouter();
    const [seq, setSeq] = useState(0);
    const read = useCampaignRead((id, signal) => api.campaignPolicies(id, signal), [seq]);
    const territoryHref = useServerGameUrl('territory');
    const stratagemHref = useServerGameUrl('stratagem');
    const retry = () => setSeq((n) => n + 1);

    const absence: AbsenceLoad = read.error ? { state: 'error', onRetry: retry }
        : read.data && session.generalId != null ? { state: 'ready', view: toAbsence(read.data, session.generalId), onRetry: retry }
        : { state: 'loading' };

    return (
        <GameShell title="전투 · 부재 대비">
            <BattleHub absence={absence} onOpenPolicy={() => router.push(territoryHref)} onOpenStratagem={() => router.push(stratagemHref)} />
        </GameShell>
    );
}
