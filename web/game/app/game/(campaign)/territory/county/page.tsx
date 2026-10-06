'use client';

import Link from 'next/link';
import { StatusView } from '@opensamguk/ui';
import GameShell from '@/components/GameShell';
import { CountyScreen } from '@/components/county/CountyScreen';
import { useGameSession } from '@/lib/campaign-session';
import { countyHrefs } from './county-hrefs';

/**
 * 영지 › 현 상세 — 셸 하위 탭 「현 상세」의 첫 화면. 현을 고르지 않고 들어오면 내 장수가 선 현을 보인다.
 * 성 밖이면(front-info city 없음) 그 사유와 영지로 가는 고리.
 */
export default function MyCountyPage() {
    const { serverId, frontInfo, loading } = useGameSession();
    const hrefs = countyHrefs(serverId);
    const cityId = frontInfo?.city?.id ?? null;
    return (
        <GameShell title="영지">
            {cityId != null ? <CountyScreen cityId={cityId} hrefs={hrefs} />
                : loading || !frontInfo ? <StatusView kind="loading" rows={6} />
                : <StatusView kind="empty" title="장수가 성에 있지 않습니다" body="성 밖에서는 선 현이 없습니다. 영지에서 우리 현을 고르세요."
                    actions={<Link href={hrefs.territory()} className="os-button">영지로</Link>} />}
        </GameShell>
    );
}
