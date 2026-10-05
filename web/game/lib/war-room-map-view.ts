import type { ViewLevel } from '@opensamguk/ui/map/topdown';

/**
 * 작전실 지도를 여는 보기(주소 `?view=ju|commandery|county&focus=<城 id>`, K0 10-02 배정).
 * K8 천하 형세 「지도에서 보기 — 주 경계」 같은 바로가기가 쓴다. 처음 열 때 한 번만 맞추고, 그 뒤는 사용자 조작을 따른다.
 * 모르는 값은 버린다(기본 보기). 지금은 새 지도(교체 스위치 + bakeId)에서만 듣고, 옛 지도는 기본 보기 그대로다.
 */
export interface WarRoomMapView {
    readonly level: ViewLevel | null;
    readonly focusCityId: number | null;
}

const LEVELS: readonly ViewLevel[] = ['ju', 'commandery', 'county'];

export function parseWarRoomMapView(params: Pick<URLSearchParams, 'get'> | null | undefined): WarRoomMapView {
    const view = params?.get('view') ?? null;
    const focus = params?.get('focus') ?? null;
    return {
        level: view != null && (LEVELS as readonly string[]).includes(view) ? view as ViewLevel : null,
        focusCityId: focus != null && /^[1-9]\d{0,8}$/.test(focus) ? Number(focus) : null,
    };
}

/** 작전실을 그 보기로 여는 주소의 검색 부분(`?view=ju`, `?view=county&focus=12`). 앞의 작전실 경로는 부르는 쪽이 붙인다. */
export function warRoomMapSearch(level: ViewLevel, focusCityId?: number | null): string {
    const params = new URLSearchParams({ view: level });
    if (focusCityId != null) params.set('focus', String(focusCityId));
    return `?${params.toString()}`;
}
