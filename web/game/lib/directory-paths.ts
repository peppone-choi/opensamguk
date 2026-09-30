// 인물 일람 · 현 목록 조회 주소. api.ts 와 directory-reads.ts 가 함께 쓴다(React 없이 — 순환 import 를 피한다).
import type { CountyScope, PeopleQuery } from './directory-reads';

/** 서버가 받는 정렬은 지금 ID 하나다(`CampaignDirectoryReader.validatePage`). 다른 정렬은 서버가 열면 더한다. */
export const PEOPLE_SORT = 'ID' as const;
export const PEOPLE_PAGE_LIMIT = 50;

export function peoplePath(query: PeopleQuery, cursor: string | null): string {
    const params = new URLSearchParams({ scope: query.scope, q: query.q.trim(), sort: PEOPLE_SORT, limit: String(query.limit) });
    if (cursor) params.set('cursor', cursor);
    return `/api/people?${params.toString()}`;
}

export function countiesPath(generalId: number, scope: CountyScope, commanderyId?: string | null): string {
    const params = new URLSearchParams({ generalId: String(generalId), scope });
    if (scope === 'COMMANDERY' && commanderyId) params.set('commanderyId', commanderyId);
    return `/api/counties?${params.toString()}`;
}
