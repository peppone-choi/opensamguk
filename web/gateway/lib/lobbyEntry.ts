// 로비 서버 행의 진입 상태와 필터(ADR-LITE-049 02). 페이지 파일은 default/metadata 만 export 할 수 있어 여기 둔다.
export interface EntryGameInfo {
    isUnited: number;
}
export interface EntryInfo {
    game: EntryGameInfo | null;
    me: { name: string } | null;
}

/** 참가 중(내 장수 있음) / 참가 가능 / 종료(천하통일·이벤트 종료) / 폐쇄(현황 없음). */
export type EntryState = 'loading' | 'joined' | 'open' | 'ended' | 'closed';
export type EntryFilter = 'all' | 'joined' | 'open' | 'ended';

export function entryStateOf(loading: boolean, info: EntryInfo | null): EntryState {
    if (loading) return 'loading';
    const game = info?.game ?? null;
    if (!game) return 'closed';
    if (info?.me?.name) return 'joined';
    if (game.isUnited === 2 || game.isUnited === 3) return 'ended';
    return 'open';
}

export function matchesFilter(state: EntryState, filter: EntryFilter): boolean {
    if (filter === 'all') return true;
    if (state === 'loading') return true;
    return state === filter;
}

export const ENTRY_FILTERS: { key: EntryFilter; label: string }[] = [
    { key: 'all', label: '전체' },
    { key: 'joined', label: '참가 중' },
    { key: 'open', label: '참가 가능' },
    { key: 'ended', label: '종료' },
];
