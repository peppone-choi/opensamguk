'use client';

import { useParams } from 'next/navigation';
import GameShell from '@/components/GameShell';
import { PersonScreen } from '@/components/person/PersonScreen';
import { campaignHref } from '@/lib/campaign-screens';
import { useGameSession } from '@/lib/campaign-session';
import { parseGeneralId } from '@/lib/person-view';

/**
 * 부 › 인물 상세(P-R03) — `/game/<서버>/retinue/people/<장수 id>`. 인물 일람 · 부 편성 · 작전실 내 장수 카드에서 들어온다.
 * 인물 상세 읽기(K4-13)가 오기 전에는 나 · 내 부 인물만 채우고, 다른 인물은 「서버 대기」다.
 */
export default function PersonPage() {
    const { serverId } = useGameSession();
    const params = useParams<{ generalId: string }>();
    return (
        <GameShell title="인물 상세">
            <PersonScreen generalId={parseGeneralId(params?.generalId)} hrefs={{
                people: campaignHref('retinue/people', serverId),
                retinue: (retainerId) => campaignHref(retainerId != null ? `retinue?person=${retainerId}` : 'retinue', serverId),
                // 사람 미리 채우기는 조정 화면이 받게 되면 붙인다 — 지금은 조정 화면으로만 간다(부 편성과 같다).
                dispatch: () => campaignHref('court', serverId),
            }} />
        </GameShell>
    );
}
