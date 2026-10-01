'use client';

import { useSearchParams } from 'next/navigation';
import GameShell from '@/components/GameShell';
import { RetinueScreen } from '@/components/retinue/RetinueScreen';
import { campaignHref } from '@/lib/campaign-screens';
import { useGameSession } from '@/lib/campaign-session';
import { buName } from '@/lib/retinue-view';

/**
 * 부 편성(P-R01) — 셸 머리 + 본문 하나. 화면 이름은 부 이름(「하후돈의 막부」, 설계 §3 P-R01), 장수를 모르면 「부 편성」.
 * 작전실 장수 목록은 `?person=<retainerId>` 로 그 인물을 열고 들어온다.
 */
export default function RetinuePage() {
    const { serverId, frontInfo } = useGameSession();
    const lord = frontInfo?.general.name;
    const linked = Number(useSearchParams()?.get('person'));
    return (
        <GameShell title={lord ? buName(lord) : '부 편성'}>
            <RetinueScreen
                initialPerson={Number.isInteger(linked) && linked > 0 ? linked : null}
                hrefs={{
                    yuedan: campaignHref('retinue/yuedan', serverId),
                    // 사람 미리 채우기는 조정 화면이 받게 되면 붙인다 — 지금은 조정 화면으로만 간다.
                    dispatch: () => campaignHref('court', serverId),
                    flow: (inputId) => `${campaignHref('', serverId)}?do=${encodeURIComponent(inputId)}`,
                }}
            />
        </GameShell>
    );
}
