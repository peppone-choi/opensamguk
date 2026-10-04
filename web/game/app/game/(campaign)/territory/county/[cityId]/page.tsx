'use client';

import { useParams } from 'next/navigation';
import GameShell from '@/components/GameShell';
import { CountyScreen } from '@/components/county/CountyScreen';
import { useGameSession } from '@/lib/campaign-session';
import { parseCityId } from '@/lib/county-view';
import { countyHrefs } from '../county-hrefs';

/**
 * 영지 › 현 상세(P-T02) — `/game/<서버>/territory/county/<현 id>`. 옛 `/game/city?id=` 와 지도 「도시 정보」(MapCityDetail)를 대신한다.
 * 縣 상세 읽기(K4-04)가 오기 전에는 7지표(내 장수가 선 현 말고) · 수비군 · 이 현의 사람 · 최근 사건이 서버 대기다.
 */
export default function CountyPage() {
    const { serverId } = useGameSession();
    const params = useParams<{ cityId: string }>();
    return (
        <GameShell title="영지">
            <CountyScreen cityId={parseCityId(params?.cityId)} hrefs={countyHrefs(serverId)} />
        </GameShell>
    );
}
