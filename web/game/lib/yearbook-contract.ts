// 연감 계약(계약판 K5-08, 소유 C7 + C4) — 서버는 아직 없다(계약판 「요청」). 화면은 이 모양만 읽는다.
//  GET /api/yearbook/years → [{year, published}]
//  GET /api/yearbook?year=&cursor= → {year, territory:[{nationId,name,color,countyCount,capitalCityId}], events:[GameEventDto], nextCursor}
// 연말 snapshot · 세력별 현 목록 · 연말 판도(ownership) · 결손 뜻은 계약판 「K5 → C7 연감 소비 안」(K5-WAIT-04) 모양으로
// 미리 지어 둔다(D124). 서버가 그 칸을 주지 않으면 지금처럼 서버 대기로 그린다. 정확한 이름은 C4 · C7 답에 맞춘다.

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
    /** 그해 말 소속 현(당시 표시명). 없으면 서버가 아직 주지 않는다(서버 대기). */
    readonly counties?: readonly YearbookCounty[];
}

export interface YearbookCounty {
    readonly cityId: number;
    readonly name: string;
}

/** 페이지 전체가 묶이는 연말 snapshot — 사건을 더 받아도 같은 revision 이어야 한다. */
export interface YearbookSnapshot {
    readonly worldId: number;
    readonly year: number;
    readonly revision: string;
    readonly publishedAt: string;
}

/** 연말 판도 — mapPin 은 province index 가 가리키는 지도 판(bake id). 그 판으로만 칠한다. */
export interface YearbookOwnership {
    readonly revision: string;
    readonly mapPin: string;
    readonly provinces: readonly { readonly index: number; readonly nationId: number }[];
}

/** 발행됐지만 원천이 없는 칸(그 칸은 null) — 다른 값으로 채우지 않는다. */
export type YearbookAbsent = 'ownership' | 'counties';

export interface YearbookPage {
    readonly year: number;
    readonly territory: readonly YearbookTerritory[];
    /** 그해 공개 사건(알림체, 주요 선정은 서버). */
    readonly events: readonly GameEvent[];
    readonly nextCursor: string | null;
    readonly snapshot?: YearbookSnapshot;
    /** undefined = 서버가 아직 주지 않음(서버 대기), null = 원천 결손(absent 에 'ownership'). */
    readonly ownership?: YearbookOwnership | null;
    readonly absent?: readonly YearbookAbsent[];
}
