// 연감 계약(계약판 K5-08, 소유 C7 + C4) — 서버는 아직 없다(계약판 「요청」). 화면은 이 모양만 읽는다.
//  GET /api/yearbook/years → [{year, published}]
//  GET /api/yearbook?year=&cursor= → {year, territory:[{nationId,name,color,countyCount,capitalCityId}], events:[GameEventDto], nextCursor}
// 연말 소유 스냅샷(ownership — 판도 지도)과 세력별 현 목록은 계약에 아직 없다(요청 중) — 화면이 서버 대기로 그린다.

import type { GameEvent } from '@opensamguk/ui';

export interface YearbookYear {
    readonly year: number;
    /** 그해 연감이 나왔는지(한 해가 끝나면 나온다). */
    readonly published: boolean;
}

export interface YearbookTerritory {
    /** 0 이면 무주(어느 세력도 갖지 않은 현) — 서버가 주면 그대로 그린다. */
    readonly nationId: number;
    readonly name: string;
    readonly color: string;
    readonly countyCount: number;
    readonly capitalCityId: number | null;
}

export interface YearbookPage {
    readonly year: number;
    readonly territory: readonly YearbookTerritory[];
    /** 그해 공개 사건(알림체, 주요 선정은 서버). */
    readonly events: readonly GameEvent[];
    readonly nextCursor: string | null;
}
