// 목 작전실 지도를 HanMapCanvas 에 먹이는 어댑터.
//
// 지형은 실제 `data/map/han-tiles.json` 이고(개발 전용 경로로 서빙), 城 표와 좌표도 동결된 1133 판
// 실측이다. **세력 배정만 목이다.** API 가 붙으면 이 파일과 hwiha-map-mock.ts 를 함께 걷어낸다.

import type { IsoCityOverlay } from '@opensamguk/ui';
import { HWIHA_MAP_MOCK } from './hwiha-map-mock';

/** 개발 전용 지형 경로 — `/api/game/**` 는 게이트웨이로 프록시되므로 그 밖에 둔다. */
export const HWIHA_MOCK_TERRAIN_URL = '/hwiha-mock/terrain';

/** 내가 있는 縣 — 시안 예시 상황의 양적현(영천군 치소). 지도를 여기 기준으로 당겨서 연다. */
export const HWIHA_HOME_CITY_ID = 122;

interface MockCity {
    readonly id: number;
    readonly name: string;
    readonly level: number;
    readonly nationId: number;
    readonly x: number;
    readonly y: number;
    readonly state: number;
    readonly supply: boolean;
    readonly province: string;
    readonly commandery: string;
    readonly county: string;
}

const NATIONS = new Map(
    (HWIHA_MAP_MOCK.nations as unknown as { id: number; name: string; color: string }[]).map((n) => [
        n.id,
        n,
    ]),
);

/**
 * 지도 이름표는 **縣 이름만** 쓴다(사용자 지시) — 동명이지 구분 郡 은 이름에 섞지 않고
 * `commanderyName` 으로 따로 넘긴다. 州 는 `regionName` 이다: 州 → 郡 → 縣.
 */
export const HWIHA_MAP_CITIES: readonly IsoCityOverlay[] = (
    HWIHA_MAP_MOCK.cities as unknown as MockCity[]
).map((c) => {
    const nation = NATIONS.get(c.nationId);
    return {
        id: c.id,
        name: c.county,
        mapLabel: c.county,
        level: c.level,
        nationId: c.nationId,
        nationName: nation?.name,
        nationColor: nation?.color,
        x: c.x,
        y: c.y,
        regionName: c.province,
        commanderyName: c.commandery || undefined,
        state: c.state,
        supply: c.supply,
        interactive: true,
    } satisfies IsoCityOverlay;
});
