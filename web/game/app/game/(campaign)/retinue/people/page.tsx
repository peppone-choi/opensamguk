'use client';

import { useSearchParams } from 'next/navigation';
import GameShell from '@/components/GameShell';
import { PeopleScreen } from '@/components/people/PeopleScreen';
import { campaignHref } from '@/lib/campaign-screens';
import { useGameSession } from '@/lib/campaign-session';
import { scopeFromQuery } from '@/lib/people-view';
import { useRecordNames } from '@/lib/records-names';

/**
 * 인물 일람(P-R02) — 셸 머리 + 본문 하나. 정렬 · 초성 찾기는 서버(#1103). 소재 城 이름은 지도 미리보기 이름(K5 useRecordNames).
 * 인물 상세(P-R03) · 받는 사람을 채운 서신 쓰기는 아직 화면이 없어 넘기지 않는다(부품이 그 고리를 그리지 않는다).
 */
export default function PeoplePage() {
    const { serverId, generalId, frontInfo } = useGameSession();
    const names = useRecordNames(generalId, frontInfo?.general.name ?? null);
    const scope = scopeFromQuery(useSearchParams()?.get('scope'));
    return (
        <GameShell title="인물 일람">
            <PeopleScreen
                initialScope={scope}
                cityName={(cityId) => names.city(cityId) ?? null}
                hrefs={{ search: `${campaignHref('', serverId)}?do=action.search` }}
            />
        </GameShell>
    );
}
