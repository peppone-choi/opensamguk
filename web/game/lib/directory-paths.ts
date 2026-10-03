// 인물 일람 · 현 목록 조회 주소. api.ts 와 directory-reads.ts 가 함께 쓴다(React 없이 — 순환 import 를 피한다).
import type { CountyScope, PeopleQuery } from './directory-reads';

/**
 * 서버가 받는 정렬 키(#1103 K4-19/20, `PeopleDirectoryPaging.PeopleSort`) — 서버가 정렬하고 커서로 잇는다(받은 쪽만 다시 정렬하지 않는다).
 * 값이 없는(시야 · 권한 밖) 인물은 서버가 정한 자리에 둔다.
 */
export const PEOPLE_SORTS = [
    'ID', 'NAME', 'AFFILIATION', 'LEADERSHIP', 'STRENGTH', 'INTEL', 'POLITICS', 'CHARM', 'TOTAL',
    'COMMAND', 'ADMINISTRATION', 'STRATEGY', 'ENVOY', 'AGE',
] as const;
export type PeopleSort = (typeof PEOPLE_SORTS)[number];
export type PeopleDirection = 'ASC' | 'DESC';
export const PEOPLE_PAGE_LIMIT = 50;

export function peoplePath(query: PeopleQuery, cursor: string | null): string {
    const params = new URLSearchParams({ scope: query.scope, q: query.q.trim(), sort: query.sort ?? 'ID', limit: String(query.limit) });
    // 방향은 기본(ASC)이 아닐 때만 — 옛 주소 모양을 그대로 둔다.
    if (query.direction === 'DESC') params.set('direction', 'DESC');
    if (cursor) params.set('cursor', cursor);
    return `/api/people?${params.toString()}`;
}

export function countiesPath(generalId: number, scope: CountyScope, commanderyId?: string | null): string {
    const params = new URLSearchParams({ generalId: String(generalId), scope });
    if (scope === 'COMMANDERY' && commanderyId) params.set('commanderyId', commanderyId);
    return `/api/counties?${params.toString()}`;
}
